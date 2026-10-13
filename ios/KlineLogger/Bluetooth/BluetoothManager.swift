//
//  BluetoothManager.swift
//  KlineLogger
//
//  Created by Douglas Rowland on 10/4/26.
//
//  Transport layer only: scans for and connects to the Veepeak over Bluetooth LE,
//  auto-detects which GATT characteristic(s) carry the ELM327 serial stream, and
//  exposes that as an ELM327Link. All the AT-command/KWP2000 session, polling,
//  scanning, and watching logic lives in OBDEngine, shared with WiFiOBDManager.
//
//  The exact characteristic UUIDs aren't known ahead of time — ELM327 BLE clones
//  don't share one standard GATT profile — so selection is capability-based: prefer
//  a single characteristic that both writes and notifies (the common pattern for
//  these adapters), otherwise pair the first writable one with the first notifying
//  one. "Adapter Info" in the app shows every discovered service/characteristic, so
//  a wrong pick is diagnosable without re-flashing anything.

import CoreBluetooth
import Observation

struct DiscoveredService: Identifiable {
    let id: CBUUID
    var uuid: CBUUID { id }
    var characteristicUUIDs: [String]
}

@Observable
final class BluetoothManager: NSObject {
    private(set) var phase: ConnectionPhase = .idle
    private(set) var nearbyDeviceNames: [String] = []
    private(set) var discoveredServices: [DiscoveredService] = []
    let engine = OBDEngine()

    private var centralManager: CBCentralManager!
    private var targetPeripheral: CBPeripheral?
    private var scanTimeoutTask: Task<Void, Never>?

    private let nameHint = "veepeak"
    private let scanTimeout: Duration = .seconds(15)

    private var allCharacteristics: [CBCharacteristic] = []
    private var pendingCharacteristicDiscoveries = 0
    private var hasStartedCharacteristicSelection = false
    private var writeCharacteristic: CBCharacteristic?
    private var writeType: CBCharacteristicWriteType = .withResponse
    private var notifyCharacteristic: CBCharacteristic?

    private var incomingContinuation: AsyncStream<Data>.Continuation?
    private var incomingIterator: AsyncStream<Data>.AsyncIterator?
    private var pendingWriteContinuation: CheckedContinuation<Void, Error>?

    override init() {
        super.init()
        centralManager = CBCentralManager(delegate: self, queue: nil)
    }

    func connect() {
        guard centralManager.state == .poweredOn else {
            phase = .failed("Bluetooth is off. Turn it on in Settings and try again.")
            return
        }
        engine.stop()
        nearbyDeviceNames = []
        discoveredServices = []
        resetCharacteristicSelection()
        setUpIncomingStream()
        phase = .scanning
        centralManager.scanForPeripherals(withServices: nil, options: [CBCentralManagerScanOptionAllowDuplicatesKey: false])
        armScanTimeout()
    }

    func cancel() {
        scanTimeoutTask?.cancel()
        centralManager.stopScan()
        engine.stop()
        teardownPeripheral()
        phase = .idle
    }

    func disconnect() {
        engine.stop()
        teardownPeripheral()
        phase = .idle
    }

    private func resetCharacteristicSelection() {
        allCharacteristics = []
        pendingCharacteristicDiscoveries = 0
        hasStartedCharacteristicSelection = false
        writeCharacteristic = nil
        writeType = .withResponse
        notifyCharacteristic = nil
    }

    private func setUpIncomingStream() {
        incomingContinuation?.finish()
        let stream = AsyncStream<Data> { [weak self] continuation in
            self?.incomingContinuation = continuation
        }
        incomingIterator = stream.makeAsyncIterator()
    }

    private func teardownPeripheral() {
        if let targetPeripheral {
            centralManager.cancelPeripheralConnection(targetPeripheral)
        }
        targetPeripheral = nil
        discoveredServices = []
        resetCharacteristicSelection()
        incomingContinuation?.finish()
        incomingContinuation = nil
        pendingWriteContinuation?.resume(throwing: OBDLinkError.connectionClosed)
        pendingWriteContinuation = nil
    }

    private func armScanTimeout() {
        scanTimeoutTask?.cancel()
        scanTimeoutTask = Task { [weak self, scanTimeout] in
            try? await Task.sleep(for: scanTimeout)
            guard let self, !Task.isCancelled else { return }
            if self.phase == .scanning {
                self.centralManager.stopScan()
                self.phase = .failed("Couldn't find a Veepeak adapter nearby. Make sure it's powered on (ignition on) and close to your phone.")
            }
        }
    }

    // MARK: - Characteristic selection

    private func finishCharacteristicDiscoveryIfReady(peripheral: CBPeripheral) {
        guard pendingCharacteristicDiscoveries <= 0, !hasStartedCharacteristicSelection else { return }
        hasStartedCharacteristicSelection = true

        if let combined = allCharacteristics.first(where: {
            $0.properties.contains(.notify) && ($0.properties.contains(.write) || $0.properties.contains(.writeWithoutResponse))
        }) {
            writeCharacteristic = combined
            notifyCharacteristic = combined
        } else {
            writeCharacteristic = allCharacteristics.first { $0.properties.contains(.write) || $0.properties.contains(.writeWithoutResponse) }
            notifyCharacteristic = allCharacteristics.first { $0.properties.contains(.notify) }
        }

        guard let writeCharacteristic, let notifyCharacteristic else {
            phase = .failed("Couldn't find a serial data channel on this adapter's Bluetooth profile. Check Adapter Info for the raw services/characteristics.")
            return
        }

        writeType = writeCharacteristic.properties.contains(.write) ? .withResponse : .withoutResponse
        peripheral.setNotifyValue(true, for: notifyCharacteristic)
    }
}

