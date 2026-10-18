package com.rowlandrestorations.klinelogger.ui.screens

import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rowlandrestorations.klinelogger.ConnectionUi
import com.rowlandrestorations.klinelogger.DevicesUi
import com.rowlandrestorations.klinelogger.LinkState
import com.rowlandrestorations.klinelogger.RawLine
import com.rowlandrestorations.klinelogger.RecordingUi
import com.rowlandrestorations.klinelogger.elm.BtDevice
import com.rowlandrestorations.klinelogger.kline.Direction
import com.rowlandrestorations.klinelogger.logging.LogFile
import com.rowlandrestorations.klinelogger.settings.AdapterKind
import com.rowlandrestorations.klinelogger.settings.AdapterPrefs
import com.rowlandrestorations.klinelogger.settings.PressureUnit
import com.rowlandrestorations.klinelogger.settings.SpeedUnit
import com.rowlandrestorations.klinelogger.settings.TemperatureUnit
import com.rowlandrestorations.klinelogger.settings.UnitPrefs
import com.rowlandrestorations.klinelogger.ui.bluetoothPermissions
import com.rowlandrestorations.klinelogger.ui.components.InfoRow
import com.rowlandrestorations.klinelogger.ui.components.LinkIndicator
import com.rowlandrestorations.klinelogger.ui.components.linkColor
import com.rowlandrestorations.klinelogger.ui.components.linkLabel
import com.rowlandrestorations.klinelogger.ui.hasPermissions
import com.rowlandrestorations.klinelogger.ui.openSettings
import com.rowlandrestorations.klinelogger.ui.rememberPermissionRequest
import com.rowlandrestorations.klinelogger.ui.shareLog
import com.rowlandrestorations.klinelogger.ui.theme.GaugeLabel
import com.rowlandrestorations.klinelogger.ui.theme.GaugeTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ConnectionScreen(
    connection: ConnectionUi,
    devices: DevicesUi,
    units: UnitPrefs,
    recording: RecordingUi,
    logs: List<LogFile>,
    logFolder: String,
    writingLogs: Set<String>,      // keys of logs being written now: no Delete
    rawLog: List<RawLine>,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onDemoMode: (Boolean) -> Unit,
    onAdapter: (AdapterPrefs) -> Unit,
    onRefreshPaired: () -> Unit,
    onBleScan: () -> Unit,
    onBleStop: () -> Unit,
    onUnits: (UnitPrefs) -> Unit,
    onClearLog: () -> Unit,
    onRefreshLogs: () -> Unit,
    onDeleteLog: (LogFile) -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) { onRefreshLogs() }      // pick up files added or removed in the Files app
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
            if (!connection.demoMode) AdapterCard(connection, devices, onAdapter, onRefreshPaired, onBleScan, onBleStop)
            DemoToggle(connection, onDemoMode)
            connection.ecu?.let { EcuCard(connection) }
            RecordingCard(recording)
            LogsCard(logs, logFolder, writingLogs, onDeleteLog)
            UnitsCard(units, onUnits)
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
    val context = LocalContext.current
    val kind = connection.adapter.kind
    // Bluetooth needs Nearby devices permission at connect time on Android 12+.
    val needed = if (connection.demoMode) emptyList() else when (kind) {
        AdapterKind.BLUETOOTH -> bluetoothPermissions(scan = false)
        AdapterKind.BLE -> bluetoothPermissions(scan = true)
        else -> emptyList()
    }
    val request = rememberPermissionRequest { granted -> if (granted) onConnect() }
    when (connection.state) {
        LinkState.DISCONNECTED -> Button(
            onClick = { if (hasPermissions(context, needed)) onConnect() else request(needed) },
            modifier = modifier,
            shape = shape,
        ) {
            Text(
                if (connection.demoMode) "CONNECT (DEMO)" else "CONNECT · ${kind.short.uppercase()}",
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 17.sp),
            )
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

/** Which adapter to use, and which device or address. ELM327 adapters first: they're the on-the-water option. */
@Composable
private fun AdapterCard(
    connection: ConnectionUi,
    devices: DevicesUi,
    onAdapter: (AdapterPrefs) -> Unit,
    onRefreshPaired: () -> Unit,
    onBleScan: () -> Unit,
    onBleStop: () -> Unit,
) {
    val a = connection.adapter
    val enabled = connection.state == LinkState.DISCONNECTED
    val context = LocalContext.current
    val chipColors = FilterChipDefaults.filterChipColors(
        selectedContainerColor = MaterialTheme.colorScheme.primary,
        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
    )
    SectionCard(content = {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("ADAPTER", style = GaugeLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AdapterKind.entries.forEach { k ->
                    FilterChip(a.kind == k, { onAdapter(a.copy(kind = k)) }, { Text(k.short) }, enabled = enabled, colors = chipColors)
                }
            }
            val hint = MaterialTheme.typography.bodySmall
            val hintColor = MaterialTheme.colorScheme.onSurfaceVariant
            when (a.kind) {
                AdapterKind.BLUETOOTH -> {
                    val perms = bluetoothPermissions(scan = false)
                    var granted by remember { mutableStateOf(hasPermissions(context, perms)) }
                    val request = rememberPermissionRequest { ok ->
                        granted = ok
                        if (ok) onRefreshPaired()
                    }
                    LaunchedEffect(granted) { if (granted) onRefreshPaired() }
                    Text("Classic Bluetooth ELM327 (most adapters). Pair it in Android's Bluetooth settings first; the PIN is usually 1234 or 0000.", style = hint, color = hintColor)
                    if (!granted) {
                        Button(onClick = { request(perms) }) { Text("ALLOW BLUETOOTH") }
                    } else {
                        DeviceList(devices.paired, a.bluetooth, enabled) { onAdapter(a.copy(bluetooth = it)) }
                        if (devices.paired.isEmpty()) Text("No paired devices yet.", style = hint, color = GaugeTheme.status.warning)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { openSettings(context, Settings.ACTION_BLUETOOTH_SETTINGS) }) { Text("PAIR NEW") }
                            TextButton(onClick = onRefreshPaired) { Text("REFRESH") }
                        }
                    }
                }
                AdapterKind.BLE -> {
                    val perms = bluetoothPermissions(scan = true)
                    val request = rememberPermissionRequest { ok -> if (ok) onBleScan() }
                    Text("Bluetooth LE ELM327 (Veepeak BLE, Vgate iCar Pro, OBDLink CX, ...). No pairing needed: scan and pick it.", style = hint, color = hintColor)
                    val shown = if (a.ble != null && devices.ble.none { it.address == a.ble.address }) listOf(a.ble) + devices.ble else devices.ble
                    DeviceList(shown, a.ble, enabled) { onAdapter(a.copy(ble = it)) }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (devices.bleScanning) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text("Scanning…", style = hint, color = hintColor)
                            TextButton(onClick = onBleStop) { Text("STOP") }
                        } else {
                            OutlinedButton(
                                onClick = { if (hasPermissions(context, perms)) onBleScan() else request(perms) },
                                enabled = enabled,
                            ) { Text("SCAN") }
                        }
                    }
                }
                AdapterKind.WIFI -> {
                    var host by rememberSaveable(a.wifiHost) { mutableStateOf(a.wifiHost) }
                    var port by rememberSaveable(a.wifiPort) { mutableStateOf(a.wifiPort.toString()) }
                    Text("Join the adapter's Wi-Fi network (often WiFi_OBDII or OBDCHECK) in Android settings first. Most use 192.168.0.10 port 35000.", style = hint, color = hintColor)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            host, {
                                host = it.trim()
                                if (host.isNotEmpty()) onAdapter(a.copy(wifiHost = host))
                            },
                            Modifier.weight(2f), label = { Text("Address") }, singleLine = true, enabled = enabled,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        )
                        OutlinedTextField(
                            port, {
                                port = it.filter(Char::isDigit).take(5)
                                port.toIntOrNull()?.takeIf { p -> p in 1..65535 }?.let { p -> onAdapter(a.copy(wifiPort = p)) }
                            },
                            Modifier.weight(1f), label = { Text("Port") }, singleLine = true, enabled = enabled,
                            isError = port.toIntOrNull()?.let { it in 1..65535 } != true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { openSettings(context, Settings.ACTION_WIFI_SETTINGS) }) { Text("WI-FI SETTINGS") }
                        TextButton(
                            onClick = { onAdapter(a.copy(wifiHost = AdapterPrefs.DEFAULT_WIFI_HOST, wifiPort = AdapterPrefs.DEFAULT_WIFI_PORT)) },
                            enabled = enabled,
                        ) { Text("DEFAULTS") }
                    }
                }
                AdapterKind.USB_CABLE -> Text(
                    "Raw K-line (KKL) cable on a USB OTG adapter. Best for bench diagnosis off the water: it does its own " +
                        "wake-up and framing, and shows every byte on the wire.",
                    style = hint, color = hintColor,
                )
            }
            devices.message?.let { Text(it, style = hint, color = GaugeTheme.status.warning) }
        }
    })
}

