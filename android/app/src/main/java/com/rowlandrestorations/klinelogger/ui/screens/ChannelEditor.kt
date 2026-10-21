package com.rowlandrestorations.klinelogger.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.rowlandrestorations.klinelogger.channels.Formula
import com.rowlandrestorations.klinelogger.channels.PidDef
import com.rowlandrestorations.klinelogger.channels.parsePid
import com.rowlandrestorations.klinelogger.kline.hexs
import com.rowlandrestorations.klinelogger.rawValue
import com.rowlandrestorations.klinelogger.ui.components.autoDecimals
import com.rowlandrestorations.klinelogger.ui.components.formatValue
import com.rowlandrestorations.klinelogger.ui.theme.GaugeLabel
import com.rowlandrestorations.klinelogger.ui.theme.GaugeNumerals
import com.rowlandrestorations.klinelogger.ui.theme.GaugeTheme

/** What the editor opens on: an existing channel, or a new one (optionally for a scanned PID). */
data class EditorRequest(val original: PidDef?, val pid: Int?, val notes: String = "")

// Common Kawasaki/KDS-style scalings to start from. All are guesses until checked against a known reading.
private val FORMULA_PRESETS = listOf(
    "raw" to "raw",
    "First byte" to "A",
    "A×256+B" to "A*256 + B",
    "Signed" to "sraw",
    "Temp °C" to "raw - 40",
    "10-bit → V" to "raw * 5 / 1023",
    "8-bit → V" to "raw * 5 / 255",
    "Battery V" to "raw / 12.75",
    "Percent" to "raw * 100 / 255",
)
private val UNIT_PRESETS = listOf("V", "°C", "%", "kPa", "rpm", "ms", "°", "raw")