extension BluetoothManager: CBCentralManagerDelegate {
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        if central.state != .poweredOn, phase == .scanning {
            phase = .failed("Bluetooth is off. Turn it on in Settings and try again.")
        }
    }

    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral, advertisementData: [String: Any], rssi RSSI: NSNumber) {
        guard phase == .scanning else { return }
        let name = peripheral.name ?? advertisementData[CBAdvertisementDataLocalNameKey] as? String ?? ""

        if !name.isEmpty, !nearbyDeviceNames.contains(name) {
            nearbyDeviceNames.append(name)
        }

        guard name.lowercased().contains(nameHint) else { return }

        scanTimeoutTask?.cancel()
        centralManager.stopScan()
        targetPeripheral = peripheral
        phase = .connecting(name)
        centralManager.connect(peripheral, options: nil)
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        peripheral.delegate = self
        peripheral.discoverServices(nil)
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        targetPeripheral = nil
        phase = .failed("Couldn't connect: \(error?.localizedDescription ?? "unknown error").")
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        targetPeripheral = nil
        engine.stop()
        teardownPeripheral()
        phase = .idle
    }
}

extension BluetoothManager: CBPeripheralDelegate {
    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard error == nil, let services = peripheral.services, !services.isEmpty else {
            phase = .failed("Couldn't discover services on the adapter: \(error?.localizedDescription ?? "no services reported").")
            return
        }
        pendingCharacteristicDiscoveries = services.count
        for service in services {
            if !discoveredServices.contains(where: { $0.id == service.uuid }) {
                discoveredServices.append(DiscoveredService(id: service.uuid, characteristicUUIDs: []))
            }
            peripheral.discoverCharacteristics(nil, for: service)
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        defer {
            pendingCharacteristicDiscoveries -= 1
            finishCharacteristicDiscoveryIfReady(peripheral: peripheral)
        }

        guard error == nil, let characteristics = service.characteristics else { return }
        guard let index = discoveredServices.firstIndex(where: { $0.id == service.uuid }) else { return }
        discoveredServices[index].characteristicUUIDs = characteristics.map { characteristic in
            "\(characteristic.uuid.uuidString)  [\(characteristic.properties.description)]"
        }
        allCharacteristics.append(contentsOf: characteristics)
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic, error: Error?) {
        guard characteristic.uuid == notifyCharacteristic?.uuid else { return }
        guard error == nil, characteristic.isNotifying else {
            phase = .failed("Couldn't subscribe to the adapter's data channel: \(error?.localizedDescription ?? "unknown error").")
            return
        }
        engine.start(
            link: self,
            onEstablished: { [weak self] in
                guard let self else { return }
                self.phase = .connected(peripheral.name ?? "Adapter")
            },
            onFailed: { [weak self] message in
                self?.phase = .failed(message)
            }
        )
    }

    func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        guard characteristic.uuid == writeCharacteristic?.uuid else { return }
        let continuation = pendingWriteContinuation
        pendingWriteContinuation = nil
        if let error {
            continuation?.resume(throwing: error)
        } else {
            continuation?.resume()
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard characteristic.uuid == notifyCharacteristic?.uuid else { return }
        if let error {
            incomingContinuation?.finish()
            phase = .failed("Lost contact with the adapter: \(error.localizedDescription)")
            return
        }
        if let data = characteristic.value, !data.isEmpty {
            incomingContinuation?.yield(data)
        }
    }
}

extension BluetoothManager: ELM327Link {
    func write(_ data: Data) async throws {
        guard let targetPeripheral, let writeCharacteristic else { throw OBDLinkError.notConnected }
        let maxLength = max(targetPeripheral.maximumWriteValueLength(for: writeType), 1)
        var offset = 0
        while offset < data.count {
            let end = min(offset + maxLength, data.count)
            let chunk = data.subdata(in: offset..<end)
            if writeType == .withResponse {
                try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                    pendingWriteContinuation = continuation
                    targetPeripheral.writeValue(chunk, for: writeCharacteristic, type: .withResponse)
                }
            } else {
                targetPeripheral.writeValue(chunk, for: writeCharacteristic, type: .withoutResponse)
            }
            offset = end
        }
    }

    func nextIncomingChunk() async throws -> Data {
        guard incomingIterator != nil else { throw OBDLinkError.notConnected }
        guard let chunk = await incomingIterator?.next() else { throw OBDLinkError.connectionClosed }
        return chunk
    }
}

private extension CBCharacteristicProperties {
    var description: String {
        var flags: [String] = []
        if contains(.read) { flags.append("read") }
        if contains(.write) { flags.append("write") }
        if contains(.writeWithoutResponse) { flags.append("writeNoResp") }
        if contains(.notify) { flags.append("notify") }
        if contains(.indicate) { flags.append("indicate") }
        return flags.isEmpty ? "—" : flags.joined(separator: ", ")
    }
}
