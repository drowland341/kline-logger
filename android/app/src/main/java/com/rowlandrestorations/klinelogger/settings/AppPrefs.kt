package com.rowlandrestorations.klinelogger.settings

import android.content.Context
import com.rowlandrestorations.klinelogger.elm.BtDevice

/** How the phone reaches the ECU. ELM327 adapters come first: they're what most riders will use on the water. */
enum class AdapterKind(val label: String, val short: String) {
    BLUETOOTH("Bluetooth ELM327", "Bluetooth"),
    BLE("Bluetooth LE ELM327", "BLE"),
    WIFI("Wi-Fi ELM327", "Wi-Fi"),
    USB_CABLE("USB K-line cable", "USB cable"),
}

enum class TemperatureUnit(val symbol: String) { FAHRENHEIT("°F"), CELSIUS("°C") }
enum class PressureUnit(val symbol: String) { KPA("kPa"), INHG("inHg"), PSI("psi") }
enum class SpeedUnit(val symbol: String) { MPH("mph"), KMH("km/h") }

/** Display units, each picked on its own (like the iOS app), e.g. °F and mph but kPa. */
data class UnitPrefs(
    val temperature: TemperatureUnit = TemperatureUnit.FAHRENHEIT,
    val pressure: PressureUnit = PressureUnit.KPA,
    val speed: SpeedUnit = SpeedUnit.MPH,
)

data class AdapterPrefs(
    val kind: AdapterKind = AdapterKind.BLUETOOTH,
    val bluetooth: BtDevice? = null,
    val ble: BtDevice? = null,
    val wifiHost: String = DEFAULT_WIFI_HOST,
    val wifiPort: Int = DEFAULT_WIFI_PORT,
) {
    companion object {
        // What nearly every Wi-Fi ELM327 uses out of the box.
        const val DEFAULT_WIFI_HOST = "192.168.0.10"
        const val DEFAULT_WIFI_PORT = 35000
    }
}

/** Small settings kept in SharedPreferences (the channel list has its own file). */
class AppPrefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun adapter(): AdapterPrefs = AdapterPrefs(
        kind = enumOr(sp.getString("adapter_kind", null), AdapterKind.BLUETOOTH),
        bluetooth = device("bt"),
        ble = device("ble"),
        wifiHost = sp.getString("wifi_host", null) ?: AdapterPrefs.DEFAULT_WIFI_HOST,
        wifiPort = sp.getInt("wifi_port", AdapterPrefs.DEFAULT_WIFI_PORT),
    )

    fun saveAdapter(a: AdapterPrefs) {
        sp.edit()
            .putString("adapter_kind", a.kind.name)
            .putString("bt_address", a.bluetooth?.address).putString("bt_name", a.bluetooth?.name)
            .putString("ble_address", a.ble?.address).putString("ble_name", a.ble?.name)
            .putString("wifi_host", a.wifiHost)
            .putInt("wifi_port", a.wifiPort)
            .apply()
    }

    fun units(): UnitPrefs = UnitPrefs(
        temperature = enumOr(sp.getString("unit_temperature", null), TemperatureUnit.FAHRENHEIT),
        pressure = enumOr(sp.getString("unit_pressure", null), PressureUnit.KPA),
        speed = enumOr(sp.getString("unit_speed", null), SpeedUnit.MPH),
    )

    fun saveUnits(u: UnitPrefs) {
        sp.edit()
            .putString("unit_temperature", u.temperature.name)
            .putString("unit_pressure", u.pressure.name)
            .putString("unit_speed", u.speed.name)
            .apply()
    }

    private fun device(prefix: String): BtDevice? {
        val address = sp.getString("${prefix}_address", null) ?: return null
        return BtDevice(address, sp.getString("${prefix}_name", null) ?: address)
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default
}