@Composable
private fun DeviceList(list: List<BtDevice>, selected: BtDevice?, enabled: Boolean, onPick: (BtDevice) -> Unit) {
    Column {
        list.forEach { d ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = enabled, role = Role.RadioButton) { onPick(d) }
                    .heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = d.address == selected?.address, onClick = null, enabled = enabled)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(d.name, style = MaterialTheme.typography.bodyLarge)
                    Text(d.address, style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace), color = GaugeTheme.status.faint)
                }
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
                    if (enabled) "Simulated ECU on the same K-line pipeline. No adapter needed."
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
            ecu.link?.let { InfoRow("Link", it) }
            ecu.adapter?.let { InfoRow("Adapter", it) }
            ecu.protocol?.let { InfoRow("Protocol", it) }
            ecu.voltage?.let { InfoRow("Voltage at plug", it) }
            InfoRow("Key bytes", ecu.keyBytes, mono = true)
            ecu.echo?.let { InfoRow("Echo", it) }
            InfoRow("Diagnostic session", ecu.diag)
            ecu.baud?.let { InfoRow("Baud", "%,d".format(it)) }
            if (connection.updateHz > 0) InfoRow("Update rate", "%.1f Hz".format(connection.updateHz))
            if (connection.demoSession) InfoRow("Source", "Demo ECU (simulated)")
            ecu.response?.let { response ->
                Spacer(Modifier.height(8.dp))
                Text("StartCommunication reply", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SelectionContainer {
                    Text(response, style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    })
}

@Composable
private fun RecordingCard(recording: RecordingUi) {
    if (recording.fileName == null) return
    val context = LocalContext.current
    SectionCard(content = {
        Column(Modifier.padding(20.dp)) {
            Text("CSV LOG", style = GaugeLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (recording.active) "Recording to ${recording.fileName}"
                    else "Saved ${recording.savedRows ?: 0} rows to ${recording.fileName}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (recording.active) GaugeTheme.status.critical else GaugeTheme.status.ok,
                    modifier = Modifier.weight(1f),
                )
                val log = recording.log
                if (!recording.active && log != null) {
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { shareLog(context, log) }) { Text("SHARE") }
                }
            }
            recording.folder?.let {
                Text(it, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = GaugeTheme.status.faint)
            }
        }
    })
}

