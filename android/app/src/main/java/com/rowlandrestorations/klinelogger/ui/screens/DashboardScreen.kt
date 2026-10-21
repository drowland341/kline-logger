package com.rowlandrestorations.klinelogger.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import com.rowlandrestorations.klinelogger.ConnectionUi
import com.rowlandrestorations.klinelogger.LinkState
import com.rowlandrestorations.klinelogger.RecordingUi
import com.rowlandrestorations.klinelogger.ui.components.LinkIndicator
import com.rowlandrestorations.klinelogger.ui.components.RecordButton
import com.rowlandrestorations.klinelogger.ui.components.linkLabel
import kotlinx.coroutines.delay
import com.rowlandrestorations.klinelogger.Reading
import com.rowlandrestorations.klinelogger.channels.PidDef
import com.rowlandrestorations.klinelogger.settings.DashStyle
import com.rowlandrestorations.klinelogger.settings.LocalUnits
import com.rowlandrestorations.klinelogger.settings.SpeedUnit
import com.rowlandrestorations.klinelogger.settings.convert
import com.rowlandrestorations.klinelogger.settings.label
import com.rowlandrestorations.klinelogger.settings.speedFromMph
import com.rowlandrestorations.klinelogger.ui.components.ArcGauge
import com.rowlandrestorations.klinelogger.ui.components.ClusterData
import com.rowlandrestorations.klinelogger.ui.components.DigitalDashboard
import com.rowlandrestorations.klinelogger.ui.components.LcdDashboard
import com.rowlandrestorations.klinelogger.ui.components.GaugeCard
import com.rowlandrestorations.klinelogger.ui.components.GaugeSpec
import com.rowlandrestorations.klinelogger.ui.components.SportDashboard
import com.rowlandrestorations.klinelogger.ui.components.SportReadout
import com.rowlandrestorations.klinelogger.ui.components.Thresholds
import com.rowlandrestorations.klinelogger.ui.components.formatValue

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

// Extra channels the Sport cluster shows. Oil has no limits yet: its scaling is still unverified.
private val OIL = GaugeSpec(0x98, "OIL", 20.0, 160.0)
private val IAT = GaugeSpec(0x07, "IAT", -10.0, 60.0)

