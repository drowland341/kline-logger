//
//  UnitSystem.swift
//  KlineLogger
//
//  Display-only unit conversion. pids.json formulas and everything derived from them
//  (OBDEngine.pidReadings, research log CSVs) stay in their native units — °C, kPa, mph —
//  so logged data is comparable across sessions regardless of what a user has this set to.
//  Conversion happens only where a reading is actually rendered on screen.
//
//  Temperature, pressure, and speed are each stored as their own independent preference
//  (rather than one combined SAE/Metric toggle) so a user can mix units freely — e.g. keep
//  Fahrenheit and mph but still prefer kPa for pressure.

import Foundation

enum TemperatureUnit: String, CaseIterable, Identifiable {
    case fahrenheit, celsius

    var id: String { rawValue }
    var symbol: String { self == .fahrenheit ? "°F" : "°C" }
}

enum PressureUnit: String, CaseIterable, Identifiable {
    case kPa, inHg

    var id: String { rawValue }
    var symbol: String { self == .kPa ? "kPa" : "inHg" }
}

enum SpeedUnit: String, CaseIterable, Identifiable {
    case mph, kmh

    var id: String { rawValue }
    var symbol: String { self == .mph ? "mph" : "km/h" }
}

enum UnitDisplay {
    private static let mphToKmh = 1.609344
    private static let kPaToInHg = 0.2953

    static func speed(mphValue: Double, unit: SpeedUnit) -> Double {
        unit == .kmh ? mphValue * mphToKmh : mphValue
    }

    /// Converts an already-formatted PIDReading (value string + native unit) for display,
    /// leaving anything it doesn't recognize — RPM, %, V, etc. — untouched.
    static func convert(value rawValue: String, unit: String, temperature: TemperatureUnit, pressure: PressureUnit) -> (value: String, unit: String) {
        guard let number = Double(rawValue) else { return (rawValue, unit) }
        if unit == "°C", temperature == .fahrenheit {
            return (format(number * 9 / 5 + 32), "°F")
        }
        if unit == "kPa", pressure == .inHg {
            return (format(number * kPaToInHg), "inHg")
        }
        return (rawValue, unit)
    }

    /// `value` (in `nativeUnit`, a channel's native units like "°C"/"kPa") converted to the user's
    /// display preference, as a raw `Double` — unlike `convert(value:unit:...)` above, which only
    /// works on already-formatted strings. Needed so a dashboard gauge's range/thresholds (which are
    /// `Double`s, not strings) convert consistently with the value drawn inside them.
    static func convertDouble(_ value: Double, nativeUnit: String, temperature: TemperatureUnit, pressure: PressureUnit) -> Double {
        if nativeUnit == "°C", temperature == .fahrenheit {
            return value * 9 / 5 + 32
        }
        if nativeUnit == "kPa", pressure == .inHg {
            return value * kPaToInHg
        }
        return value
    }

    /// The unit label to show next to a value converted by `convertDouble`.
    static func displayUnit(nativeUnit: String, temperature: TemperatureUnit, pressure: PressureUnit) -> String {
        if nativeUnit == "°C" { return temperature.symbol }
        if nativeUnit == "kPa" { return pressure == .inHg ? "inHg" : "kPa" }
        return nativeUnit
    }

    private static func format(_ value: Double) -> String {
        if value.truncatingRemainder(dividingBy: 1) == 0, abs(value) < 1_000_000 {
            return String(Int(value))
        }
        return String(format: "%.2f", value)
    }
}