/** Every saved CSV (recordings, research logs, scan exports), each with Share and Delete. */
@Composable
private fun LogsCard(logs: List<LogFile>, folder: String, busy: Set<String>, onDelete: (LogFile) -> Unit) {
    val context = LocalContext.current
    var showAll by rememberSaveable { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<LogFile?>(null) }
    SectionCard(content = {
        Column(Modifier.padding(20.dp)) {
            Text("LOGS", style = GaugeLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                if (logs.isEmpty()) "No logs yet. Recordings, research logs and scan exports are saved to $folder."
                else "${logs.size} saved. New logs go to $folder" +
                    if (folder.startsWith("Documents")) ", where the Files app can see them." else ".",
                style = MaterialTheme.typography.bodySmall,
                color = GaugeTheme.status.faint,
            )
            val shown = if (showAll) logs else logs.take(LOGS_SHOWN)
            for (log in shown) {
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(log.name, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), maxLines = 2)
                        Text(
                            "${formatSize(log.sizeBytes)} · ${formatDate(log.modifiedMs)}" + if (log.inDocuments) "" else " · app folder",
                            style = MaterialTheme.typography.bodySmall,
                            color = GaugeTheme.status.faint,
                        )
                    }
                    val writing = log.key in busy
                    TextButton(onClick = { shareLog(context, log) }) { Text("SHARE") }
                    IconButton(onClick = { confirm = log }, enabled = !writing) {
                        Icon(Icons.Filled.Delete, contentDescription = if (writing) "Recording, can't delete" else "Delete ${log.name}")
                    }
                }
            }
            if (logs.size > LOGS_SHOWN) {
                TextButton(onClick = { showAll = !showAll }) { Text(if (showAll) "SHOW FEWER" else "SHOW ALL ${logs.size}") }
            }
        }
    })
    confirm?.let { log ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Delete log?") },
            text = { Text("${log.name} will be deleted from this device. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(log)
                    confirm = null
                }) { Text("DELETE", color = GaugeTheme.status.critical) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("CANCEL") } },
        )
    }
}