@Composable
fun DashboardScreen(
    channels: List<PidDef>,
    readings: Map<Int, Reading>,
    connection: ConnectionUi,
    banner: @Composable () -> Unit,
    gpsMph: Double?,
    style: DashStyle,
    onStyle: (DashStyle) -> Unit,
    modifier: Modifier = Modifier,
    fullscreen: Boolean = false,
    onFullscreen: (Boolean) -> Unit = {},
    recording: RecordingUi = RecordingUi(),
    onRecord: () -> Unit = {},
    vehicle: VehicleControls? = null,
) {
    val byPid = channels.associateBy { it.pid }
    val prefs = LocalUnits.current
    fun native(spec: GaugeSpec) = byPid[spec.pid]?.units.orEmpty()
    fun value(spec: GaugeSpec) = readings[spec.pid]?.value?.let { prefs.convert(it, native(spec)) }
    fun units(spec: GaugeSpec) = prefs.label(byPid[spec.pid]?.displayUnits.orEmpty(), value(spec))

    // Gauge ranges and limits are written in the channel's native units (°C, kPa): convert them with the value.
    fun shown(spec: GaugeSpec): GaugeSpec {
        val n = native(spec)
        fun c(v: Double?) = v?.let { prefs.convert(it, n) }
        val t = spec.thresholds
        // Pressure follows the channel's gauge_max, so a supercharged ski's boost range fits on the gauge.
        val max = if (spec.pid == MAP.pid) byPid[spec.pid]?.gaugeMax?.takeIf { it > spec.min } ?: spec.max else spec.max
        return spec.copy(
            min = prefs.convert(spec.min, n), max = prefs.convert(max, n),
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

    fun readout(spec: GaugeSpec): SportReadout {
        val s = shown(spec)
        val v = value(spec)
        return SportReadout(
            spec.label, v?.let { formatValue(it, s.decimals, s.step) } ?: "—", units(spec),
            s.thresholds.check(v).level, v?.let { s.fraction(it) }, if (v == null) caption(spec) else null,
        )
    }

    fun cluster(): ClusterData {
        val kmh = prefs.speed == SpeedUnit.KMH
        val speed = gpsMph?.let { prefs.speedFromMph(it) }
        val rpm = value(RPM)
        val iat = value(IAT)
        return ClusterData(
            speedSpec = GaugeSpec(-1, "SPEED", 0.0, if (kmh) 110.0 else 70.0, majorTick = if (kmh) 20.0 else 10.0),
            speed = speed,
            speedText = speed?.let { formatValue(it, 0) } ?: "—",
            speedUnits = prefs.speed.symbol,
            rpmSpec = RPM,
            rpm = rpm,
            rpmText = rpm?.let { formatValue(it, 0, RPM.step).replace(",", "") } ?: "—",
            rpmUnits = units(RPM).ifEmpty { "rpm" }.uppercase(),
            throttle = readout(THROTTLE),
            coolant = readout(COOLANT),
            oil = readout(OIL),
            battery = readout(BATTERY),
            map = readout(MAP),
            footer = "IAT " + (iat?.let { formatValue(it, 0) + units(IAT) } ?: "—"),
        )
    }

    @Composable
    fun Gauges(m: Modifier) {
        when (style) {
            DashStyle.SPORT -> {
                val d = cluster()
                SportDashboard(
                    speedSpec = d.speedSpec,
                    speed = d.speed,
                    speedText = d.speedText,
                    speedUnits = d.speedUnits,
                    rpmSpec = d.rpmSpec,
                    rpm = d.rpm,
                    rpmText = d.rpmText,
                    rpmUnits = d.rpmUnits,
                    ring = d.throttle,
                    side = listOf(d.coolant, d.oil, d.battery, d.map),
                    footer = d.footer,
                    modifier = m,
                    edgeToEdge = fullscreen,
                )
            }
            DashStyle.LCD -> LcdDashboard(cluster(), m, edgeToEdge = fullscreen)
            DashStyle.DIGITAL -> DigitalDashboard(
                cluster(), m, edgeToEdge = fullscreen, vehicle = vehicle?.image, flipVehicle = vehicle?.flip ?: false,
            )
            DashStyle.CLASSIC -> BoxWithConstraints(m) {
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

    if (fullscreen) {
        FullscreenFrame(style, onStyle, connection, recording, onRecord, onExit = { onFullscreen(false) }) { Gauges(it) }
    } else {
        Column(modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            banner()
            Row(verticalAlignment = Alignment.CenterVertically) {
                StylePicker(style, onStyle, Modifier.weight(1f))
                if (style == DashStyle.DIGITAL && vehicle != null) {
                    VehicleMenu(vehicle)
                    Spacer(Modifier.width(8.dp))
                }
                OutlinedButton(onClick = { onFullscreen(true) }) { Text("FULL SCREEN") }
            }
            Gauges(Modifier.fillMaxWidth().weight(1f))
        }
    }
}

/** The rider's ski picture for the Digital dashboard, and what to do with it. */
class VehicleControls(
    val image: ImageBitmap?,
    val flip: Boolean,
    val onPick: () -> Unit,
    val onFlip: () -> Unit,
    val onClear: () -> Unit,
)

@Composable
private fun VehicleMenu(vehicle: VehicleControls) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) { Text("SKI PICTURE") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Choose picture…") },
                onClick = {
                    open = false
                    vehicle.onPick()
                },
            )
            DropdownMenuItem(
                text = { Text(if (vehicle.flip) "Flip back" else "Flip left/right") },
                onClick = {
                    open = false
                    vehicle.onFlip()
                },
            )
            DropdownMenuItem(
                text = { Text("Use built-in drawing") },
                enabled = vehicle.image != null,
                onClick = {
                    open = false
                    vehicle.onClear()
                },
            )
        }
    }
}

@Composable
private fun StylePicker(style: DashStyle, onStyle: (DashStyle) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Gauge style", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        DashStyle.entries.forEach { s ->
            FilterChip(selected = s == style, onClick = { onStyle(s) }, label = { Text(s.label) })
        }
    }
}

/**
 * The gauges alone, filling the screen. A tap shows the style chips and an exit button for a few seconds;
 * a pill at the top says so whenever the ECU link isn't up, since the status bar is hidden. Log All stays
 * at the bottom, always reachable.
 */
@Composable
private fun FullscreenFrame(
    style: DashStyle,
    onStyle: (DashStyle) -> Unit,
    connection: ConnectionUi,
    recording: RecordingUi,
    onRecord: () -> Unit,
    onExit: () -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    var controls by remember { mutableStateOf(false) }
    LaunchedEffect(controls, style) {
        if (controls) {
            delay(4_000)
            controls = false
        }
    }
    val sport = style != DashStyle.CLASSIC      // the cluster styles draw on a black panel
    Box(
        Modifier
            .fillMaxSize()
            .background(if (sport) SportPanel else MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .clickable(remember { MutableInteractionSource() }, indication = null) { controls = !controls },
    ) {
        content(Modifier.fillMaxSize().padding(if (sport) 0.dp else 12.dp))
        if (connection.state != LinkState.CONNECTED) {
            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp)
                    .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(50))
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LinkIndicator(connection.state, size = 10)
                Spacer(Modifier.width(8.dp))
                Text(linkLabel(connection.state), color = Color.White, style = MaterialTheme.typography.labelLarge)
            }
        }
        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)) {
            RecordButton(recording, enabled = connection.state == LinkState.CONNECTED, onClick = onRecord)
        }
        AnimatedVisibility(controls, Modifier.align(Alignment.TopEnd).padding(12.dp), enter = fadeIn(), exit = fadeOut()) {
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f)) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DashStyle.entries.forEach { s ->
                        FilterChip(selected = s == style, onClick = { onStyle(s) }, label = { Text(s.label) })
                    }
                    Button(onClick = onExit) { Text("EXIT FULL SCREEN") }
                }
            }
        }
    }
}

private val SportPanel = Color(0xFF040506)

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
