package com.rowlandrestorations.klinelogger.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rowlandrestorations.klinelogger.ConnectionUi
import com.rowlandrestorations.klinelogger.LinkState
import com.rowlandrestorations.klinelogger.MainViewModel
import com.rowlandrestorations.klinelogger.Reading
import com.rowlandrestorations.klinelogger.channels.PidDef
import com.rowlandrestorations.klinelogger.kline.hexs
import com.rowlandrestorations.klinelogger.location.GpsSpeed
import com.rowlandrestorations.klinelogger.settings.LocalUnits
import com.rowlandrestorations.klinelogger.settings.convert
import com.rowlandrestorations.klinelogger.settings.label
import com.rowlandrestorations.klinelogger.settings.speedFromMph
import com.rowlandrestorations.klinelogger.ui.LOCATION_PERMISSIONS
import com.rowlandrestorations.klinelogger.ui.rememberPermissionRequest
import com.rowlandrestorations.klinelogger.ui.components.Sparkline
import com.rowlandrestorations.klinelogger.ui.components.autoDecimals
import com.rowlandrestorations.klinelogger.ui.components.formatValue
import com.rowlandrestorations.klinelogger.ui.theme.GaugeLabel
import com.rowlandrestorations.klinelogger.ui.theme.GaugeNumerals
import com.rowlandrestorations.klinelogger.ui.theme.GaugeTheme

@Composable
fun LiveDataScreen(
    channels: List<PidDef>,
    readings: Map<Int, Reading>,
    connection: ConnectionUi,
    banner: @Composable () -> Unit,
    gpsMph: Double?,
    gpsStatus: GpsSpeed.Status,
    onEnableGps: () -> Unit,
    onEdit: (PidDef) -> Unit,
    onAdd: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmReset by rememberSaveable { mutableStateOf(false) }
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { banner() }
        item {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${channels.size} CHANNELS", style = GaugeLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (connection.state == LinkState.CONNECTED) {
                        Text("%.1f updates/s".format(connection.updateHz), style = MaterialTheme.typography.labelMedium, color = GaugeTheme.status.faint)
                    }
                }
                TextButton(onClick = { confirmReset = true }) { Text("DEFAULTS") }
                TextButton(onClick = onAdd) { Text("+ ADD") }
            }
        }
        item { GpsCard(gpsMph, gpsStatus, onEnableGps) }
        items(channels, key = { it.pid }) { ch -> ChannelRow(ch, readings[ch.pid], onClick = { onEdit(ch) }) }
        item {
            Text(
                "Tap a channel to rename it or change its scaling. Use the Scan tab to find PIDs that aren't listed here.",
                style = MaterialTheme.typography.bodySmall,
                color = GaugeTheme.status.faint,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            )
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Restore default channels?") },
            text = { Text("This replaces your channel list with the built-in pids.json. Channels you added or edited will be lost.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    onReset()
                }) { Text("RESTORE") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("CANCEL") } },
        )
    }
}

@Composable
private fun ChannelRow(ch: PidDef, reading: Reading?, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val status = GaugeTheme.status
    val units = LocalUnits.current
    val value = reading?.value?.let { units.convert(it, ch.units) }
    val history = reading?.history.orEmpty().map { units.convert(it.toDouble(), ch.units).toFloat() }
    val shownUnits = units.label(ch.displayUnits)
    Surface(shape = RoundedCornerShape(16.dp), color = scheme.surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(ch.name, style = MaterialTheme.typography.titleMedium, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Tag("0x%02X".format(ch.pid))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            buildList {
                                add(if (ch.every == 1) "every update" else "every ${ch.every}")
                                if (!ch.verified) add("unverified")
                                if (!ch.enabled) add("off in desktop app")
                            }.joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = status.faint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        if (value == null) "—" else formatValue(value, autoDecimals(value)),
                        style = GaugeNumerals.copy(fontSize = 32.sp, lineHeight = 34.sp),
                        color = scheme.onSurface,
                        textAlign = TextAlign.End,
                        modifier = Modifier.alignByBaseline(),
                    )
                    if (shownUnits.isNotEmpty()) {
                        Spacer(Modifier.width(4.dp))
                        Text(shownUnits, style = MaterialTheme.typography.titleSmall, color = scheme.onSurfaceVariant, modifier = Modifier.alignByBaseline())
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            if (reading?.error != null) {
                Text(reading.error, style = MaterialTheme.typography.labelMedium, color = status.warning)
            } else {
                Sparkline(history, scheme.primary, Modifier.fillMaxWidth().height(36.dp), capacity = MainViewModel.HISTORY)
            }
            // Raw bytes and the recent range, for spotting swings and checking a scaling.
            val small = MaterialTheme.typography.labelSmall
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    reading?.raw?.let { hexs(it) } ?: "",
                    style = small.copy(fontFamily = FontFamily.Monospace),
                    color = status.faint,
                    modifier = Modifier.weight(1f),
                )
                if (history.size > 1) {
                    val lo = history.min().toDouble()
                    val hi = history.max().toDouble()
                    Text(
                        "min ${formatValue(lo, autoDecimals(hi))} · max ${formatValue(hi, autoDecimals(hi))}",
                        style = small.copy(fontFeatureSettings = "tnum"),
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun Tag(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/** Phone GPS speed: the reference for spotting a speed PID, since the ECU's candidates stay flat. */
@Composable
private fun GpsCard(mph: Double?, status: GpsSpeed.Status, onEnable: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val units = LocalUnits.current
    val request = rememberPermissionRequest { granted -> if (granted) onEnable() }
    Surface(shape = RoundedCornerShape(16.dp), color = scheme.surfaceContainerHigh) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("GPS Speed", style = MaterialTheme.typography.titleMedium, color = scheme.onSurface)
                Text(
                    when (status) {
                        GpsSpeed.Status.FIX -> "phone GPS · logged in research logs"
                        GpsSpeed.Status.WAITING -> "waiting for a GPS fix…"
                        GpsSpeed.Status.GPS_OFF -> "turn on Location in Android settings"
                        GpsSpeed.Status.NO_PERMISSION, GpsSpeed.Status.OFF -> "needs location permission"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = GaugeTheme.status.faint,
                )
            }
            if (status == GpsSpeed.Status.NO_PERMISSION || status == GpsSpeed.Status.OFF) {
                OutlinedButton(onClick = { request(LOCATION_PERMISSIONS) }) { Text("ENABLE") }
            } else {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        mph?.let { formatValue(units.speedFromMph(it), 1) } ?: "—",
                        style = GaugeNumerals.copy(fontSize = 32.sp, lineHeight = 34.sp),
                        color = scheme.onSurface,
                        modifier = Modifier.alignByBaseline(),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(units.speed.symbol, style = MaterialTheme.typography.titleSmall, color = scheme.onSurfaceVariant, modifier = Modifier.alignByBaseline())
                }
            }
        }
    }
}
