//
//  ELM327Link.swift
//  KlineLogger
//
//  The one seam between OBDEngine's protocol logic (AT commands, KWP2000 framing,
//  request pacing) and the physical radio. WiFiOBDManager wraps an NWConnection and
//  BluetoothManager wraps a CBPeripheral's characteristics; both expose the same
//  byte-in/byte-out shape so OBDEngine never needs to know which one it's talking to.
//

import Foundation

protocol ELM327Link: AnyObject {
    /// Sends raw bytes to the adapter (OBDEngine appends the trailing "\r" itself).
    func write(_ data: Data) async throws

    /// Suspends until the next chunk of incoming data arrives. Throws if the link
    /// drops while waiting.
    func nextIncomingChunk() async throws -> Data
}
