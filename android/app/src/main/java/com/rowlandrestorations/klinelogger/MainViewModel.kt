package com.rowlandrestorations.klinelogger

import android.app.Application
import android.content.Context
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.SystemClock
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.rowlandrestorations.klinelogger.channels.ChannelStore
import com.rowlandrestorations.klinelogger.channels.PidDef
import com.rowlandrestorations.klinelogger.codes.DtcProtocol
import com.rowlandrestorations.klinelogger.codes.SERVICE_CODE_FILE
import com.rowlandrestorations.klinelogger.codes.ServiceCode
import com.rowlandrestorations.klinelogger.codes.StoredCode
import com.rowlandrestorations.klinelogger.codes.loadServiceCodes
import com.rowlandrestorations.klinelogger.codes.parseDtcReply
import com.rowlandrestorations.klinelogger.demo.DemoEcuTransport
import com.rowlandrestorations.klinelogger.kline.BadChecksumException
import com.rowlandrestorations.klinelogger.kline.Direction
import com.rowlandrestorations.klinelogger.kline.KLine
import com.rowlandrestorations.klinelogger.kline.EcuLink
import com.rowlandrestorations.klinelogger.kline.KLineException
import com.rowlandrestorations.klinelogger.kline.KLineSettings
import com.rowlandrestorations.klinelogger.kline.NegativeResponseException
import com.rowlandrestorations.klinelogger.kline.NoResponseException
import com.rowlandrestorations.klinelogger.kline.SessionInfo
import com.rowlandrestorations.klinelogger.kline.Transport
import com.rowlandrestorations.klinelogger.kline.hexs
import com.rowlandrestorations.klinelogger.kline.nrcName
import com.rowlandrestorations.klinelogger.elm.BleLink
import com.rowlandrestorations.klinelogger.elm.BtDevice
import com.rowlandrestorations.klinelogger.elm.ByteLink
import com.rowlandrestorations.klinelogger.elm.Elm327
import com.rowlandrestorations.klinelogger.elm.ElmSession
import com.rowlandrestorations.klinelogger.elm.looksLikeAdapter
import com.rowlandrestorations.klinelogger.elm.openBluetoothClassic
import com.rowlandrestorations.klinelogger.elm.openWifi
import com.rowlandrestorations.klinelogger.elm.pairedDevices
import com.rowlandrestorations.klinelogger.elm.scanBle
import com.rowlandrestorations.klinelogger.location.GpsSpeed
import com.rowlandrestorations.klinelogger.logging.CsvRecorder
import com.rowlandrestorations.klinelogger.logging.LogFile
import com.rowlandrestorations.klinelogger.logging.LogStore
import com.rowlandrestorations.klinelogger.logging.ResearchLogger
import com.rowlandrestorations.klinelogger.logging.TrafficRecorder
import com.rowlandrestorations.klinelogger.vehicle.VehicleImage
import com.rowlandrestorations.klinelogger.settings.AdapterKind
import com.rowlandrestorations.klinelogger.settings.AdapterPrefs
import com.rowlandrestorations.klinelogger.settings.AppPrefs
import com.rowlandrestorations.klinelogger.settings.DashStyle
import com.rowlandrestorations.klinelogger.settings.UnitPrefs
import com.rowlandrestorations.klinelogger.settings.isKpa
import com.rowlandrestorations.klinelogger.usb.UsbSerialTransport
import com.rowlandrestorations.klinelogger.usb.requestUsbPermission
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.Closeable
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors

enum class LinkState { DISCONNECTED, CONNECTING, CONNECTED }

/** What the StartCommunication / StartDiagnosticSession handshake reported. Cable-only and adapter-only fields are null otherwise. */
data class EcuInfo(
    val address: Int,
    val keyBytes: String,
    val diag: String,
    val response: String? = null,   // the raw StartCommunication frame (cable)
    val echo: String? = null,       // cable
    val baud: Int? = null,          // cable
    val adapter: String? = null,    // ELM327: "ELM327 v1.5"
    val link: String? = null,       // ELM327: "Bluetooth · OBDII"
    val protocol: String? = null,   // ELM327: "ISO 14230-4 (KWP FAST)"
    val voltage: String? = null,    // ELM327: supply voltage at the diagnostic plug
)

data class ConnectionUi(
    val state: LinkState = LinkState.DISCONNECTED,
    val status: String = "Pick your adapter, then press Connect.",
    val ok: Boolean? = null,
    val demoMode: Boolean = false,
    val demoSession: Boolean = false,
    val ecu: EcuInfo? = null,
    val updateHz: Double = 0.0,
    val adapter: AdapterPrefs = AdapterPrefs(),
    val sessionKind: AdapterKind? = null,      // what the open (or opening) session uses; null for demo
)

/** Devices the Connection screen offers for the Bluetooth adapters. */
data class DevicesUi(
    val paired: List<BtDevice> = emptyList(),
    val ble: List<BtDevice> = emptyList(),
    val bleScanning: Boolean = false,
    val message: String? = null,
)

/** The research log: every channel, GPS speed and the raw bytes of scanned PIDs, once a second. */
data class ResearchUi(
    val active: Boolean = false,
    val pids: List<Int> = emptyList(),
    val rows: Int = 0,
    val log: LogFile? = null,
    val message: String? = null,
)

/** Latest decoded value of one channel, the bytes it came from, and recent history for sparklines. */
data class Reading(val value: Double?, val history: List<Float>, val error: String? = null, val raw: ByteArray? = null)

data class RawLine(val seconds: Double, val dir: Direction, val hex: String)

data class RecordingUi(
    val active: Boolean = false,
    val startedAt: Long = 0L,       // SystemClock.elapsedRealtime()
    val fileName: String? = null,
    val folder: String? = null,
    val savedRows: Int? = null,
    val log: LogFile? = null,
    val trafficLog: LogFile? = null,    // the raw bus traffic written alongside the CSV
)

enum class ScanMode { IDLE, SCANNING, WATCHING }

enum class ScanResult { ANSWERS, REFUSED, NO_ANSWER, BAD_CHECKSUM }

/** All data bytes as one big-endian unsigned number: the formula variable `raw`. */
fun rawValue(data: ByteArray): Double = data.fold(0.0) { acc, b -> acc * 256 + (b.toInt() and 0xFF) }

