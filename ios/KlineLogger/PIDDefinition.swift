//
//  PIDDefinition.swift
//  KlineLogger
//
//  Created by Douglas Rowland on 10/5/26.
//
//  Channel definitions ported from drowland341/kline-logger's pids.json,
//  which was reverse-engineered against a 2007 Kawasaki Ultra LX ECU.

import Foundation

struct PIDDefinition: Codable {
    var name: String
    var pid: UInt8
    var formula: String
    var units: String
    var enabled: Bool
    var every: Int
    var verified: Bool
    var notes: String
    var gaugeMin: Double?
    var gaugeMax: Double?

    private enum CodingKeys: String, CodingKey {
        case name, pid, formula, units, enabled, every, verified, notes
        case gaugeMin = "gauge_min"
        case gaugeMax = "gauge_max"
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        name = try c.decode(String.self, forKey: .name)
        let pidString = try c.decode(String.self, forKey: .pid)
        let cleaned = pidString.lowercased().replacingOccurrences(of: "0x", with: "")
        guard let parsedPid = UInt8(cleaned, radix: 16) else {
            throw DecodingError.dataCorruptedError(forKey: .pid, in: c, debugDescription: "Invalid PID hex string: \(pidString)")
        }
        pid = parsedPid
        formula = try c.decodeIfPresent(String.self, forKey: .formula) ?? "raw"
        units = try c.decodeIfPresent(String.self, forKey: .units) ?? "raw"
        enabled = try c.decodeIfPresent(Bool.self, forKey: .enabled) ?? true
        every = max(1, try c.decodeIfPresent(Int.self, forKey: .every) ?? 1)
        verified = try c.decodeIfPresent(Bool.self, forKey: .verified) ?? false
        notes = try c.decodeIfPresent(String.self, forKey: .notes) ?? ""
        gaugeMin = try c.decodeIfPresent(Double.self, forKey: .gaugeMin)
        gaugeMax = try c.decodeIfPresent(Double.self, forKey: .gaugeMax)
    }
}

private struct PIDDefinitionFile: Codable {
    var pids: [PIDDefinition]
}

enum PIDLibrary {
    static let all: [PIDDefinition] = {
        guard let url = Bundle.main.url(forResource: "pids", withExtension: "json") else { return [] }
        guard let data = try? Data(contentsOf: url) else { return [] }
        guard let file = try? JSONDecoder().decode(PIDDefinitionFile.self, from: data) else { return [] }
        return file.pids
    }()

    static func name(forPID pid: UInt8) -> String? {
        all.first { $0.pid == pid }?.name
    }
}
