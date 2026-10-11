package com.rowlandrestorations.klinelogger

import android.app.Application
import android.content.Context
import android.hardware.usb.UsbManager
import android.os.SystemClock
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
import com.rowlandrestorations.klinelogger.kline.KLineException
import com.rowlandrestorations.klinelogger.kline.KLineSettings
import com.rowlandrestorations.klinelogger.kline.NegativeResponseException
import com.rowlandrestorations.klinelogger.kline.NoResponseException
import com.rowlandrestorations.klinelogger.kline.SessionInfo
import com.rowlandrestorations.klinelogger.kline.Transport
import com.rowlandrestorations.klinelogger.kline.hexs
import com.rowlandrestorations.klinelogger.kline.nrcName
import com.rowlandrestorations.klinelogger.logging.CsvRecorder
import com.rowlandrestorations.klinelogger.usb.UsbSerialTransport
import com.rowlandrestorations.klinelogger.usb.requestUsbPermission
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
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
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors

enum class LinkState { DISCONNECTED, CONNECTING, CONNECTED }

/** What the StartCommunication / StartDiagnosticSession handshake reported. */
data class EcuInfo(
    val address: Int,
    val keyBytes: String,
    val echo: String,
    val diag: String,
    val baud: Int,
    val response: String,
)

data class ConnectionUi(
    val state: LinkState = LinkState.DISCONNECTED,
    val status: String = "Plug in the K-line cable, wake the ECU, then press Connect.",
    val ok: Boolean? = null,
    val demoMode: Boolean = false,
    val demoSession: Boolean = false,
    val ecu: EcuInfo? = null,
    val updateHz: Double = 0.0,
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

    private val _connection = MutableStateFlow(ConnectionUi(status = channelError ?: ConnectionUi().status, ok = channelError?.let { false }))
    val connection: StateFlow<ConnectionUi> = _connection.asStateFlow()

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
    private val logDir = File(app.getExternalFilesDir(null) ?: app.filesDir, "logs")
    private val saveLock = Mutex()

    private var session: Job? = null
    private var userStopped = false
    private var logStart = System.nanoTime()

    // The open session's link, for one-off requests (fault codes) slotted in between bus steps.
    @Volatile private var activeLink: KLine? = null

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
    private var sessionStart = 0L
    private var misses = 0

    // --- connection ---------------------------------------------------------

    fun setDemoMode(on: Boolean) {
        if (_connection.value.state != LinkState.DISCONNECTED) return
        _connection.update {
            it.copy(
                demoMode = on,
                ok = null,
                status = if (on) "Demo mode: press Connect to start the simulated ECU."
                else "Plug in the K-line cable, wake the ECU, then press Connect.",
            )
        }
    }

    fun connect() {
        if (_connection.value.state != LinkState.DISCONNECTED) return
        val demo = _connection.value.demoMode
        userStopped = false
        logStart = System.nanoTime()
        busMode = BusMode.Poll
        _rawLog.value = emptyList()
        _readings.value = emptyMap()
        _codes.update { it.copy(codes = null, busy = false) }    // codes from another session may be another ECU's
        _connection.update {
            it.copy(
                state = LinkState.CONNECTING, ok = null, ecu = null, updateHz = 0.0, demoSession = demo,
                status = if (demo) "Starting the simulated ECU…" else "Looking for the cable…",
            )
        }
        session = viewModelScope.launch {
            var port: Transport? = null
            var link: KLine? = null
            try {
                port = if (demo) DemoEcuTransport() else openUsbCable() ?: return@launch
                _connection.update { it.copy(status = "Waking the ECU…") }
                val kline = KLine(port, settings, ::logTraffic)
                val info = withContext(serial) { kline.startSession() }
                link = kline
                activeLink = kline
                _connection.update {
                    it.copy(
                        state = LinkState.CONNECTED, ok = true, ecu = info.toEcuInfo(),
                        status = "ECU 0x%02X answered. Diagnostic session %s.".format(info.start.ecu, info.diag),
                    )
                }
                withContext(serial) { runBus(kline) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: KLineException) {
                fail(e.message ?: "No answer from the ECU.")
            } catch (e: IOException) {
                fail("Cable problem: ${e.message}")
            } finally {
                busMode = BusMode.Poll
                activeLink = null
                // The whole cleanup must be NonCancellable: after Disconnect this job is cancelled, and a plain
                // withContext would throw on returning to it, skipping the state updates below.
                withContext(NonCancellable) {
                    withContext(serial) {
                        stopRecorder()
                        if (userStopped) link?.stop()
                        port?.close()
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
                val tag = if (_connection.value.demoSession) "demo" else "ski"
                val r = CsvRecorder.start(logDir, _channels.value, tag)
                recorder = r
                _recording.value = RecordingUi(
                    active = true, startedAt = SystemClock.elapsedRealtime(), fileName = r.file.name, folder = logDir.path,
                )
            } catch (e: IOException) {
                _connection.update { it.copy(status = "Couldn't start the CSV log: ${e.message}", ok = false) }
            }
        }
    }

    fun clearRawLog() {
        _rawLog.value = emptyList()
    }

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

    fun startScan(from: Int, to: Int) {
        if (_connection.value.state != LinkState.CONNECTED) return
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
        if (_connection.value.state != LinkState.CONNECTED) return
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
            val msg = try {
                logDir.mkdirs()
                val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
                val file = File(logDir, "pid_scan_$stamp.csv")
                file.bufferedWriter().use { w ->
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
                "Saved the scan to ${file.path}"
            } catch (e: IOException) {
                "Couldn't save the scan: ${e.message}"
            }
            _scan.update { it.copy(message = msg) }
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

    fun clearCodes() = codeRequest("Clear codes", DtcProtocol.CLEAR_REQUEST, "Clearing codes…", afterSuccess = ::readCodes) {
        _codes.update { it.copy(codes = emptyList(), ok = true, message = "Codes cleared. Reading again to confirm…") }
        "cleared"
    }

    /**
     * Sends [payload] between bus steps and records the exchange. [onReply] handles a positive answer and
     * returns a summary; [afterSuccess] then runs once the request is no longer busy.
     */
    private fun codeRequest(
        label: String,
        payload: ByteArray,
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
            val frames = mutableListOf<RawLine>()
            capture = frames
            var response = ""
            var success = false
            val result = try {
                val resp = link.request(payload)
                misses = 0
                response = hexs(resp)
                success = true
                onReply(resp)
            } catch (e: NegativeResponseException) {
                response = "7F %02X %02X".format(e.sid, e.code)
                _codes.update { it.copy(ok = false, message = "${e.message}. The ski may use a different service; see the debug section.") }
                "refused (${nrcName(e.code)})"
            } catch (e: KLineException) {
                _codes.update { it.copy(ok = false, message = "No usable answer: ${e.message}") }
                e.message ?: "no answer"
            } catch (e: IOException) {
                _codes.update { it.copy(ok = false, message = "Cable problem: ${e.message}") }
                "cable problem"
            } finally {
                capture = null
            }
            val ex = CodeExchange(label, hexs(payload), response, result, frames.toList())
            _codes.update { it.copy(busy = false, exchanges = (listOf(ex) + it.exchanges).take(MAX_CODE_EXCHANGES)) }
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
        var cycle = 0L
        var rateStart = System.nanoTime()
        var rateCycles = 0
    }

    /** Runs the bus until cancelled or the ECU stops answering: polling channels, or scanning/watching. */
    private suspend fun runBus(link: KLine) {
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
    private fun missed(link: KLine) {
        misses++
        if (misses >= 3) {
            if (!link.alive()) throw NoResponseException("The ECU stopped answering. Check the key and the cable.")
            misses = 0
        }
    }

    /** Reads every channel due this cycle (each at its own `every` rate). */
    private suspend fun pollCycle(link: KLine, st: PollState) {
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
        for (ch in channels) {
            if (busMode != BusMode.Poll) return          // the scanner took over mid-cycle
            if (ch.pid in st.unsupported || st.cycle % ch.every != 0L) continue
            val history = st.history.getOrPut(ch.pid) { ArrayDeque(HISTORY + 1) }
            val reading: Reading = try {
                val data = link.readLocalId(ch.pid)
                misses = 0
                val v = try {
                    ch.decode(data)
                } catch (e: RuntimeException) {
                    null
                }
                if (v == null || v.isNaN() || v.isInfinite()) {
                    Reading(null, history.toList(), "Formula error", data)
                } else {
                    st.latest[ch.pid] = v
                    history.addLast(v.toFloat())
                    if (history.size > HISTORY) history.removeFirst()
                    Reading(v, history.toList(), raw = data)
                }
            } catch (e: NegativeResponseException) {
                if (e.code in PERMANENT_NRCS) st.unsupported += ch.pid
                st.latest[ch.pid] = null
                Reading(null, history.toList(), "ECU refused: ${nrcName(e.code)}")
            } catch (e: KLineException) {
                missed(link)
                continue
            }
            _readings.update { it + (ch.pid to reading) }
            yield()
        }
        st.cycle++
        recorder?.let { r ->
            r.write((System.nanoTime() - sessionStart) / 1e9, r.channels.map { st.latest[it.pid] })
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
    private fun scanStep(link: KLine, m: BusMode.Scan) {
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
    private fun watchStep(link: KLine, m: BusMode.Watch) {
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
        val r = recorder ?: return
        recorder = null
        try {
            r.close()
        } catch (_: IOException) {
        }
        _recording.value = RecordingUi(fileName = r.file.name, folder = logDir.path, savedRows = r.rows)
    }

    private fun logTraffic(dir: Direction, data: ByteArray) {
        val line = RawLine((System.nanoTime() - logStart) / 1e9, dir, hexs(data))
        capture?.add(line)
        _rawLog.update { if (it.size >= MAX_RAW_LINES) it.drop(it.size - MAX_RAW_LINES + 1) + line else it + line }
    }

    private fun fail(status: String) {
        _connection.update { it.copy(status = status, ok = false) }
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

    override fun onCleared() {
        serial.close()
    }

    companion object {
        const val HISTORY = 120
        const val WATCH_HISTORY = 200
        private const val MAX_RAW_LINES = 400
        private const val MAX_CODE_EXCHANGES = 6
        private val PERMANENT_NRCS = setOf(0x11, 0x12, 0x31)
    }
}