/** One PID's scan result. While watching, [lo]/[hi]/[changes]/[history] track the raw value. */
data class ScanRow(
    val pid: Int,
    val result: ScanResult,
    val detail: String = "",
    val data: ByteArray? = null,
    val first: String = "",
    val lo: Double? = null,
    val hi: Double? = null,
    val changes: Int = 0,
    val history: List<Float> = emptyList(),
) {
    val hex: String get() = data?.let(::hexs).orEmpty()
    val value: Double? get() = data?.let(::rawValue)

    /** How far the raw value moved, as a percent of its highest reading (same as the desktop scanner). */
    val swing: Double?
        get() {
            val l = lo ?: return null
            val h = hi ?: return null
            return if (h > 0) (h - l) * 100 / h else null
        }
}

data class ScanUi(
    val mode: ScanMode = ScanMode.IDLE,
    val from: Int = 0x00,
    val to: Int = 0xFF,
    val total: Int = 0,
    val done: Int = 0,
    val rows: Map<Int, ScanRow> = emptyMap(),
    val watching: List<Int> = emptyList(),
    val sampleHz: Double = 0.0,
    val exported: LogFile? = null,
    val message: String = "Scan a range of PIDs to see everything this ECU answers. Live data pauses while the scanner runs.",
) {
    val answering: List<ScanRow> get() = rows.values.filter { it.result == ScanResult.ANSWERS }.sortedBy { it.pid }
}

/** One fault-code request and everything that came back, for the Codes screen's debug section. */
data class CodeExchange(val label: String, val request: String, val response: String, val result: String, val frames: List<RawLine>)

