package com.rowlandrestorations.klinelogger.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rowlandrestorations.klinelogger.ConnectionUi
import com.rowlandrestorations.klinelogger.LinkState
import com.rowlandrestorations.klinelogger.RawLine
import com.rowlandrestorations.klinelogger.RecordingUi
import com.rowlandrestorations.klinelogger.kline.Direction
import com.rowlandrestorations.klinelogger.ui.components.InfoRow
import com.rowlandrestorations.klinelogger.ui.components.LinkIndicator
import com.rowlandrestorations.klinelogger.ui.components.linkColor
import com.rowlandrestorations.klinelogger.ui.components.linkLabel
import com.rowlandrestorations.klinelogger.ui.theme.GaugeLabel
import com.rowlandrestorations.klinelogger.ui.theme.GaugeTheme

@Composable
fun ConnectionScreen(
    connection: ConnectionUi,
    recording: RecordingUi,
    rawLog: List<RawLine>,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onDemoMode: (Boolean) -> Unit,
    onClearLog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.widthIn(max = 720.dp).align(Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            StatusCard(connection)
            ConnectButton(connection, onConnect, onDisconnect)
            DemoToggle(connection, onDemoMode)
            connection.ecu?.let { EcuCard(connection) }
            RecordingCard(recording)
            RawLogPanel(rawLog, onClearLog)
            Text(
                "Rowland Restorations K-Line Logger",
                style = MaterialTheme.typography.labelMedium,
                color = GaugeTheme.status.faint,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun SectionCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        content = content,
    )
}

@Composable
private fun StatusCard(connection: ConnectionUi) {
    val color = linkColor(connection.state)
    SectionCard(Modifier, content = {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            LinkIndicator(connection.state, size = 20)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(linkLabel(connection.state), style = MaterialTheme.typography.headlineSmall, color = color)
                Spacer(Modifier.height(4.dp))
                Text(
                    connection.status,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (connection.ok == false) GaugeTheme.status.critical else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    })
}

@Composable
private fun ConnectButton(connection: ConnectionUi, onConnect: () -> Unit, onDisconnect: () -> Unit) {
    val status = GaugeTheme.status
    val modifier = Modifier.fillMaxWidth().height(60.dp)
    val shape = RoundedCornerShape(16.dp)
    when (connection.state) {
        LinkState.DISCONNECTED -> Button(onClick = onConnect, modifier = modifier, shape = shape) {
            Text(if (connection.demoMode) "CONNECT (DEMO)" else "CONNECT", style = MaterialTheme.typography.labelLarge.copy(fontSize = 17.sp))
        }
        LinkState.CONNECTING, LinkState.CONNECTED -> Button(
            onClick = onDisconnect,
            modifier = modifier,
            shape = shape,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest, contentColor = MaterialTheme.colorScheme.onSurface),
            border = BorderStroke(1.5.dp, status.critical),
        ) {
            if (connection.state == LinkState.CONNECTING) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.5.dp, color = status.warning)
                Spacer(Modifier.width(12.dp))
                Text("CANCEL", style = MaterialTheme.typography.labelLarge.copy(fontSize = 17.sp))
            } else {
                Text("DISCONNECT", style = MaterialTheme.typography.labelLarge.copy(fontSize = 17.sp))
            }
        }
    }
}

@Composable
private fun DemoToggle(connection: ConnectionUi, onDemoMode: (Boolean) -> Unit) {
    val enabled = connection.state == LinkState.DISCONNECTED
    SectionCard(content = {
        Row(
            Modifier
                .clickable(enabled = enabled, role = Role.Switch) { onDemoMode(!connection.demoMode) }
                .heightIn(min = 72.dp)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Demo mode", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (enabled) "Simulated ECU on the same K-line pipeline. No cable needed."
                    else "Disconnect to switch between demo and the real ECU.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = connection.demoMode,
                onCheckedChange = null,
                enabled = enabled,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                    disabledCheckedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
                    disabledCheckedThumbColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
        }
    })
}

@Composable
private fun EcuCard(connection: ConnectionUi) {
    val ecu = connection.ecu ?: return
    SectionCard(content = {
        Column(Modifier.padding(20.dp)) {
            Text("ECU RESPONSE", style = GaugeLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            InfoRow("Address", "0x%02X".format(ecu.address), mono = true)
            InfoRow("Key bytes", ecu.keyBytes, mono = true)
            InfoRow("Echo", ecu.echo)
            InfoRow("Diagnostic session", ecu.diag)
            InfoRow("Baud", "%,d".format(ecu.baud))
            if (connection.updateHz > 0) InfoRow("Update rate", "%.1f Hz".format(connection.updateHz))
            if (connection.demoSession) InfoRow("Source", "Demo ECU (simulated)")
            Spacer(Modifier.height(8.dp))
            Text("StartCommunication reply", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SelectionContainer {
                Text(ecu.response, style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.primary)
            }
        }
    })
}

@Composable
private fun RecordingCard(recording: RecordingUi) {
    if (recording.fileName == null) return
    SectionCard(content = {
        Column(Modifier.padding(20.dp)) {
            Text("CSV LOG", style = GaugeLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Text(
                if (recording.active) "Recording to ${recording.fileName}"
                else "Saved ${recording.savedRows ?: 0} rows to ${recording.fileName}",
                style = MaterialTheme.typography.bodyMedium,
                color = if (recording.active) GaugeTheme.status.critical else GaugeTheme.status.ok,
            )
            recording.folder?.let {
                Text(it, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = GaugeTheme.status.faint)
            }
        }
    })
}

@Composable
private fun RawLogPanel(rawLog: List<RawLine>, onClear: () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val chevron by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
    val status = GaugeTheme.status
    SectionCard(content = {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .heightIn(min = 60.dp)
                    .padding(start = 20.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Raw K-line log", style = MaterialTheme.typography.titleMedium)
                    Text("${rawLog.size} frames", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (expanded) {
                    IconButton(onClick = onClear, enabled = rawLog.isNotEmpty()) {
                        Icon(Icons.Filled.Delete, contentDescription = "Clear log")
                    }
                }
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    modifier = Modifier.padding(12.dp).rotate(chevron),
                )
            }
            AnimatedVisibility(expanded) {
                Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    if (rawLog.isEmpty()) {
                        Text(
                            "No traffic yet. Frames appear here once you connect.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(20.dp),
                        )
                    } else {
                        val listState = rememberLazyListState()
                        LaunchedEffect(rawLog.size) { listState.scrollToItem(rawLog.lastIndex) }
                        val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp)
                        SelectionContainer {
                            LazyColumn(
                                Modifier.fillMaxWidth().height(320.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                                state = listState,
                            ) {
                                itemsIndexed(rawLog) { _, line ->
                                    Row {
                                        Text("%8.3f".format(line.seconds), style = mono, color = status.faint)
                                        Spacer(Modifier.width(10.dp))
                                        Text(
                                            line.dir.name,
                                            style = mono,
                                            color = if (line.dir == Direction.TX) MaterialTheme.colorScheme.primary else status.ok,
                                        )
                                        Spacer(Modifier.width(10.dp))
                                        Text(line.hex, style = mono, color = MaterialTheme.colorScheme.onSurface)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    })
}
