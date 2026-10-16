package com.rowlandrestorations.klinelogger.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rowlandrestorations.klinelogger.ConnectionUi
import com.rowlandrestorations.klinelogger.ResearchUi
import com.rowlandrestorations.klinelogger.LinkState
import com.rowlandrestorations.klinelogger.ScanMode
import com.rowlandrestorations.klinelogger.ScanResult
import com.rowlandrestorations.klinelogger.ScanRow
import com.rowlandrestorations.klinelogger.ScanUi
import com.rowlandrestorations.klinelogger.channels.PidDef
import com.rowlandrestorations.klinelogger.channels.parsePid
import com.rowlandrestorations.klinelogger.ui.components.Sparkline
import com.rowlandrestorations.klinelogger.ui.shareFile
import java.io.File
import com.rowlandrestorations.klinelogger.ui.theme.GaugeLabel
import com.rowlandrestorations.klinelogger.ui.theme.GaugeNumerals
import com.rowlandrestorations.klinelogger.ui.theme.GaugeTheme

// Points in each row's trace: fills in about 30 s when watching a handful of PIDs.
private const val TRACE_POINTS = 60

private enum class ScanSort(val label: String) { PID("PID"), SWING("Swing"), CHANGES("Changes") }

@Composable
fun ScanScreen(
    scan: ScanUi,
    connection: ConnectionUi,
    channels: List<PidDef>,
    research: ResearchUi,
    onResearchStart: (List<Int>) -> Unit,
    onResearchStop: () -> Unit,
    onScan: (Int, Int) -> Unit,
    onWatch: (Set<Int>) -> Unit,
    onStop: () -> Unit,
    onExport: () -> Unit,
    onOpenRow: (ScanRow, PidDef?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showAll by rememberSaveable { mutableStateOf(false) }
    var sort by rememberSaveable { mutableStateOf(ScanSort.PID) }
    var selected by remember { mutableStateOf(emptySet<Int>()) }
    val byPid = channels.associateBy { it.pid }

    val rows = scan.rows.values
        .filter { showAll || it.result == ScanResult.ANSWERS }
        .let { list ->
            when (sort) {
                ScanSort.PID -> list.sortedBy { it.pid }
                ScanSort.SWING -> list.sortedByDescending { it.swing ?: -1.0 }
                ScanSort.CHANGES -> list.sortedByDescending { it.changes }
            }
        }

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Controls(scan, connection, selected, onScan, onWatch, onStop, locked = research.active) }
        if (scan.answering.isNotEmpty() || research.active || research.file != null) {
            item { ResearchCard(scan, connection, research, selected, onResearchStart, onResearchStop) }
        }
        if (scan.rows.isNotEmpty()) {
            item {
                Toolbar(
                    showAll, { showAll = it }, sort, { sort = it },
                    selectedCount = selected.size, onClearSelection = { selected = emptySet() }, onExport = onExport, exported = scan.exported,
                )
            }
        }
        items(rows, key = { it.pid }) { row ->
            ScanRowCard(
                row,
                channel = byPid[row.pid],
                watching = row.pid in scan.watching,
                selected = row.pid in selected,
                onSelect = { on -> selected = if (on) selected + row.pid else selected - row.pid },
                onOpen = { onOpenRow(row, byPid[row.pid]) },
            )
        }
    }
}