data class CodesUi(
    val busy: Boolean = false,
    val codes: List<StoredCode>? = null,        // null = not read yet this session
    val message: String = "Connect, then press Read Codes to ask the ECU for its stored service codes.",
    val ok: Boolean? = null,
    val exchanges: List<CodeExchange> = emptyList(),   // newest first
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val store = ChannelStore(app)
    private val _channels: MutableStateFlow<List<PidDef>>
    private val channelError: String?

    init {
        var err: String? = null
        val loaded = try {
            store.load()
        } catch (e: Exception) {
            err = "Your saved channels couldn't be read (${e.message}). Using the defaults."
            try {
                store.defaults()
            } catch (e2: Exception) {
                err = "Couldn't read the PID definitions: ${e2.message}"
                emptyList()
            }
        }
        _channels = MutableStateFlow(loaded)
        channelError = err
    }

    /** The channel list (pids.json). Edited from Live Data and the scanner; saved on every change. */
    val channels: StateFlow<List<PidDef>> = _channels.asStateFlow()

    private val prefs = AppPrefs(app)

    private val _connection = MutableStateFlow(
        ConnectionUi(status = channelError ?: ConnectionUi().status, ok = channelError?.let { false }, adapter = prefs.adapter()),
    )
    val connection: StateFlow<ConnectionUi> = _connection.asStateFlow()

    private val _devices = MutableStateFlow(DevicesUi())
    val devices: StateFlow<DevicesUi> = _devices.asStateFlow()
    private var stopBleScan: (() -> Unit)? = null

    private val _units = MutableStateFlow(prefs.units())
    val units: StateFlow<UnitPrefs> = _units.asStateFlow()

    /** Intake pressure last read with the engine stopped: the atmosphere the vacuum/boost display measures from. */
    private val _atmosphere = MutableStateFlow<Double?>(null)
    val atmosphere: StateFlow<Double?> = _atmosphere.asStateFlow()

    private val _dashStyle = MutableStateFlow(prefs.dashStyle())
    val dashStyle: StateFlow<DashStyle> = _dashStyle.asStateFlow()

    /** Phone GPS speed, for Live Data and the research log (the ECU has no working speed channel). */
    val gps = GpsSpeed(app)

    private val _research = MutableStateFlow(ResearchUi())
    val research: StateFlow<ResearchUi> = _research.asStateFlow()

    /** What changed, when the saved channel list predates the built-in defaults: offer the new ones once. */
    private val _defaultsOffer = MutableStateFlow(
        if (channelError == null && store.savedRevision() < store.defaultsRevision) store.defaultsNote else null,
    )
    val defaultsOffer: StateFlow<String?> = _defaultsOffer.asStateFlow()

    private val _readings = MutableStateFlow<Map<Int, Reading>>(emptyMap())
    val readings: StateFlow<Map<Int, Reading>> = _readings.asStateFlow()

    private val _rawLog = MutableStateFlow<List<RawLine>>(emptyList())
    val rawLog: StateFlow<List<RawLine>> = _rawLog.asStateFlow()

    private val _recording = MutableStateFlow(RecordingUi())
    val recording: StateFlow<RecordingUi> = _recording.asStateFlow()

    private val _scan = MutableStateFlow(ScanUi())
    val scan: StateFlow<ScanUi> = _scan.asStateFlow()

    /** The service manual's code table (ultra_lx_service_codes.json), keyed by code number. */
    private val serviceCodes: Map<Int, ServiceCode> = try {
        loadServiceCodes(app)
    } catch (e: Exception) {
        emptyMap()
    }

    private val _codes = MutableStateFlow(
        if (serviceCodes.isEmpty()) CodesUi(message = "Couldn't read $SERVICE_CODE_FILE: codes will show without descriptions.", ok = false)
        else CodesUi(),
    )
    val codes: StateFlow<CodesUi> = _codes.asStateFlow()

    // One thread owns the cable, so serial I/O never runs on the main thread and never overlaps.
    private val serialExecutor = Executors.newSingleThreadExecutor { Thread(it, "kline-serial") }
    private val serial = serialExecutor.asCoroutineDispatcher()

    private val usb = app.getSystemService(Context.USB_SERVICE) as UsbManager
    private val settings = KLineSettings()
    /** CSV logs: Documents/KlineLogger on Android 10+, the app-private folder otherwise. */
    val logStore = LogStore(app)

    /** Saved CSV logs (recordings, research logs, scan exports), newest first. */
    private val _logs = MutableStateFlow<List<LogFile>>(emptyList())
    val logs: StateFlow<List<LogFile>> = _logs.asStateFlow()

    private val saveLock = Mutex()

    private var session: Job? = null
    private var userStopped = false
    private var logStart = System.nanoTime()

    // The open session's link, for one-off requests (fault codes) slotted in between bus steps.
    @Volatile private var activeLink: EcuLink? = null

    // Frames of the fault-code request in progress. Touched only on the serial thread.
    private var capture: MutableList<RawLine>? = null

    /** What the bus is doing, like kline.py's engine mode: poll the channels, scan a range, or watch scan hits. */
    private sealed interface BusMode {
        data object Poll : BusMode
        class Scan(val queue: ArrayDeque<Int>) : BusMode
        class Watch(val pids: List<Int>) : BusMode {
            var pos = 0
            val fresh = HashSet(pids)        // lo/hi/changes restart from each PID's first reading
            var rateStart = System.nanoTime()
            var rateReads = 0
        }
    }

    // Set from the main thread; the Scan/Watch objects themselves are only touched on the serial thread.
    @Volatile private var busMode: BusMode = BusMode.Poll

    // Channels whose history must restart (edited or deleted). Drained by the poll loop.
    private val resetPids = ConcurrentLinkedQueue<Int>()

    // Touched only on the serial thread.
    private var recorder: CsvRecorder? = null
    private var traffic: TrafficRecorder? = null     // every bus frame, written alongside the CSV while recording
    private var researchLog: ResearchLogger? = null
    private var sessionStart = 0L
    private var misses = 0

    // Research log PIDs to read alongside the channels. Set from the main thread, read on the serial thread.
    @Volatile private var researchPids: List<Int> = emptyList()

    // --- connection ---------------------------------------------------------

    fun setDemoMode(on: Boolean) {
        if (_connection.value.state != LinkState.DISCONNECTED) return
        _connection.update { it.copy(demoMode = on, ok = null, status = idleStatus(on, it.adapter.kind)) }
    }

    private fun idleStatus(demo: Boolean, kind: AdapterKind): String = when {
        demo -> "Demo mode: press Connect to start the simulated ECU."
        kind == AdapterKind.USB_CABLE -> "Plug in the K-line cable, wake the ECU, then press Connect."
        kind == AdapterKind.WIFI -> "Join the adapter's Wi-Fi network, turn the key on, then press Connect."
        else -> "Plug the adapter into the ski's diagnostic plug, turn the key on, then press Connect."
    }

    /** Remembers which adapter to use (and which device or address). Only while disconnected. */
    fun setAdapter(a: AdapterPrefs) {
        if (_connection.value.state != LinkState.DISCONNECTED) return
        prefs.saveAdapter(a)
        _connection.update { it.copy(adapter = a, ok = null, status = idleStatus(it.demoMode, a.kind)) }
    }

    fun refreshPairedDevices() {
        _devices.update { it.copy(paired = pairedDevices(getApplication()), message = null) }
    }

    /** Looks for Bluetooth LE adapters for ten seconds. */
    fun startBleScan() {
        stopBleScan?.invoke()
        _devices.update { it.copy(ble = emptyList(), bleScanning = true, message = null) }
        var stop: (() -> Unit)? = null
        stop = scanBle(
            getApplication(),
            onFound = { d -> _devices.update { s -> s.copy(ble = (s.ble + d).sortedByDescending { looksLikeAdapter(it.name) }) } },
            onError = { msg -> _devices.update { it.copy(bleScanning = false, message = msg) } },
        )
        stopBleScan = stop
        viewModelScope.launch {
            delay(10_000)
            if (stopBleScan === stop) stopBleScanNow()
        }
    }

    fun stopBleScanNow() {
        stopBleScan?.invoke()
        stopBleScan = null
        _devices.update { it.copy(bleScanning = false) }
    }

    fun connect() {
        val c = _connection.value
        if (c.state != LinkState.DISCONNECTED) return
        val demo = c.demoMode
        val adapter = c.adapter
        val kind = adapter.kind
        if (!demo && kind == AdapterKind.BLUETOOTH && adapter.bluetooth == null) {
            return fail("Pick your adapter from the paired devices first. Pair it in Android's Bluetooth settings if it isn't listed (PIN is usually 1234 or 0000).")
        }
        if (!demo && kind == AdapterKind.BLE && adapter.ble == null) return fail("Press Scan, then pick your adapter first.")
        stopBleScanNow()
        userStopped = false
        logStart = System.nanoTime()
        busMode = BusMode.Poll
        _rawLog.value = emptyList()
        _readings.value = emptyMap()
        _codes.update { it.copy(codes = null, busy = false) }    // codes from another session may be another ECU's
        _connection.update {
            it.copy(
                state = LinkState.CONNECTING, ok = null, ecu = null, updateHz = 0.0, demoSession = demo,
                sessionKind = if (demo) null else kind,
                status = when {
                    demo -> "Starting the simulated ECU…"
                    kind == AdapterKind.USB_CABLE -> "Looking for the cable…"
                    kind == AdapterKind.BLUETOOTH -> "Connecting to ${adapter.bluetooth?.name}…"
                    kind == AdapterKind.BLE -> "Connecting to ${adapter.ble?.name}…"
                    else -> "Connecting to ${adapter.wifiHost}:${adapter.wifiPort}…"
                },
            )
        }
        val wired = demo || kind == AdapterKind.USB_CABLE
        session = viewModelScope.launch {
            var closeable: Closeable? = null
            var link: EcuLink? = null
            try {
                val (ecuLink, info) = if (wired) {
                    val port: Transport = if (demo) DemoEcuTransport() else openUsbCable() ?: return@launch
                    closeable = port
                    _connection.update { it.copy(status = "Waking the ECU…") }
                    val kline = KLine(port, settings, ::logTraffic)
                    val s = withContext(serial) { kline.startSession() }
                    kline to s.toEcuInfo()
                } else {
                    val io = withContext(serial) { openAdapter(adapter) }
                    closeable = io
                    _connection.update { it.copy(status = "Adapter connected. Waking the ECU…") }
                    val elm = Elm327(io, ::logTraffic, ::logNote)
                    val s = withContext(serial) { elm.startSession() }
                    elm to s.toEcuInfo(io.description)
                }
                link = ecuLink
                activeLink = ecuLink
                _connection.update {
                    it.copy(
                        state = LinkState.CONNECTED, ok = true, ecu = info,
                        status = "ECU 0x%02X answered. Diagnostic session %s.".format(info.address, info.diag),
                    )
                }
                withContext(serial) { runBus(ecuLink) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: KLineException) {
                fail(e.message ?: "No answer from the ECU.")
            } catch (e: IOException) {
                fail(if (wired) "Cable problem: ${e.message}" else e.message ?: "Lost the adapter.")
            } catch (e: SecurityException) {
                fail("Bluetooth permission was denied. Allow Nearby devices for this app in Android settings.")
            } finally {
                busMode = BusMode.Poll
                activeLink = null
                // The whole cleanup must be NonCancellable: after Disconnect this job is cancelled, and a plain
                // withContext would throw on returning to it, skipping the state updates below.
                withContext(NonCancellable) {
                    withContext(serial) {
                        stopRecorder()
                        stopResearchLog("Stopped: the ECU link closed.")
                        try {
                            if (userStopped) link?.stop()
                        } catch (_: IOException) {
                        }
                        try {
                            closeable?.close()
                        } catch (_: IOException) {
                        }
                    }
                    _scan.update {
                        if (it.mode == ScanMode.IDLE) it
                        else it.copy(mode = ScanMode.IDLE, watching = emptyList(), message = "Stopped: the ECU link closed. ${summary(it)}")
                    }
                    _connection.update {
                        if (userStopped) it.copy(state = LinkState.DISCONNECTED, ok = null, status = "Disconnected.", updateHz = 0.0)
                        else it.copy(state = LinkState.DISCONNECTED, updateHz = 0.0)
                    }
                }
            }
        }
    }

    fun disconnect() {
        val job = session ?: return
        if (_connection.value.state == LinkState.DISCONNECTED) return
        userStopped = true
        _connection.update { it.copy(status = "Disconnecting…") }
        viewModelScope.launch { job.cancelAndJoin() }
    }

    fun toggleRecording() {
        if (_connection.value.state != LinkState.CONNECTED) return
        viewModelScope.launch(serial) {
            if (recorder != null) {
                stopRecorder()
                return@launch
            }
            try {
                startRecorder()
            } catch (e: IOException) {
                stopRecorder()
                _connection.update { it.copy(status = "Couldn't start the log: ${e.message}", ok = false) }
            }
        }
    }

    private fun sessionTag() = if (_connection.value.demoSession) "demo" else "ski"

    /** One recording, two files: the channel values (CSV) and every request and reply on the bus (traffic). */
    @Throws(IOException::class)
    private fun startRecorder() {
        val r = CsvRecorder.start(logStore, _channels.value, sessionTag())
        recorder = r
        val t = TrafficRecorder.start(logStore, sessionTag(), sessionStart)
        traffic = t
        _recording.value = RecordingUi(
            active = true, startedAt = SystemClock.elapsedRealtime(), fileName = r.log.name, folder = logStore.folderLabel,
            log = r.log, trafficLog = t.log,
        )
        refreshLogs()
    }

    fun clearRawLog() {
        _rawLog.value = emptyList()
    }

    fun setUnits(u: UnitPrefs) {
        prefs.saveUnits(u)
        _units.value = u
    }

    fun setDashStyle(s: DashStyle) {
        prefs.saveDashStyle(s)
        _dashStyle.value = s
    }

    // --- the rider's ski picture (Digital dashboard) -----------------------------

    private val _vehicle = MutableStateFlow<ImageBitmap?>(null)
    val vehicle: StateFlow<ImageBitmap?> = _vehicle.asStateFlow()
    private val _vehicleFlip = MutableStateFlow(prefs.vehicleFlip())
    val vehicleFlip: StateFlow<Boolean> = _vehicleFlip.asStateFlow()

    init {
        loadVehicle()
    }

    private fun loadVehicle() {
        viewModelScope.launch(Dispatchers.IO) { _vehicle.value = VehicleImage.load(getApplication())?.asImageBitmap() }
    }

    /** Imports a picture from the photo picker: background cut out, trimmed, saved for next time. */
    fun importVehicle(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _vehicle.value = VehicleImage.import(getApplication(), uri).asImageBitmap()
            } catch (e: Exception) {
                _connection.update { it.copy(status = "Couldn't use that picture: ${e.message}", ok = false) }
            }
        }
    }

    fun flipVehicle() {
        val flip = !_vehicleFlip.value
        prefs.saveVehicleFlip(flip)
        _vehicleFlip.value = flip
    }

    /** Back to the built-in drawing. */
    fun clearVehicle() {
        viewModelScope.launch(Dispatchers.IO) {
            VehicleImage.clear(getApplication())
            _vehicle.value = null
        }
    }

    // --- research log -----------------------------------------------------------

    /**
     * Logs every channel, GPS speed and the raw bytes of [pids] (PIDs the scanner found) once a second
     * until stopped. Like the iOS app's Research Mode: move, rev or switch things while it runs, then look
     * for the PID that tracks what you did. Streams to disk, so a crash on the water doesn't lose the run.
     */
    fun startResearchLog(pids: List<Int>) {
        if (_connection.value.state != LinkState.CONNECTED || _research.value.active) return
        if (_scan.value.mode != ScanMode.IDLE) {
            _research.update { it.copy(message = "Stop the scan or watch first: the research log reads alongside live data.") }
            return
        }
        val extras = pids.sorted()
        viewModelScope.launch(serial) {
            try {
                val tag = if (_connection.value.demoSession) "demo" else "ski"
                researchLog = ResearchLogger.start(logStore, _channels.value, extras, tag)
                researchPids = extras
                _research.value = ResearchUi(
                    active = true, pids = extras, log = researchLog?.log,
                    message = "Logging ${_channels.value.size} channels, GPS speed and ${extras.size} scanned PIDs once a second.",
                )
                refreshLogs()
            } catch (e: IOException) {
                _research.update { it.copy(message = "Couldn't start the research log: ${e.message}") }
            }
        }
    }

    fun stopResearchLog() {
        viewModelScope.launch(serial) { stopResearchLog(null) }
    }

    /** Serial thread only. */
    private fun stopResearchLog(reason: String?) {
        val r = researchLog ?: return
        researchLog = null
        researchPids = emptyList()
        try {
            r.close()
        } catch (_: IOException) {
        }
        _research.value = ResearchUi(
            rows = r.rows, log = r.log,
            message = (reason?.let { "$it " } ?: "") + "Saved ${r.rows} rows to ${r.log.name}.",
        )
        refreshLogs()
    }

    // --- saved logs ---------------------------------------------------------------

    /** Re-reads the log folders, e.g. when the Connection screen opens (files may have gone via the Files app). */
    fun refreshLogs() {
        viewModelScope.launch(Dispatchers.IO) { _logs.value = logStore.list() }
    }

    /** Deletes a saved log. The one being written right now can't be deleted. */
    fun deleteLog(log: LogFile) {
        if (log.key == recorder?.log?.key || log.key == researchLog?.log?.key || log.key == traffic?.log?.key) return
        viewModelScope.launch(Dispatchers.IO) {
            if (!logStore.delete(log)) {
                _connection.update { it.copy(status = "Couldn't delete ${log.name}. Try the Files app.", ok = false) }
            }
            _logs.value = logStore.list()
        }
    }

    // --- GPS ----------------------------------------------------------------------

    /** Call once location permission is granted. */
    fun startGps() = gps.start()

    // --- channels -----------------------------------------------------------

    /** Adds [ch] (when [originalPid] is null) or replaces the channel that had [originalPid]. Returns an error, or null. */
    fun saveChannel(originalPid: Int?, ch: PidDef): String? {
        val list = _channels.value
        list.firstOrNull { it.pid == ch.pid && it.pid != originalPid }?.let {
            return "PID 0x%02X already has a channel (%s).".format(ch.pid, it.name)
        }
        val updated = if (originalPid == null) list + ch else list.map { if (it.pid == originalPid) ch else it }
        commitChannels(updated, setOfNotNull(originalPid, ch.pid))
        return null
    }

    fun deleteChannel(pid: Int) {
        commitChannels(_channels.value.filter { it.pid != pid }, setOf(pid))
    }

    fun resetChannels() {
        val defaults = try {
            store.defaults()
        } catch (e: Exception) {
            return
        }
        commitChannels(defaults, (_channels.value + defaults).map { it.pid }.toSet())
    }

    val channelFile: String get() = store.file.path

    /** Answer to the new-defaults offer: take the bundled channels, or keep the user's (and stop asking). */
    fun answerDefaultsOffer(useNew: Boolean) {
        _defaultsOffer.value = null
        if (useNew) resetChannels() else commitChannels(_channels.value, emptySet())   // re-saving stamps the current revision
    }

    private fun commitChannels(list: List<PidDef>, changed: Set<Int>) {
        _channels.value = list
        resetPids.addAll(changed)
        _readings.update { it - changed }
        viewModelScope.launch(Dispatchers.IO) {
            saveLock.withLock {
                try {
                    store.save(_channels.value)
                } catch (e: IOException) {
                    _connection.update { it.copy(status = "Couldn't save the channels: ${e.message}", ok = false) }
                }
            }
        }
    }

    // --- scanner ------------------------------------------------------------

    /** The scanner takes over the bus, which would leave gaps in a research log. */
    private fun researchBlocksScanner(): Boolean {
        if (!_research.value.active) return false
        _scan.update { it.copy(message = "Stop the research log first: scanning or watching would leave gaps in it.") }
        return true
    }

    fun startScan(from: Int, to: Int) {
        if (_connection.value.state != LinkState.CONNECTED || researchBlocksScanner()) return
        val lo = minOf(from, to)
        val hi = maxOf(from, to)
        _scan.value = ScanUi(
            mode = ScanMode.SCANNING, from = lo, to = hi, total = hi - lo + 1,
            message = "Scanning 0x%02X to 0x%02X. Live data pauses until the scan ends.".format(lo, hi),
        )
        busMode = BusMode.Scan(ArrayDeque((lo..hi).toList()))
    }

    /** Re-reads the answering PIDs in [selected] (all of them if none are selected) as fast as the bus allows. */
    fun watch(selected: Set<Int>) {
        if (_connection.value.state != LinkState.CONNECTED || researchBlocksScanner()) return
        val answering = _scan.value.answering.map { it.pid }
        if (answering.isEmpty()) {
            _scan.update { it.copy(message = "Run a scan first. Watch re-reads the PIDs that answered.") }
            return
        }
        val pids = answering.filter { it in selected }.ifEmpty { answering }
        _scan.update {
            it.copy(
                mode = ScanMode.WATCHING, watching = pids, sampleHz = 0.0,
                message = "Watching ${pids.size} PIDs. Now do what you want to find (rev it, flip a switch, " +
                    "unplug a sensor), then sort by Swing or Changes.",
            )
        }
        busMode = BusMode.Watch(pids)
    }

    fun stopScan() {
        busMode = BusMode.Poll
        _scan.update {
            if (it.mode == ScanMode.IDLE) it
            else it.copy(mode = ScanMode.IDLE, watching = emptyList(), sampleHz = 0.0, message = summary(it) + " Live data has resumed.")
        }
    }

    fun exportScan() {
        val s = _scan.value
        if (s.rows.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            var exported: LogFile? = null
            val msg = try {
                val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
                val output = logStore.create("pid_scan_$stamp.csv")
                output.stream.bufferedWriter().use { w ->
                    w.write("pid,result,bytes,first,last,value,low,high,swing_pct,changes\n")
                    for (r in s.rows.values.sortedBy { it.pid }) {
                        w.write(
                            listOf(
                                "0x%02X".format(r.pid), r.detail, r.data?.size?.toString().orEmpty(), r.first, r.hex,
                                r.value?.let { "%.0f".format(Locale.US, it) }.orEmpty(),
                                r.lo?.let { "%.0f".format(Locale.US, it) }.orEmpty(),
                                r.hi?.let { "%.0f".format(Locale.US, it) }.orEmpty(),
                                r.swing?.let { "%.1f".format(Locale.US, it) }.orEmpty(),
                                r.changes.toString(),
                            ).joinToString(",") { "\"$it\"" } + "\n",
                        )
                    }
                }
                exported = output.log
                "Saved the scan to ${output.log.name}."
            } catch (e: IOException) {
                "Couldn't save the scan: ${e.message}"
            }
            _scan.update { it.copy(message = msg, exported = exported) }
            refreshLogs()
        }
    }

    private fun summary(s: ScanUi): String {
        if (s.rows.isEmpty()) return ""
        val answered = s.answering.size
        val moving = s.rows.values.count { it.changes > 0 }
        return "Done. $answered PIDs answered" + if (moving > 0) ", $moving changed while watching." else "."
    }

    // --- fault codes ----------------------------------------------------------

    fun readCodes() = codeRequest("Read codes", DtcProtocol.READ_REQUEST, "Reading stored codes…") { resp ->
        val parsed = parseDtcReply(resp, serviceCodes)
        val n = parsed.codes.size
        val msg = when (n) {
            0 -> "No codes stored."
            1 -> "1 code stored."
            else -> "$n codes stored."
        }
        _codes.update { it.copy(codes = parsed.codes, ok = parsed.warning == null, message = parsed.warning?.let { w -> "$msg $w" } ?: msg) }
        "$n code(s)" + (parsed.warning?.let { ", $it" } ?: "")
    }

    /**
     * Tries every form in [DtcProtocol.CLEAR_FORMS], first in the normal 10 80 session and then in each of
     * [DtcProtocol.CLEAR_SESSIONS] the ECU accepts, asking for a security seed in each (never sending a key) to
     * see whether it wants unlocking. Always switches back to 10 80 afterwards, then reads the codes again.
     */
    fun clearCodes() {
        val link = activeLink
        if (link == null || _connection.value.state != LinkState.CONNECTED) {
            _codes.update { it.copy(message = "Connect to the ECU first.", ok = false) }
            return
        }
        if (_codes.value.busy) return
        _codes.update { it.copy(busy = true, ok = null, message = "Clearing codes…", exchanges = emptyList()) }
        viewModelScope.launch(serial) {
            val sessions = listOf(DtcProtocol.NORMAL_SESSION) + DtcProtocol.CLEAR_SESSIONS
            var cleared: String? = null
            var silent = 0
            val notes = mutableListOf<String>()
            fun send(label: String, payload: ByteArray): Pair<ByteArray?, Int?> {
                val frames = mutableListOf<RawLine>()
                capture = frames
                var reply: ByteArray? = null
                var nrc: Int? = null
                val result = try {
                    reply = link.request(payload)
                    misses = 0
                    silent = 0
                    "ANSWERED"
                } catch (e: NegativeResponseException) {
                    silent = 0
                    nrc = e.code
                    "refused (${nrcName(e.code)})"
                } catch (e: KLineException) {
                    silent++
                    e.message ?: "no answer"
                } catch (e: IOException) {
                    silent = CODE_GIVE_UP
                    "cable problem: ${e.message}"
                } finally {
                    capture = null
                }
                val ex = CodeExchange(label, hexs(payload), reply?.let(::hexs) ?: nrc?.let { "7F %02X %02X".format(payload[0], it) } ?: "", result, frames.toList())
                _codes.update { it.copy(exchanges = it.exchanges + ex) }
                return reply to nrc
            }
            try {
                sessions@ for (session in sessions) {
                    if (activeLink !== link || silent >= CODE_GIVE_UP) break
                    val name = "session %02X".format(session)
                    _codes.update { it.copy(message = "Clearing codes… trying $name") }
                    if (session != DtcProtocol.NORMAL_SESSION) {
                        val (ok, nrc) = send("Start $name", byteArrayOf(0x10, session.toByte()))
                        if (ok == null) {
                            notes += "$name: " + (nrc?.let { nrcName(it) } ?: "no answer")
                            continue
                        }
                    }
                    val (seed, seedNrc) = send("Seed request in $name", DtcProtocol.SEED_REQUEST)
                    notes += "$name: " + when {
                        seed != null -> "accepted, wants unlocking (seed ${hexs(seed.drop(2).toByteArray())})"
                        seedNrc != null -> "accepted, seed ${nrcName(seedNrc)}"
                        else -> "accepted"
                    }
                    for (form in DtcProtocol.CLEAR_FORMS) {
                        val (ok, _) = send("Clear in $name", form)
                        if (ok != null) {
                            cleared = "${hexs(form)} in $name"
                            break@sessions
                        }
                        if (silent >= CODE_GIVE_UP) break@sessions
                    }
                }
            } finally {
                // Back to the session the poller expects, whatever happened above.
                if (activeLink === link) send("Back to session 80", byteArrayOf(0x10, DtcProtocol.NORMAL_SESSION.toByte()))
            }
            _codes.update {
                it.copy(
                    busy = false, ok = cleared != null,
                    message = if (cleared != null) "Codes cleared with $cleared. Reading again to confirm…"
                    else "No clear request accepted. " + notes.joinToString("; ") + ". See the debug section.",
                )
            }
            if (cleared != null) readCodes()
        }
    }

    private fun codeRequest(
        label: String,
        payload: ByteArray,
        busyMessage: String,
        afterSuccess: (() -> Unit)? = null,
        onReply: (ByteArray) -> String,
    ) = codeRequest(label, listOf(payload), busyMessage, afterSuccess, onReply)

    /**
     * Sends [payloads] between bus steps until the ECU accepts one, recording each exchange (the debug section
     * keeps the last few; a traffic log keeps them all). [onReply] handles the positive answer and returns a
     * summary; [afterSuccess] then runs once the request is no longer busy. Gives up early if the ECU goes quiet.
     */
    private fun codeRequest(
        label: String,
        payloads: List<ByteArray>,
        busyMessage: String,
        afterSuccess: (() -> Unit)? = null,
        onReply: (ByteArray) -> String,
    ) {
        val link = activeLink
        if (link == null || _connection.value.state != LinkState.CONNECTED) {
            _codes.update { it.copy(message = "Connect to the ECU first.", ok = false) }
            return
        }
        if (_codes.value.busy) return
        _codes.update { it.copy(busy = true, ok = null, message = busyMessage) }
        viewModelScope.launch(serial) {
            var success = false
            var silentInARow = 0
            val refusals = sortedMapOf<Int, Int>()      // refusal code -> how many, for the summary
            for ((i, payload) in payloads.withIndex()) {
                if (activeLink !== link) break
                if (payloads.size > 1) _codes.update { it.copy(message = "$busyMessage (${i + 1} of ${payloads.size}: ${hexs(payload)})") }
                val frames = mutableListOf<RawLine>()
                capture = frames
                var response = ""
                val result = try {
                    val resp = link.request(payload)
                    misses = 0
                    silentInARow = 0
                    response = hexs(resp)
                    success = true
                    onReply(resp)
                } catch (e: NegativeResponseException) {
                    silentInARow = 0
                    response = "7F %02X %02X".format(e.sid, e.code)
                    refusals.merge(e.code, 1, Int::plus)
                    "refused (${nrcName(e.code)})"
                } catch (e: KLineException) {
                    silentInARow++
                    e.message ?: "no answer"
                } catch (e: IOException) {
                    silentInARow = CODE_GIVE_UP
                    "cable problem: ${e.message}"
                } finally {
                    capture = null
                }
                val ex = CodeExchange(label, hexs(payload), response, result, frames.toList())
                _codes.update { it.copy(exchanges = (listOf(ex) + it.exchanges).take(MAX_CODE_EXCHANGES)) }
                if (success || silentInARow >= CODE_GIVE_UP) break
            }
            if (!success) {
                val tried = if (payloads.size > 1) "Tried ${payloads.size} forms; none accepted" else "Not accepted"
                val why = refusals.entries.joinToString(", ") { (code, n) -> "$n × ${nrcName(code)}" }
                val msg = when {
                    silentInARow >= CODE_GIVE_UP -> "The ECU stopped answering. See the debug section."
                    why.isEmpty() -> "No usable answer. See the debug section."
                    else -> "$tried ($why). See the debug section."
                }
                _codes.update { it.copy(ok = false, message = msg) }
            }
            _codes.update { it.copy(busy = false) }
            if (success) afterSuccess?.invoke()
        }
    }

    // --- bus loop (serial thread) ---------------------------------------------

    private suspend fun openUsbCable(): Transport? {
        val driver = withContext(serial) {
            UsbSerialProber.getDefaultProber().findAllDrivers(usb).firstOrNull()
        } ?: return null.also { fail("No USB serial cable found. Check the OTG adapter, or turn on Demo mode.") }

        if (!requestUsbPermission(getApplication(), usb, driver.device)) {
            return null.also { fail("USB access was denied.") }
        }
        return withContext(serial) {
            UsbSerialTransport.open(usb, driver, settings.baud, settings.dtr, settings.rts)
        }
    }

    /** Per-session state of the channel poller. */
    private class PollState {
        val history = HashMap<Int, ArrayDeque<Float>>()
        val latest = HashMap<Int, Double?>()
        val unsupported = HashSet<Int>()
        val raw = HashMap<Int, ByteArray>()     // latest bytes of every PID read, for the research log
        var cycle = 0L
        val queued = ArrayDeque<Int>()          // non-priority PIDs that are due, read one per cycle
        var extraPos = 0
        var rateStart = System.nanoTime()
        var rateCycles = 0
    }

    /** Reads one channel and publishes the result. */
    private fun readChannel(link: EcuLink, ch: PidDef, st: PollState) {
        val history = st.history.getOrPut(ch.pid) { ArrayDeque(HISTORY + 1) }
        val reading: Reading = try {
            val data = link.readLocalId(ch.pid)
            misses = 0
            st.raw[ch.pid] = data
            val v = try {
                ch.decode(data)
            } catch (e: RuntimeException) {
                null
            }
            if (v == null || v.isNaN() || v.isInfinite()) {
                Reading(null, history.toList(), "Formula error", data)
            } else {
                st.latest[ch.pid] = v
                if (isKpa(ch.units) && v in 60.0..110.0 && engineStopped(st)) _atmosphere.value = v
                history.addLast(v.toFloat())
                if (history.size > HISTORY) history.removeFirst()
                Reading(v, history.toList(), raw = data)
            }
        } catch (e: NegativeResponseException) {
            if (e.code in PERMANENT_NRCS) st.unsupported += ch.pid
            st.latest[ch.pid] = null
            st.raw.remove(ch.pid)
            Reading(null, history.toList(), "ECU refused: ${nrcName(e.code)}")
        } catch (e: KLineException) {
            missed(link)
            return
        }
        _readings.update { it + (ch.pid to reading) }
    }

    /** True when every RPM channel's latest reading is 0, so intake pressure is the atmosphere. */
    private fun engineStopped(st: PollState): Boolean {
        val rpm = _channels.value.filter { it.units.trim().lowercase() == "rpm" }.mapNotNull { st.latest[it.pid] }
        return rpm.isNotEmpty() && rpm.all { it == 0.0 }
    }

    /** Runs the bus until cancelled or the ECU stops answering: polling channels, or scanning/watching. */
    private suspend fun runBus(link: EcuLink) {
        val poll = PollState()
        sessionStart = System.nanoTime()
        misses = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            when (val mode = busMode) {
                BusMode.Poll -> pollCycle(link, poll)
                is BusMode.Scan -> scanStep(link, mode)
                is BusMode.Watch -> watchStep(link, mode)
            }
            yield()
        }
    }

    /** Silence once is noise; three in a row means asking the ECU whether it's still there. */
    private fun missed(link: EcuLink) {
        misses++
        if (misses >= 3) {
            if (!link.alive()) throw NoResponseException("The ECU stopped answering. Check the key and the cable.")
            misses = 0
        }
    }

    /** Reads every channel due this cycle (each at its own `every` rate). */
    private suspend fun pollCycle(link: EcuLink, st: PollState) {
        while (true) {
            val pid = resetPids.poll() ?: break
            st.history.remove(pid)
            st.latest.remove(pid)
            st.unsupported.remove(pid)
        }
        val channels = _channels.value
        if (channels.all { it.pid in st.unsupported }) {
            // Nothing to read: keep the session alive so the link status stays truthful.
            if (!link.alive()) throw NoResponseException("The ECU stopped answering. Check the key and the cable.")
            return
        }
        // Priority channels (RPM, throttle) are read every cycle. The rest join a queue when their `every` comes
        // round and are read one per cycle, so a cycle is never more than the priority reads plus one: the gauges
        // keep their pace however many slow channels there are.
        val priority = channels.filter { it.priority && it.pid !in st.unsupported }
        val others = channels.filter { !it.priority && it.pid !in st.unsupported }
        val plan = if (priority.isEmpty()) {
            others.filter { st.cycle % it.every == 0L }
        } else {
            for (ch in others) if (st.cycle % ch.every == 0L && ch.pid !in st.queued) st.queued.addLast(ch.pid)
            var next: PidDef? = null
            while (next == null && st.queued.isNotEmpty()) {
                val pid = st.queued.removeFirst()
                next = others.firstOrNull { it.pid == pid }   // skips channels deleted or made priority meanwhile
            }
            priority + listOfNotNull(next)
        }
        for (ch in plan) {
            if (busMode != BusMode.Poll) return          // the scanner took over mid-cycle
            if (ch.pid in st.unsupported) continue
            readChannel(link, ch, st)
            yield()
        }
        // Research log: one scanned PID per cycle, round-robin, so the gauges keep their pace.
        val extras = researchPids.filter { pid -> channels.none { it.pid == pid } }
        repeat(minOf(EXTRAS_PER_CYCLE, extras.size)) {
            if (busMode != BusMode.Poll) return
            val pid = extras[st.extraPos++ % extras.size]
            try {
                st.raw[pid] = link.readLocalId(pid)
                misses = 0
            } catch (e: NegativeResponseException) {
                st.raw.remove(pid)
            } catch (e: KLineException) {
                missed(link)
            }
            yield()
        }
        st.cycle++
        val elapsed = (System.nanoTime() - sessionStart) / 1e9
        recorder?.let { r ->
            r.write(elapsed, r.channels.map { st.latest[it.pid] })
        }
        researchLog?.let { r ->
            if (elapsed - r.lastRowSeconds >= RESEARCH_INTERVAL_S) {
                try {
                    gps.expireStale()
                    r.write(elapsed, st.latest, gps.mph.value, st.raw)
                    _research.update { it.copy(rows = r.rows) }
                } catch (e: IOException) {
                    stopResearchLog("Stopped: couldn't write the file (${e.message}).")
                }
            }
        }
        st.rateCycles++
        val now = System.nanoTime()
        if (now - st.rateStart >= 1_000_000_000L) {
            val hz = st.rateCycles * 1e9 / (now - st.rateStart)
            _connection.update { it.copy(updateHz = hz) }
            st.rateStart = now
            st.rateCycles = 0
        }
    }

    /** Asks the ECU for the next PID in the scan range. Ported from kline.py's _scan_step. */
    private fun scanStep(link: EcuLink, m: BusMode.Scan) {
        val pid = m.queue.firstOrNull()
        if (pid == null) {
            if (busMode === m) {
                busMode = BusMode.Poll
                _scan.update { it.copy(mode = ScanMode.IDLE, message = summary(it) + " Live data has resumed.") }
            }
            return
        }
        val row = try {
            val data = link.readLocalId(pid)
            misses = 0
            val v = rawValue(data)
            ScanRow(pid, ScanResult.ANSWERS, "answers", data, hexs(data), v, v, history = listOf(v.toFloat()))
        } catch (e: NegativeResponseException) {
            ScanRow(pid, ScanResult.REFUSED, "refused (${nrcName(e.code)})")
        } catch (e: BadChecksumException) {
            ScanRow(pid, ScanResult.BAD_CHECKSUM, "bad checksum")
        } catch (e: KLineException) {
            // Silence could mean "unsupported PID" or "ECU gone": ask the ECU which.
            if (!link.alive()) throw NoResponseException("The ECU stopped answering during the scan.")
            ScanRow(pid, ScanResult.NO_ANSWER, "no answer")
        }
        m.queue.removeFirst()
        _scan.update { s ->
            val rows = s.rows + (pid to row)
            val answered = rows.values.count { it.result == ScanResult.ANSWERS }
            s.copy(rows = rows, done = s.done + 1, message = "Scanned ${s.done + 1} of ${s.total}. $answered answered so far.")
        }
    }

    /** Re-reads the watched PIDs in turn, tracking low/high/changes. Ported from kline.py's _watch_step. */
    private fun watchStep(link: EcuLink, m: BusMode.Watch) {
        if (m.pids.isEmpty()) {
            busMode = BusMode.Poll
            return
        }
        val pid = m.pids[m.pos % m.pids.size]
        m.pos++
        try {
            val data = link.readLocalId(pid)
            misses = 0
            val v = rawValue(data)
            val fresh = m.fresh.remove(pid)
            _scan.update { s ->
                val old = s.rows[pid] ?: return@update s
                val row = if (fresh) {
                    old.copy(data = data, lo = v, hi = v, changes = 0, history = listOf(v.toFloat()))
                } else {
                    old.copy(
                        data = data,
                        lo = minOf(old.lo ?: v, v),
                        hi = maxOf(old.hi ?: v, v),
                        changes = old.changes + if (data.contentEquals(old.data)) 0 else 1,
                        history = (old.history + v.toFloat()).takeLast(WATCH_HISTORY),
                    )
                }
                s.copy(rows = s.rows + (pid to row))
            }
        } catch (e: NegativeResponseException) {
            // A PID that answered during the scan and now refuses: skip it this round.
        } catch (e: KLineException) {
            missed(link)
        }
        m.rateReads++
        val now = System.nanoTime()
        if (now - m.rateStart >= 1_000_000_000L) {
            val perPid = m.rateReads * 1e9 / (now - m.rateStart) / m.pids.size
            _scan.update { it.copy(sampleHz = perPid) }
            m.rateStart = now
            m.rateReads = 0
        }
    }

    private fun stopRecorder() {
        stopTraffic()
        val r = recorder ?: return
        recorder = null
        try {
            r.close()
        } catch (_: IOException) {
        }
        _recording.value = RecordingUi(
            fileName = r.log.name, folder = logStore.folderLabel, savedRows = r.rows, log = r.log, trafficLog = _recording.value.trafficLog,
        )
        refreshLogs()
    }

    private fun stopTraffic() {
        val t = traffic ?: return
        traffic = null
        try {
            t.close()
        } catch (_: IOException) {
        }
    }

    private fun logTraffic(dir: Direction, data: ByteArray) {
        val line = RawLine((System.nanoTime() - logStart) / 1e9, dir, hexs(data))
        capture?.add(line)
        appendRawLine(line)
        recordTraffic(dir, line.hex)
    }

    /** An ELM327 AT command and its answer, shown in the raw log between the frames. */
    private fun logNote(text: String) {
        appendRawLine(RawLine((System.nanoTime() - logStart) / 1e9, Direction.NOTE, text))
        recordTraffic(Direction.NOTE, text)
    }

    /** Called on the serial thread, like every other bus callback. */
    private fun recordTraffic(dir: Direction, data: String) {
        val t = traffic ?: return
        try {
            t.write(dir, data)
        } catch (e: IOException) {
            stopTraffic()
            _connection.update { it.copy(status = "Traffic log stopped: couldn't write the file (${e.message}). Channel CSV still recording.", ok = false) }
        }
    }

    private fun appendRawLine(line: RawLine) {
        _rawLog.update { if (it.size >= MAX_RAW_LINES) it.drop(it.size - MAX_RAW_LINES + 1) + line else it + line }
    }

    private fun fail(status: String) {
        _connection.update { it.copy(status = status, ok = false) }
    }

    @Throws(IOException::class)
    private fun openAdapter(a: AdapterPrefs): ByteLink = when (a.kind) {
        AdapterKind.BLUETOOTH -> openBluetoothClassic(getApplication(), a.bluetooth ?: throw IOException("No adapter picked."))
        AdapterKind.BLE -> BleLink.open(getApplication(), a.ble ?: throw IOException("No adapter picked."))
        AdapterKind.WIFI -> openWifi(getApplication(), a.wifiHost, a.wifiPort)
        AdapterKind.USB_CABLE -> throw IOException("The USB cable doesn't use an ELM327.")
    }

    private fun SessionInfo.toEcuInfo() = EcuInfo(
        address = start.ecu,
        keyBytes = hexs(start.keyBytes),
        echo = when (start.echo) {
            true -> "Cable echoes"
            false -> "No echo"
            null -> "Unknown"
        },
        diag = diag,
        baud = baud,
        response = hexs(start.response),
    )

    private fun ElmSession.toEcuInfo(link: String) = EcuInfo(
        address = ecu,
        keyBytes = keyBytes.ifEmpty { "—" },
        diag = diag,
        adapter = version,
        link = link,
        protocol = protocol,
        voltage = voltage,
    )

    override fun onCleared() {
        stopBleScanNow()
        gps.stop()
        serial.close()
    }

    companion object {
        const val HISTORY = 120
        const val WATCH_HISTORY = 200
        private const val MAX_RAW_LINES = 400
        private const val MAX_CODE_EXCHANGES = 8
        private const val CODE_GIVE_UP = 5           // unanswered code requests in a row before giving up
        private const val EXTRAS_PER_CYCLE = 1
        private const val RESEARCH_INTERVAL_S = 1.0
        private val PERMANENT_NRCS = setOf(0x11, 0x12, 0x31)
    }
}
