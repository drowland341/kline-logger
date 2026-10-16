package com.rowlandrestorations.klinelogger.ui.components

import android.os.SystemClock
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rowlandrestorations.klinelogger.ConnectionUi
import com.rowlandrestorations.klinelogger.LinkState
import com.rowlandrestorations.klinelogger.RecordingUi
import com.rowlandrestorations.klinelogger.ScanMode
import com.rowlandrestorations.klinelogger.ScanUi
import com.rowlandrestorations.klinelogger.ui.theme.GaugeLabel
import com.rowlandrestorations.klinelogger.ui.theme.GaugeNumerals
import com.rowlandrestorations.klinelogger.ui.theme.GaugeTheme
import kotlinx.coroutines.delay

@Composable
fun linkColor(state: LinkState): Color = when (state) {
    LinkState.CONNECTED -> GaugeTheme.status.ok
    LinkState.CONNECTING -> GaugeTheme.status.warning
    LinkState.DISCONNECTED -> GaugeTheme.status.critical
}

fun linkLabel(state: LinkState): String = when (state) {
    LinkState.CONNECTED -> "Connected"
    LinkState.CONNECTING -> "Connecting"
    LinkState.DISCONNECTED -> "Disconnected"
}

/** Status dot; pulses while connecting. */
@Composable
fun LinkIndicator(state: LinkState, modifier: Modifier = Modifier, size: Int = 12) {
    val color by animateColorAsState(linkColor(state), label = "linkColor")
    val pulse = rememberInfiniteTransition(label = "pulse")
    val a by pulse.animateFloat(1f, 0.25f, infiniteRepeatable(tween(600, easing = LinearEasing), RepeatMode.Reverse), label = "pulseAlpha")
    Box(
        modifier
            .size(size.dp)
            .alpha(if (state == LinkState.CONNECTING) a else 1f)
            .background(color, CircleShape),
    )
}

/** Slim bar shown above every screen: link state on the left, CSV Record button on the right. */
@Composable
fun StatusBar(connection: ConnectionUi, scan: ScanUi, recording: RecordingUi, onRecord: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier.background(scheme.surfaceContainerLowest)) {
        Row(
            Modifier
                .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(start = 16.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LinkIndicator(connection.state)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(linkLabel(connection.state).uppercase(), style = GaugeLabel, color = scheme.onSurface, maxLines = 1)
                val detail = when (connection.state) {
                    LinkState.CONNECTED -> buildString {
                        if (connection.demoSession) append("DEMO · ") else connection.sessionKind?.let { append(it.short.uppercase() + " · ") }
                        connection.ecu?.let { append("ECU 0x%02X".format(it.address)) }
                        when (scan.mode) {
                            ScanMode.SCANNING -> append(" · SCANNING ${scan.done}/${scan.total}")
                            ScanMode.WATCHING -> append(" · WATCHING ${scan.watching.size} PIDs")
                            ScanMode.IDLE -> if (connection.updateHz > 0) append(" · %.1f Hz".format(connection.updateHz))
                        }
                    }
                    LinkState.CONNECTING -> if (connection.demoSession) "Demo ECU" else connection.sessionKind?.label ?: "K-line"
                    LinkState.DISCONNECTED -> if (connection.demoMode) "Demo mode on" else "No ECU"
                }
                Text(detail, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            RecordButton(recording, enabled = connection.state == LinkState.CONNECTED, onClick = onRecord)
        }
        HorizontalDivider(color = scheme.outlineVariant)
    }
}

@Composable
private fun RecordButton(recording: RecordingUi, enabled: Boolean, onClick: () -> Unit) {
    val status = GaugeTheme.status
    val shape = RoundedCornerShape(12.dp)
    val padding = PaddingValues(horizontal = 14.dp)
    if (recording.active) {
        var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
        LaunchedEffect(recording.startedAt) {
            while (true) {
                now = SystemClock.elapsedRealtime()
                delay(250)
            }
        }
        val secs = ((now - recording.startedAt) / 1000).coerceAtLeast(0)
        Button(
            onClick = onClick,
            shape = shape,
            contentPadding = padding,
            modifier = Modifier.height(44.dp),
            colors = ButtonDefaults.buttonColors(containerColor = status.critical, contentColor = Color.White),
        ) {
            Box(Modifier.size(10.dp).background(Color.White, RoundedCornerShape(2.dp)))
            Spacer(Modifier.width(8.dp))
            Text("%d:%02d".format(secs / 60, secs % 60), style = GaugeNumerals.copy(fontSize = MaterialTheme.typography.labelLarge.fontSize))
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            shape = shape,
            contentPadding = padding,
            modifier = Modifier.height(44.dp),
            border = BorderStroke(1.5.dp, if (enabled) status.critical else MaterialTheme.colorScheme.outline),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
        ) {
            Box(Modifier.size(10.dp).background(if (enabled) status.critical else status.faint, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text("REC", style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Label/value pair used in info cards. */
@Composable
fun InfoRow(label: String, value: String, mono: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Text(
            value,
            style = if (mono) MaterialTheme.typography.bodyMedium.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            else MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * Explains why the gauges aren't moving: not connected, or the scanner has the bus.
 * Draws nothing while live data is flowing.
 */
@Composable
fun LiveDataBanner(connection: ConnectionUi, scan: ScanUi, onOpenConnection: () -> Unit, onOpenScan: () -> Unit) {
    val (text, action, onClick) = when {
        connection.state == LinkState.CONNECTING -> Triple("Connecting to the ECU…", "CONNECTION", onOpenConnection)
        connection.state == LinkState.DISCONNECTED -> Triple("No live data. Connect to the ECU or start Demo mode.", "CONNECTION", onOpenConnection)
        scan.mode == ScanMode.SCANNING -> Triple("Live data paused while the scanner runs.", "SCANNER", onOpenScan)
        scan.mode == ScanMode.WATCHING -> Triple("Live data paused while watching scanned PIDs.", "SCANNER", onOpenScan)
        else -> return
    }
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (connection.state == LinkState.CONNECTED) {
                Box(Modifier.size(10.dp).background(GaugeTheme.status.warning, CircleShape))
            } else {
                LinkIndicator(connection.state, size = 10)
            }
            Spacer(Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            TextButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp)) { Text(action) }
        }
    }
}
