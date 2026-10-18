package com.rowlandrestorations.klinelogger.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rowlandrestorations.klinelogger.LinkState
import com.rowlandrestorations.klinelogger.MainViewModel
import com.rowlandrestorations.klinelogger.R
import com.rowlandrestorations.klinelogger.settings.LocalUnits
import com.rowlandrestorations.klinelogger.ui.components.LiveDataBanner
import com.rowlandrestorations.klinelogger.ui.components.StatusBar
import com.rowlandrestorations.klinelogger.ui.screens.ChannelEditor
import com.rowlandrestorations.klinelogger.ui.screens.CodesScreen
import com.rowlandrestorations.klinelogger.ui.screens.EditorRequest
import com.rowlandrestorations.klinelogger.ui.screens.ScanScreen
import com.rowlandrestorations.klinelogger.ui.screens.ConnectionScreen
import com.rowlandrestorations.klinelogger.ui.screens.DashboardScreen
import com.rowlandrestorations.klinelogger.ui.screens.LiveDataScreen
import com.rowlandrestorations.klinelogger.ui.theme.GaugeLabel
import kotlinx.coroutines.delay

enum class Screen(val label: String, @DrawableRes val icon: Int) {
    DASHBOARD("Dashboard", R.drawable.ic_nav_dashboard),
    LIVE_DATA("Live Data", R.drawable.ic_nav_live),
    SCAN("Scan", R.drawable.ic_nav_scan),
    CODES("Codes", R.drawable.ic_nav_codes),
    CONNECTION("Connection", R.drawable.ic_nav_connection),
}

@Composable
fun KlineApp(vm: MainViewModel = viewModel()) {
    val connection by vm.connection.collectAsState()
    val readings by vm.readings.collectAsState()
    val rawLog by vm.rawLog.collectAsState()
    val recording by vm.recording.collectAsState()
    val scan by vm.scan.collectAsState()
    val channels by vm.channels.collectAsState()
    val codes by vm.codes.collectAsState()
    val devices by vm.devices.collectAsState()
    val units by vm.units.collectAsState()
    val research by vm.research.collectAsState()
    val defaultsOffer by vm.defaultsOffer.collectAsState()
    val logs by vm.logs.collectAsState()
    val gpsMph by vm.gps.mph.collectAsState()
    val gpsStatus by vm.gps.status.collectAsState()
    var editor by remember { mutableStateOf<EditorRequest?>(null) }
    var screen by rememberSaveable { mutableStateOf(Screen.DASHBOARD) }

    KeepScreenOn(connection.state == LinkState.CONNECTED)
    LaunchedEffect(Unit) { if (vm.gps.hasPermission()) vm.startGps() }

    CompositionLocalProvider(LocalUnits provides units) {
    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = { StatusBar(connection, scan, recording, vm::toggleRecording) },
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest, tonalElevation = 0.dp) {
                    Screen.entries.forEach { s ->
                        NavigationBarItem(
                            selected = s == screen,
                            onClick = { screen = s },
                            icon = { Icon(painterResource(s.icon), contentDescription = null) },
                            label = { Text(s.label, style = MaterialTheme.typography.labelMedium) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                                indicatorColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                }
            },
        ) { padding ->
            val banner = @Composable {
                LiveDataBanner(connection, scan, onOpenConnection = { screen = Screen.CONNECTION }, onOpenScan = { screen = Screen.SCAN })
            }
            Crossfade(screen, Modifier.padding(padding), animationSpec = tween(180), label = "screen") { s ->
                when (s) {
                    Screen.DASHBOARD -> DashboardScreen(channels, readings, connection, banner)
                    Screen.LIVE_DATA -> LiveDataScreen(
                        channels, readings, connection, banner, gpsMph, gpsStatus,
                        onEnableGps = vm::startGps,
                        onEdit = { editor = EditorRequest(it, it.pid) },
                        onAdd = { editor = EditorRequest(null, null) },
                        onReset = vm::resetChannels,
                    )
                    Screen.SCAN -> ScanScreen(
                        scan, connection, channels, research,
                        onResearchStart = vm::startResearchLog,
                        onResearchStop = vm::stopResearchLog,
                        onScan = vm::startScan,
                        onWatch = vm::watch,
                        onStop = vm::stopScan,
                        onExport = vm::exportScan,
                        onOpenRow = { row, ch ->
                            editor = EditorRequest(ch, row.pid, notes = "Added from a PID scan. First answer: ${row.first}")
                        },
                    )
                    Screen.CODES -> CodesScreen(codes, connection, onRead = vm::readCodes, onClear = vm::clearCodes)
                    Screen.CONNECTION -> ConnectionScreen(
                        connection, devices, units, recording, logs, vm.logStore.folderLabel,
                        writingLogs = setOfNotNull(
                            recording.log?.key?.takeIf { recording.active },
                            research.log?.key?.takeIf { research.active },
                        ),
                        rawLog = rawLog,
                        onConnect = vm::connect,
                        onDisconnect = vm::disconnect,
                        onDemoMode = vm::setDemoMode,
                        onAdapter = vm::setAdapter,
                        onRefreshPaired = vm::refreshPairedDevices,
                        onBleScan = vm::startBleScan,
                        onBleStop = vm::stopBleScanNow,
                        onUnits = vm::setUnits,
                        onClearLog = vm::clearRawLog,
                        onRefreshLogs = vm::refreshLogs,
                        onDeleteLog = vm::deleteLog,
                    )
                }
            }
        }
        editor?.let { req ->
            // Preview against whichever source has this PID's freshest bytes: the scanner or the poller.
            val pid = req.original?.pid ?: req.pid
            val sample = pid?.let { scan.rows[it]?.data ?: readings[it]?.raw }
            ChannelEditor(
                req, sample,
                onSave = vm::saveChannel,
                onDelete = vm::deleteChannel,
                onDismiss = { editor = null },
            )
        }
        defaultsOffer?.let { DefaultsOfferDialog(note = it, onAnswer = vm::answerDefaultsOffer) }
        BrandIntro()
    }
    }
}

/** Keeps the display awake while the ECU is connected, so a mounted phone stays readable. */
@Composable
private fun KeepScreenOn(on: Boolean) {
    val view = LocalView.current
    DisposableEffect(on) {
        view.keepScreenOn = on
        onDispose { view.keepScreenOn = false }
    }
}

/** Branded card that follows the system splash on a cold start, then fades out. */
@Composable
private fun BrandIntro() {
    var visible by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(1100)
        visible = false
    }
    AnimatedVisibility(visible, enter = EnterTransition.None, exit = fadeOut(tween(450))) {
        Column(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.size(200.dp))
            Text("ROWLAND RESTORATIONS", style = GaugeLabel.copy(fontSize = 14.sp, letterSpacing = 3.sp), color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(6.dp))
            Text("K-Line Logger", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
        }
    }
}

/** After an update with new default channels, asks once whether to take them (like the desktop app). */
@Composable
private fun DefaultsOfferDialog(note: String, onAnswer: (Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("New default channels") },
        text = {
            Text(
                "This version has updated default channels: $note.\n\n" +
                    "Replace your channel list with the new defaults? Choose Keep Mine if you added channels or " +
                    "calibrated the throttle yourself. You can still restore the defaults any time from Live Data.",
            )
        },
        confirmButton = { TextButton(onClick = { onAnswer(true) }) { Text("USE NEW DEFAULTS") } },
        dismissButton = { TextButton(onClick = { onAnswer(false) }) { Text("KEEP MINE") } },
    )
}