@Composable
private fun Controls(
    scan: ScanUi,
    connection: ConnectionUi,
    selected: Set<Int>,
    onScan: (Int, Int) -> Unit,
    onWatch: (Set<Int>) -> Unit,
    onStop: () -> Unit,
    locked: Boolean,          // a research log is running: scanning would leave gaps in it
) {
    val scheme = MaterialTheme.colorScheme
    var fromText by rememberSaveable { mutableStateOf("%02X".format(scan.from)) }
    var toText by rememberSaveable { mutableStateOf("%02X".format(scan.to)) }
    val from = runCatching { parsePid(fromText) }.getOrNull()
    val to = runCatching { parsePid(toText) }.getOrNull()
    val connected = connection.state == LinkState.CONNECTED
    val running = scan.mode != ScanMode.IDLE
    val mono = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace)
    val hexKeys = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Ascii)

    Surface(shape = RoundedCornerShape(20.dp), color = scheme.surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("PID SCANNER", style = GaugeLabel, color = scheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    fromText, { fromText = it.uppercase().take(2) }, Modifier.weight(1f), label = { Text("From") },
                    prefix = { Text("0x") }, singleLine = true, isError = from == null, textStyle = mono, keyboardOptions = hexKeys,
                    enabled = !running,
                )
                OutlinedTextField(
                    toText, { toText = it.uppercase().take(2) }, Modifier.weight(1f), label = { Text("To") },
                    prefix = { Text("0x") }, singleLine = true, isError = to == null, textStyle = mono, keyboardOptions = hexKeys,
                    enabled = !running,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val big = Modifier.weight(1f).height(52.dp)
                if (running) {
                    Button(
                        onClick = onStop, modifier = big,
                        colors = ButtonDefaults.buttonColors(containerColor = GaugeTheme.status.critical, contentColor = GaugeTheme.status.onStatus),
                    ) { Text(if (scan.mode == ScanMode.SCANNING) "STOP SCAN" else "STOP WATCHING") }
                } else {
                    Button(
                        onClick = { if (from != null && to != null) onScan(from, to) },
                        enabled = connected && !locked && from != null && to != null, modifier = big,
                    ) { Text("SCAN") }
                    OutlinedButton(
                        onClick = { onWatch(selected) },
                        enabled = connected && !locked && scan.answering.isNotEmpty(), modifier = big,
                    ) { Text(if (selected.isEmpty()) "WATCH ALL" else "WATCH ${selected.size}") }
                }
            }
            if (scan.mode == ScanMode.SCANNING && scan.total > 0) {
                LinearProgressIndicator(
                    progress = { scan.done.toFloat() / scan.total },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    trackColor = GaugeTheme.status.track,
                )
            }
            if (scan.mode == ScanMode.WATCHING && scan.sampleHz > 0) {
                Text(
                    "%.1f readings/s per PID".format(scan.sampleHz) +
                        if (scan.watching.size > 3) " · select fewer PIDs to sample faster" else "",
                    style = MaterialTheme.typography.labelMedium, color = scheme.primary,
                )
            }
            Text(
                if (!connected && !running) "Connect to the ECU (or Demo mode) to scan." else scan.message,
                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Toolbar(
    showAll: Boolean,
    onShowAll: (Boolean) -> Unit,
    sort: ScanSort,
    onSort: (ScanSort) -> Unit,
    selectedCount: Int,
    onClearSelection: () -> Unit,
    onExport: () -> Unit,
    exported: File?,
) {
    val context = LocalContext.current
    val chipColors = FilterChipDefaults.filterChipColors(
        selectedContainerColor = MaterialTheme.colorScheme.primary,
        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
    )
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("SHOW", style = MaterialTheme.typography.labelSmall, color = GaugeTheme.status.faint, modifier = Modifier.width(44.dp))
            FilterChip(!showAll, { onShowAll(false) }, { Text("Answering") }, colors = chipColors)
            FilterChip(showAll, { onShowAll(true) }, { Text("All results") }, colors = chipColors)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("SORT", style = MaterialTheme.typography.labelSmall, color = GaugeTheme.status.faint, modifier = Modifier.width(44.dp))
            ScanSort.entries.forEach { s ->
                FilterChip(sort == s, { onSort(s) }, { Text(s.label) }, colors = chipColors)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (selectedCount == 0) "Tick PIDs to watch just those. Tap a row to add or edit its channel."
                else "$selectedCount selected",
                style = MaterialTheme.typography.labelMedium,
                color = GaugeTheme.status.faint,
                modifier = Modifier.weight(1f),
            )
            if (selectedCount > 0) TextButton(onClick = onClearSelection) { Text("CLEAR") }
            TextButton(onClick = onExport) { Text("EXPORT CSV") }
            if (exported != null) TextButton(onClick = { shareFile(context, exported) }) { Text("SHARE") }
        }
    }
}

