//
//  WiFiOBDManager.swift
//  KlineLogger
//
//  Created by Douglas Rowland on 10/5/26.
//
//  Transport layer only: opens a TCP socket to the Veepeak's ELM327 Wi-Fi interface
//  and exposes it as an ELM327Link. All the AT-command/KWP2000 session, polling,
//  scanning, and watching logic lives in OBDEngine, shared with BluetoothManager.

import Foundation
import Network
import Observation

@Observable
final class WiFiOBDManager {
    private(set) var phase: ConnectionPhase = .idle
    let engine = OBDEngine()

    private var connection: NWConnection?
    private var connectTimeoutTask: Task<Void, Never>?

    private let host: NWEndpoint.Host = "192.168.0.10"
    private let port: NWEndpoint.Port = 35000
    private let connectTimeout: Duration = .seconds(30)
    private let adapterDisplayName = "OBDCheck Wi-Fi"

    // MARK: - Connect / disconnect

    func connect() {
        engine.stop()
        phase = .connecting(adapterDisplayName)

        let conn = NWConnection(host: host, port: port, using: .tcp)
        connection = conn

        conn.stateUpdateHandler = { [weak self] state in
            guard let self else { return }
            switch state {
            case .ready:
                self.connectTimeoutTask?.cancel()
                self.engine.start(
                    link: self,
                    onEstablished: { [weak self] in
                        guard let self else { return }
                        self.phase = .connected(self.adapterDisplayName)
                    },
                    onFailed: { [weak self] message in
                        self?.phase = .failed(message)
                    }
                )
            case .failed(let error):
                self.connectTimeoutTask?.cancel()
                self.phase = .failed("Couldn't reach the adapter at \(self.host):\(self.port): \(error.localizedDescription). Make sure your phone is joined to the adapter's Wi-Fi network (usually named something like \"OBDCHECK\") in Settings.")
            default:
                break
            }
        }
        conn.start(queue: .main)
        armConnectTimeout()
    }

    func cancel() {
        connectTimeoutTask?.cancel()
        engine.stop()
        connection?.cancel()
        connection = nil
        phase = .idle
    }

    func disconnect() {
        engine.stop()
        connection?.cancel()
        connection = nil
        phase = .idle
    }

    private func armConnectTimeout() {
        connectTimeoutTask?.cancel()
        connectTimeoutTask = Task { [weak self, connectTimeout] in
            try? await Task.sleep(for: connectTimeout)
            guard let self, !Task.isCancelled else { return }
            if case .connecting = self.phase {
                self.connection?.cancel()
                self.phase = .failed("Timed out reaching the adapter's Wi-Fi network. Make sure your phone is joined to the adapter's Wi-Fi (usually named something like \"OBDCHECK\") before connecting.")
            }
        }
    }
}

extension WiFiOBDManager: ELM327Link {
    func write(_ data: Data) async throws {
        guard let connection else { throw OBDLinkError.notConnected }
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            connection.send(content: data, completion: .contentProcessed { error in
                if let error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume()
                }
            })
        }
    }

    func nextIncomingChunk() async throws -> Data {
        guard let connection else { throw OBDLinkError.notConnected }
        return try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Data, Error>) in
            connection.receive(minimumIncompleteLength: 1, maximumLength: 1024) { data, _, _, error in
                if let error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume(returning: data ?? Data())
                }
            }
        }
    }
}