@Composable
fun ChannelEditor(
    request: EditorRequest,
    sample: ByteArray?,
    onSave: (originalPid: Int?, PidDef) -> String?,
    onDelete: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val original = request.original
    var name by rememberSaveable { mutableStateOf(original?.name ?: request.pid?.let { "PID 0x%02X".format(it) }.orEmpty()) }
    var pidText by rememberSaveable { mutableStateOf((original?.pid ?: request.pid)?.let { "%02X".format(it) }.orEmpty()) }
    var formula by rememberSaveable { mutableStateOf(original?.formula?.source ?: "raw") }
    var units by rememberSaveable { mutableStateOf(original?.units ?: "raw") }
    var everyText by rememberSaveable { mutableStateOf((original?.every ?: 1).toString()) }
    var priority by rememberSaveable { mutableStateOf(original?.priority ?: false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val status = GaugeTheme.status

    val pid = try {
        parsePid(pidText)
    } catch (e: Exception) {
        null
    }
    val parsed = runCatching { Formula(formula.trim().ifEmpty { "raw" }) }
    val compiled = parsed.getOrNull()
    val formulaError = parsed.exceptionOrNull()?.let { it.message ?: "Can't read this formula" }
    val mono = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.fillMaxWidth(0.94f).widthIn(max = 640.dp).imePadding(),
            shape = RoundedCornerShape(24.dp),
            color = scheme.surfaceContainerHigh,
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (original == null) "Add channel" else "Edit channel", style = MaterialTheme.typography.headlineSmall)

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        name, { name = it }, Modifier.weight(1f), label = { Text("Name") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    )
                    OutlinedTextField(
                        pidText, { pidText = it.uppercase().take(4) }, Modifier.width(104.dp),
                        label = { Text("PID hex") }, prefix = { Text("0x") }, singleLine = true, isError = pid == null,
                        textStyle = mono,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Ascii),
                    )
                }

                // Live preview against the latest bytes from the ECU, so a scaling can be judged before saving.
                Surface(shape = RoundedCornerShape(16.dp), color = scheme.surfaceContainerLowest) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text("PREVIEW", style = GaugeLabel, color = scheme.onSurfaceVariant)
                        Spacer(Modifier.height(6.dp))
                        if (sample == null) {
                            Text(
                                "No bytes from this PID yet. Connect, then scan or watch it to see a live preview.",
                                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                            )
                        } else {
                            val value = compiled?.let {
                                try {
                                    it.eval(sample)
                                } catch (e: Exception) {
                                    null
                                }
                            }
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(
                                    if (value == null || value.isNaN()) "—" else formatValue(value, autoDecimals(value)),
                                    style = GaugeNumerals.copy(fontSize = 40.sp, lineHeight = 42.sp),
                                    color = if (value == null) status.warning else scheme.primary,
                                    modifier = Modifier.alignByBaseline(),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(if (units == "raw") "" else units, style = MaterialTheme.typography.titleMedium, color = scheme.onSurfaceVariant, modifier = Modifier.alignByBaseline())
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(hexs(sample), style = mono, color = scheme.onSurface)
                            Text(
                                byteBreakdown(sample),
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = scheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                OutlinedTextField(
                    formula, { formula = it }, Modifier.fillMaxWidth(), label = { Text("Formula") }, singleLine = true,
                    textStyle = mono, isError = formulaError != null,
                    supportingText = {
                        Text(formulaError ?: "Variables: A B C D (bytes), raw, sraw, n, d[i]. Functions: min max abs round signed math.*")
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                )
                PresetRow(FORMULA_PRESETS) { formula = it }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(units, { units = it }, Modifier.weight(1f), label = { Text("Units") }, singleLine = true)
                    OutlinedTextField(
                        everyText, { everyText = it.filter(Char::isDigit).take(3) }, Modifier.width(120.dp),
                        label = { Text("Read every") }, suffix = { Text("upd") }, singleLine = true,
                        enabled = !priority,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
                PresetRow(UNIT_PRESETS.map { it to it }) { units = it }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Fast channel", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Read on every update, ahead of the rest. Keep this to the one or two gauges that move quickest.",
                            style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(priority, { priority = it })
                }
                Text(
                    "Other channels take turns, one per update. Read every 1 for fast signals like voltage swings; " +
                        "10 or more for slow ones like temperatures.",
                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                )

                error?.let { Text(it, color = status.critical, style = MaterialTheme.typography.bodyMedium) }

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (original != null) {
                        TextButton(
                            onClick = {
                                if (confirmDelete) {
                                    onDelete(original.pid)
                                    onDismiss()
                                } else {
                                    confirmDelete = true
                                }
                            },
                            colors = ButtonDefaults.textButtonColors(contentColor = status.critical),
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text(if (confirmDelete) "TAP TO CONFIRM" else "DELETE") }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("CANCEL") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val every = everyText.toIntOrNull()?.coerceAtLeast(1) ?: 1
                            val p = pid ?: return@Button run { error = "PID must be hex between 00 and FF." }
                            val f = compiled ?: return@Button run { error = "Fix the formula first." }
                            val formulaChanged = original != null && original.formula.source != f.source
                            val ch = PidDef(
                                name = name.trim().ifEmpty { "PID 0x%02X".format(p) },
                                pid = p,
                                formula = f,
                                units = units.trim().ifEmpty { "raw" },
                                enabled = original?.enabled ?: true,
                                every = every,
                                verified = (original?.verified ?: false) && !formulaChanged,
                                notes = original?.notes ?: request.notes,
                                gaugeMin = original?.gaugeMin,
                                gaugeMax = original?.gaugeMax,
                                priority = priority,
                            )
                            error = onSave(original?.pid, ch)
                            if (error == null) onDismiss()
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("SAVE") }
                }
            }
        }
    }
}

@Composable
private fun PresetRow(presets: List<Pair<String, String>>, onPick: (String) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(presets) { (label, value) ->
            SuggestionChip(onClick = { onPick(value) }, label = { Text(label) })
        }
    }
}

private fun byteBreakdown(data: ByteArray): String {
    val letters = data.take(8).mapIndexed { i, b -> "${'A' + i}=${b.toInt() and 0xFF}" }.joinToString("  ")
    return "$letters\nraw=${"%.0f".format(rawValue(data))}  n=${data.size}"
}
