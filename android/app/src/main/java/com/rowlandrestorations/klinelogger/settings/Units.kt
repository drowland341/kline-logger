package com.rowlandrestorations.klinelogger.settings

import androidx.compose.runtime.staticCompositionLocalOf

/*
 * Display-only unit conversion, like the iOS app's UnitSystem. pids.json formulas, CSV logs and research
 * logs stay in their native units (°C, kPa, mph) so logs compare across sessions whatever this is set to.
 * Conversion happens only where a reading is drawn on screen.
 */

/** The units preference, provided at the top of the UI. */
val LocalUnits = staticCompositionLocalOf { UnitPrefs() }

private const val KPA_TO_INHG = 0.2953
private const val KPA_TO_PSI = 0.145038
private const val MPH_TO_KMH = 1.609344

private fun isCelsius(units: String) = units.trim().lowercase() in setOf("°c", "c", "degc")
private fun isKpa(units: String) = units.trim().lowercase() == "kpa"

/** [value] in [units] (a channel's native units) converted to what the user picked. Others pass through. */
fun UnitPrefs.convert(value: Double, units: String): Double = when {
    isCelsius(units) && temperature == TemperatureUnit.FAHRENHEIT -> value * 9 / 5 + 32
    isKpa(units) && pressure == PressureUnit.INHG -> value * KPA_TO_INHG
    isKpa(units) && pressure == PressureUnit.PSI -> value * KPA_TO_PSI
    else -> value
}

/** Units label to show next to a converted value. */
fun UnitPrefs.label(units: String): String = when {
    isCelsius(units) -> temperature.symbol
    isKpa(units) -> pressure.symbol
    else -> units
}

/** GPS speed (always measured in mph, like the iOS app's logs) in the user's speed unit. */
fun UnitPrefs.speedFromMph(mph: Double): Double = if (speed == SpeedUnit.KMH) mph * MPH_TO_KMH else mph
