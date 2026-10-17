package com.rowlandrestorations.klinelogger.kline

/**
 * An open KWP2000 session with the ECU, whatever carries it: [KLine] over a raw USB K-line cable, or
 * [com.rowlandrestorations.klinelogger.elm.Elm327] over a Bluetooth / Wi-Fi ELM327 adapter. The poller,
 * scanner and fault-code screen only talk to this, so they work the same on either.
 *
 * Blocking: call only from a background thread. Throws KLineException for ECU problems and
 * IOException when the cable or adapter itself fails.
 */
interface EcuLink {
    /** Sends one KWP2000 request and returns the positive answer's payload (service byte first). */
    fun request(payload: ByteArray, timeoutMs: Long? = null): ByteArray

    /** ReadDataByLocalIdentifier: the data bytes after "61 <pid>". */
    fun readLocalId(pid: Int): ByteArray

    /** Any answer (even a refusal) proves the ECU is still listening. */
    fun alive(): Boolean

    /** Politely ends the session. Never throws for ECU problems. */
    fun stop()
}
