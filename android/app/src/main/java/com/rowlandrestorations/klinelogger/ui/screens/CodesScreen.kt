package com.rowlandrestorations.klinelogger.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rowlandrestorations.klinelogger.CodeExchange
import com.rowlandrestorations.klinelogger.CodesUi
import com.rowlandrestorations.klinelogger.ConnectionUi
import com.rowlandrestorations.klinelogger.LinkState
import com.rowlandrestorations.klinelogger.codes.StoredCode
import com.rowlandrestorations.klinelogger.kline.Direction
import com.rowlandrestorations.klinelogger.ui.theme.GaugeLabel
import com.rowlandrestorations.klinelogger.ui.theme.GaugeNumerals
import com.rowlandrestorations.klinelogger.ui.theme.GaugeTheme

@Composable
fun CodesScreen(
    codes: CodesUi,
    connection: ConnectionUi,
    onRead: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    val list = codes.codes

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            CodesControls(codes, connection, onRead, onClear = { confirmClear = true })
        }
        when {
            list == null -> {}
            list.isEmpty() -> item { NoCodesCard() }
            else -> itemsIndexed(list) { _, code -> CodeCard(code, connection.demoSession) }
        }
        item { DebugPanel(codes.exchanges) }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear stored codes?") },
            text = {
                Text(
                    "This erases every stored service code from the ECU. Write them down first: once cleared, " +
                        "the history is gone. Codes for faults that are still present will come back.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    onClear()
                }) { Text("CLEAR CODES", color = GaugeTheme.status.critical) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("CANCEL") } },
        )
    }
}

@Composable
private fun CodesCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier.fillMaxWidth().widthIn(max = 720.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        content = content,
    )
}

@Composable
private fun CodesControls(codes: CodesUi, connection: ConnectionUi, onRead: () -> Unit, onClear: () -> Unit) {
    val status = GaugeTheme.status
    val connected = connection.state == LinkState.CONNECTED
    val enabled = connected && !codes.busy
    val shape = RoundedCornerShape(16.dp)
    CodesCard {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("SERVICE CODES", style = GaugeLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (codes.busy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = status.warning)
                    Spacer(Modifier.width(10.dp))
                }
                Text(
                    if (!connected && !codes.busy) "Connect to the ECU (or Demo mode) to read codes." else codes.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = when (codes.ok) {
                        false -> status.critical
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onRead, enabled = enabled, modifier = Modifier.weight(1f).height(56.dp), shape = shape) {
                    Text("READ CODES", style = MaterialTheme.typography.labelLarge.copy(fontSize = 16.sp))
                }
                OutlinedButton(
                    onClick = onClear,
                    enabled = enabled,
                    modifier = Modifier.weight(1f).height(56.dp),
                    shape = shape,
                    border = BorderStroke(1.5.dp, if (enabled) status.critical else status.faint),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                ) {
                    Text("CLEAR CODES", style = MaterialTheme.typography.labelLarge.copy(fontSize = 16.sp))
                }
            }
        }
    }
}

@Composable
private fun NoCodesCard() {
    CodesCard {
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("No codes stored", style = MaterialTheme.typography.headlineSmall, color = GaugeTheme.status.ok)
            Spacer(Modifier.height(4.dp))
            Text(
                "The ECU reported no service codes.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CodeCard(code: StoredCode, demo: Boolean) {
    val status = GaugeTheme.status
    val info = code.info
    val scheme = MaterialTheme.colorScheme
    CodesCard {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    code.code?.toString() ?: "??",
                    style = GaugeNumerals.copy(fontSize = 40.sp),
                    color = if (info != null) status.critical else status.warning,
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    if (info != null) {
                        Text(
                            info.meter,
                            style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace, letterSpacing = 2.sp),
                            color = scheme.primary,
                        )
                        Text(info.title, style = MaterialTheme.typography.titleLarge, color = scheme.onSurface)
                    } else {
                        val label = code.code?.toString() ?: code.rawHex.take(5).replace(" ", "")
                        Text("Unknown code $label", style = MaterialTheme.typography.titleLarge, color = status.warning)
                        Text(
                            "Not in the Ultra LX service code table.",
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Raw ${code.rawHex}" + (code.status?.let { "  ·  status 0x%02X".format(it) } ?: "") + if (demo) "  ·  demo" else "",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = status.faint,
            )
            if (info == null) return@Column

            if (info.problem.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(info.problem, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            }
            Section("POSSIBLE CAUSES") {
                info.causes.forEach { cause ->
                    Row(Modifier.padding(vertical = 2.dp)) {
                        Text("•", style = MaterialTheme.typography.bodyMedium, color = scheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text(cause, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface)
                    }
                }
            }
            Section("FAIL-SAFE") {
                Text(info.failSafe.ifBlank { "None listed." }, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface)
            }
            Section("ECU PINS TO CHECK") {
                Text(
                    info.ecuPins.takeIf { it.isNotEmpty() }?.joinToString("   ") { "#$it" } ?: "None listed.",
                    style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                    color = scheme.primary,
                )
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Spacer(Modifier.height(14.dp))
    Text(title, style = GaugeLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(4.dp))
    content()
}

@Composable
private fun DebugPanel(exchanges: List<CodeExchange>) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val chevron by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
    val status = GaugeTheme.status
    val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp)
    CodesCard {
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
                    Text("Protocol debug", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Raw request and response hex for the last ${exchanges.size} code request(s)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
                    if (exchanges.isEmpty()) {
                        Text(
                            "Nothing sent yet. Press Read Codes and the frames show up here.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(20.dp),
                        )
                    }
                    SelectionContainer {
                        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            exchanges.forEach { ex ->
                                Column {
                                    Text(ex.label.uppercase(), style = GaugeLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("Request   ${ex.request}", style = mono, color = MaterialTheme.colorScheme.primary)
                                    Text("Response  ${ex.response.ifEmpty { "(none)" }}", style = mono, color = status.ok)
                                    Text("Result    ${ex.result}", style = mono, color = MaterialTheme.colorScheme.onSurface)
                                    ex.frames.forEach { line ->
                                        Text(
                                            "%-3s %s".format(line.dir.name, line.hex),
                                            style = mono,
                                            color = if (line.dir == Direction.TX) MaterialTheme.colorScheme.primary.copy(alpha = 0.75f) else status.faint,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