private const val LOGS_SHOWN = 5

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(Locale.US, bytes / 1024.0)
    else -> "%.1f MB".format(Locale.US, bytes / (1024.0 * 1024))
}

private fun formatDate(ms: Long): String = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(ms))

/** Display units, each picked on its own. Logs always keep the native units. */
@Composable
private fun UnitsCard(units: UnitPrefs, onUnits: (UnitPrefs) -> Unit) {
    val chipColors = FilterChipDefaults.filterChipColors(
        selectedContainerColor = MaterialTheme.colorScheme.primary,
        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
    )
    SectionCard(content = {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("UNITS", style = GaugeLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
            @Composable
            fun <E> UnitRow(label: String, values: List<E>, selected: E, symbol: (E) -> String, pick: (E) -> Unit) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(96.dp))
                    values.forEach { v -> FilterChip(v == selected, { pick(v) }, { Text(symbol(v)) }, colors = chipColors) }
                }
            }
            UnitRow("Temperature", TemperatureUnit.entries, units.temperature, { it.symbol }) { onUnits(units.copy(temperature = it)) }
            UnitRow("Pressure", PressureUnit.entries, units.pressure, { it.symbol }) { onUnits(units.copy(pressure = it)) }
            UnitRow("Speed", SpeedUnit.entries, units.speed, { it.symbol }) { onUnits(units.copy(speed = it)) }
            Text(
                "Display only: CSV and research logs keep °C, kPa and mph so they compare across sessions.",
                style = MaterialTheme.typography.bodySmall,
                color = GaugeTheme.status.faint,
            )
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
                    Text("Raw traffic log", style = MaterialTheme.typography.titleMedium)
                    Text("${rawLog.size} lines", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                            if (line.dir == Direction.NOTE) "AT" else line.dir.name,
                                            style = mono,
                                            color = when (line.dir) {
                                                Direction.TX -> MaterialTheme.colorScheme.primary
                                                Direction.RX -> status.ok
                                                Direction.NOTE -> status.faint
                                            },
                                        )
                                        Spacer(Modifier.width(10.dp))
                                        Text(
                                            line.hex,
                                            style = mono,
                                            color = if (line.dir == Direction.NOTE) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                                        )
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
