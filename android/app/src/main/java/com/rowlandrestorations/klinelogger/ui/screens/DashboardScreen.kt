package com.rowlandrestorations.klinelogger.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rowlandrestorations.klinelogger.ConnectionUi
import com.rowlandrestorations.klinelogger.Reading
import com.rowlandrestorations.klinelogger.channels.PidDef
import com.rowlandrestorations.klinelogger.settings.LocalUnits
import com.rowlandrestorations.klinelogger.settings.convert
import com.rowlandrestorations.klinelogger.settings.label
import com.rowlandrestorations.klinelogger.ui.components.ArcGauge
import com.rowlandrestorations.klinelogger.ui.components.GaugeCard
import com.rowlandrestorations.klinelogger.ui.components.GaugeSpec
import com.rowlandrestorations.klinelogger.ui.components.Thresholds

// Dashboard gauges, keyed by the PIDs in pids.json. Limits are typical for a 4-stroke PWC;
// adjust them here for a particular ski.
private val RPM = GaugeSpec(
    pid = 0x09, label = "RPM", min = 0.0, max = 8000.0, step = 10.0,
    thresholds = Thresholds(warnHigh = 7000.0, critHigh = 7600.0),
    majorTick = 1000.0, tickDivisor = 1000.0,
)
private val COOLANT = GaugeSpec(0x06, "COOLANT", 20.0, 120.0, thresholds = Thresholds(warnHigh = 95.0, critHigh = 105.0))
private val BATTERY = GaugeSpec(
    0x0A, "BATTERY", 10.0, 16.0, decimals = 1,
    thresholds = Thresholds(warnLow = 12.2, critLow = 11.5, warnHigh = 15.0, critHigh = 15.5),
)
private val THROTTLE = GaugeSpec(0x04, "THROTTLE", 0.0, 100.0)
private val MAP = GaugeSpec(0x05, "MAP", 0.0, 110.0)
private val CARDS = listOf(COOLANT, BATTERY, THROTTLE, MAP)

@Composable
fun DashboardScreen(
    channels: List<PidDef>,
    readings: Map<Int, Reading>,
    connection: ConnectionUi,
    banner: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val byPid = channels.associateBy { it.pid }
    val prefs = LocalUnits.current
    fun native(spec: GaugeSpec) = byPid[spec.pid]?.units.orEmpty()
    fun units(spec: GaugeSpec) = prefs.label(byPid[spec.pid]?.displayUnits.orEmpty())
    fun value(spec: GaugeSpec) = readings[spec.pid]?.value?.let { prefs.convert(it, native(spec)) }

    // Gauge ranges and limits are written in the channel's native units (°C, kPa): convert them with the value.
    fun shown(spec: GaugeSpec): GaugeSpec {
        val n = native(spec)
        fun c(v: Double?) = v?.let { prefs.convert(it, n) }
        val t = spec.thresholds
        return spec.copy(
            min = prefs.convert(spec.min, n), max = prefs.convert(spec.max, n),
            thresholds = Thresholds(c(t.warnHigh), c(t.critHigh), c(t.warnLow), c(t.critLow)),
        )
    }
    fun caption(spec: GaugeSpec): String? {
        val ch = byPid[spec.pid] ?: return "PID 0x%02X not in pids.json".format(spec.pid)
        readings[spec.pid]?.error?.let { return it }
        return if (!ch.enabled) "Unconfirmed PID 0x%02X".format(ch.pid) else null
    }

    @Composable
    fun Card(spec: GaugeSpec, m: Modifier) = GaugeCard(shown(spec), value(spec), units(spec), m, caption(spec))

    Column(modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        banner()

        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val gap = 12.dp
            if (maxWidth > maxHeight) {
                // Landscape (handlebar mount): tach on the left, 2x2 cards on the right.
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    TachPanel(value(RPM), units(RPM), Modifier.weight(1f).fillMaxHeight())
                    CardGrid(Modifier.weight(1.15f).fillMaxHeight(), gap) { spec, m -> Card(spec, m) }
                }
            } else {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(gap)) {
                    TachPanel(value(RPM), units(RPM), Modifier.fillMaxWidth().weight(1.1f))
                    CardGrid(Modifier.fillMaxWidth().weight(1f), gap) { spec, m -> Card(spec, m) }
                }
            }
        }
    }
}

@Composable
private fun TachPanel(value: Double?, units: String, modifier: Modifier) {
    Surface(modifier, shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        ArcGauge(RPM, value, units.ifEmpty { "rpm" }, Modifier.fillMaxSize().padding(12.dp))
    }
}

@Composable
private fun CardGrid(modifier: Modifier, gap: androidx.compose.ui.unit.Dp, card: @Composable (GaugeSpec, Modifier) -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(gap)) {
        CARDS.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(gap)) {
                row.forEach { spec -> card(spec, Modifier.weight(1f).fillMaxHeight()) }
            }
        }
    }
}