@Composable
private fun ScanRowCard(
    row: ScanRow,
    channel: PidDef?,
    watching: Boolean,
    selected: Boolean,
    onSelect: (Boolean) -> Unit,
    onOpen: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val status = GaugeTheme.status
    val answers = row.result == ScanResult.ANSWERS

    // Flash the card each time the value changes while watching.
    val flash = remember { Animatable(0f) }
    LaunchedEffect(row.changes) {
        if (row.changes > 0) {
            flash.snapTo(1f)
            flash.animateTo(0f, tween(700))
        }
    }
    val bg = scheme.primary.copy(alpha = 0.16f * flash.value).compositeOver(scheme.surfaceContainerHigh)

    Surface(shape = RoundedCornerShape(16.dp), color = bg, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.clickable(enabled = answers || channel != null, onClick = onOpen).padding(start = 4.dp, end = 14.dp, top = 4.dp, bottom = 10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            if (answers) {
                Checkbox(
                    selected, onSelect,
                    colors = CheckboxDefaults.colors(checkedColor = scheme.primary, checkmarkColor = scheme.onPrimary),
                )
            } else {
                Spacer(Modifier.width(48.dp))
            }
            Column(Modifier.weight(1f).padding(top = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "0x%02X".format(row.pid),
                        style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                        color = if (answers) scheme.onSurface else status.faint,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        channel?.name ?: if (answers) (row.data?.size ?: 0).let { if (it == 1) "1 byte" else "$it bytes" } else row.detail,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (channel != null) scheme.primary else if (answers) scheme.onSurfaceVariant else status.faint,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    if (watching) {
                        Text("LIVE", style = MaterialTheme.typography.labelSmall, color = status.ok)
                    }
                }
                if (answers) {
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            row.hex,
                            style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                            color = scheme.onSurface,
                            modifier = Modifier.weight(1f).alignByBaseline(),
                        )
                        Text(
                            row.value?.let { "%,.0f".format(it) }.orEmpty(),
                            style = GaugeNumerals.copy(fontSize = 26.sp, lineHeight = 28.sp),
                            color = scheme.onSurface,
                            modifier = Modifier.alignByBaseline(),
                        )
                    }
                    if (row.history.size > 1) {
                        Spacer(Modifier.height(6.dp))
                        Sparkline(row.history.takeLast(TRACE_POINTS), scheme.primary, Modifier.fillMaxWidth().height(32.dp), capacity = TRACE_POINTS)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "low ${row.lo?.let { "%,.0f".format(it) } ?: "—"} · high ${row.hi?.let { "%,.0f".format(it) } ?: "—"} · " +
                            "swing ${row.swing?.let { "%.1f%%".format(it) } ?: "—"} · ${row.changes} changes",
                        style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                        color = if (row.changes > 0) scheme.primary else status.faint,
                    )
                }
            }
        }
    }
}


/**
 * Research log: every channel, GPS speed and the raw bytes of the PIDs the scan found, once a second, all
 * timestamped together (the iOS app's Research Mode). Ride, rev or flip switches while it runs, then look
 * through the CSV for the PID that tracks what you did. That's how Oil Temp (0x98) was found.
 */
@Composable
private fun ResearchCard(
    scan: ScanUi,
    connection: ConnectionUi,
    research: ResearchUi,
    selected: Set<Int>,
    onStart: (List<Int>) -> Unit,
    onStop: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val connected = connection.state == LinkState.CONNECTED
    val answering = scan.answering.map { it.pid }
    val picked = answering.filter { it in selected }
    Surface(shape = RoundedCornerShape(20.dp), color = scheme.surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("RESEARCH LOG", style = GaugeLabel, color = scheme.onSurfaceVariant)
            if (research.active) {
                Text(
                    "${research.rows} rows · ${research.pids.size} scanned PIDs · ${research.file?.name.orEmpty()}",
                    style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                    color = GaugeTheme.status.critical,
                )
                Button(
                    onClick = onStop, modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = GaugeTheme.status.critical, contentColor = GaugeTheme.status.onStatus),
                ) { Text("STOP LOGGING") }
            } else {
                Text(
                    "Logs every channel, GPS speed and the raw bytes of the scanned PIDs once a second until you stop it. " +
                        "Ride, rev or flip switches while it runs, then share the CSV and look for the PID that moved.",
                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                )
                val idle = connected && scan.mode == ScanMode.IDLE && answering.isNotEmpty()
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { onStart(answering) }, enabled = idle, modifier = Modifier.weight(1f).height(52.dp)) {
                        Text("LOG ALL ${answering.size}")
                    }
                    if (picked.isNotEmpty()) {
                        OutlinedButton(onClick = { onStart(picked) }, enabled = idle, modifier = Modifier.weight(1f).height(52.dp)) {
                            Text("LOG ${picked.size} TICKED")
                        }
                    }
                }
                val file = research.file
                if (file != null) {
                    OutlinedButton(onClick = { shareFile(context, file) }) { Text("SHARE ${file.name}", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
            research.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant) }
        }
    }
}
