//
//  DiagnosticModels.swift
//  KlineLogger
//
//  Created by Douglas Rowland on 10/5/26.
//

import Foundation

struct PIDReading: Identifiable {
    let id = UUID()
    let name: String
    let shortCode: String
    let value: String
    let unit: String
    let icon: String
    let isFault: Bool
    let faultReason: String?
}

struct DiagnosticTroubleCode: Identifiable {
    let id = UUID()
    let code: String
    let description: String
    let isActive: Bool
}

struct ScannedPID: Identifiable {
    let id = UUID()
    let address: String
    let name: String?
    let didRespond: Bool
    var rawValue: String? = nil
}

struct WatchedPIDSample: Identifiable {
    let id: UInt8
    var address: String
    var name: String?
    var rawValue: String
    var low: Int
    var high: Int
}

enum DemoData {
    static let livePIDs: [PIDReading] = [
        PIDReading(name: "Engine RPM", shortCode: "0x0C", value: "4250", unit: "RPM", icon: "gauge.with.dots.needle.67percent", isFault: false, faultReason: nil),
        PIDReading(name: "Coolant Temp", shortCode: "0x05", value: "187", unit: "°F", icon: "thermometer", isFault: false, faultReason: nil),
        PIDReading(name: "Battery Voltage", shortCode: "0x42", value: "12.9", unit: "V", icon: "bolt.fill", isFault: false, faultReason: nil),
        PIDReading(name: "Throttle Position", shortCode: "0x11", value: "38", unit: "%", icon: "gauge.medium", isFault: false, faultReason: nil),
        PIDReading(name: "Intake Air Temp", shortCode: "0x0F", value: "—", unit: "", icon: "wind", isFault: true, faultReason: "Sensor unplugged or reading out of range"),
        PIDReading(name: "O2 Sensor", shortCode: "0x14", value: "0.91", unit: "V", icon: "aqi.medium", isFault: false, faultReason: nil),
        PIDReading(name: "Fuel Level", shortCode: "0x2F", value: "64", unit: "%", icon: "fuelpump.fill", isFault: false, faultReason: nil),
        PIDReading(name: "Vehicle Speed", shortCode: "0x0D", value: "41", unit: "mph", icon: "speedometer", isFault: false, faultReason: nil),
    ]

    static let troubleCodes: [DiagnosticTroubleCode] = [
        DiagnosticTroubleCode(code: "P0113", description: "Intake Air Temperature Sensor Circuit High Input", isActive: true),
        DiagnosticTroubleCode(code: "P0563", description: "System Voltage High", isActive: false),
    ]

    static let scanCandidates: [(address: String, name: String?)] = [
        ("0x04", "Calculated Engine Load"),
        ("0x05", "Coolant Temperature"),
        ("0x0C", "Engine RPM"),
        ("0x0D", "Vehicle Speed"),
        ("0x0F", "Intake Air Temperature"),
        ("0x11", "Throttle Position"),
        ("0x14", "O2 Sensor Voltage"),
        ("0x1F", "Run Time Since Start"),
        ("0x2F", "Fuel Level"),
        ("0x42", "Battery Voltage"),
        ("0x46", "Ambient Air Temperature"),
        ("0x5C", "Oil Temperature"),
        ("0x61", "Demanded Torque"),
        ("0x70", nil),
        ("0x8A", nil),
    ]
}
