//
//  BluetoothManager.swift
//  KlineLogger
//
//  Created by Douglas Rowland on 10/4/26.
//

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

    private var centralManager: CBCentralManager!
    private var targetPeripheral: CBPeripheral?
    private var scanTimeoutTask: Task<Void, Never>?

    private let nameHint = "veepeak"
    private let scanTimeout: Duration = .seconds(15)

    override init() {
        super.init()
        centralManager = CBCentralManager(delegate: self, queue: nil)
    }

    func connect() {
        guard centralManager.state == .poweredOn else {
            phase = .failed("Bluetooth is off. Turn it on in Settings and try again.")
            return
        }
        nearbyDeviceNames = []
        discoveredServices = []
        phase = .scanning
        centralManager.scanForPeripherals(withServices: nil, options: [CBCentralManagerScanOptionAllowDuplicatesKey: false])
        armScanTimeout()
    }

    func cancel() {
        scanTimeoutTask?.cancel()
        centralManager.stopScan()
        if let targetPeripheral {
            centralManager.cancelPeripheralConnection(targetPeripheral)
        }
        targetPeripheral = nil
        phase = .idle
    }

    func disconnect() {
        if let targetPeripheral {
            centralManager.cancelPeripheralConnection(targetPeripheral)
        }
        targetPeripheral = nil
        discoveredServices = []
        phase = .idle
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
        phase = .connected(peripheral.name ?? "Adapter")
        peripheral.delegate = self
        peripheral.discoverServices(nil)
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        targetPeripheral = nil
        phase = .failed("Couldn't connect: \(error?.localizedDescription ?? "unknown error").")
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        targetPeripheral = nil
        discoveredServices = []
        phase = .idle
    }
}

extension BluetoothManager: CBPeripheralDelegate {
    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard error == nil, let services = peripheral.services else { return }
        for service in services {
            if !discoveredServices.contains(where: { $0.id == service.uuid }) {
                discoveredServices.append(DiscoveredService(id: service.uuid, characteristicUUIDs: []))
            }
            peripheral.discoverCharacteristics(nil, for: service)
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard error == nil, let characteristics = service.characteristics else { return }
        guard let index = discoveredServices.firstIndex(where: { $0.id == service.uuid }) else { return }
        discoveredServices[index].characteristicUUIDs = characteristics.map { characteristic in
            "\(characteristic.uuid.uuidString)  [\(characteristic.properties.description)]"
        }
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
