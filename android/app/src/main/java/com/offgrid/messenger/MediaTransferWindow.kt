package com.offgrid.messenger

/**
 * Reliable bounded radio window for large encrypted OGC4 media transfers.
 * Only the submitting host changes: radio/firmware, message format, pairing,
 * encryption, and small text/receipt stop-and-wait behavior are untouched.
 * All entry points execute on the Android main handler thread.
 */
class MediaTransferWindow(
    private val frames: List<ByteArray>,
    private val window: Int = 3, // ESP32 currently supports at most 8 pending radio slots.
    private val nowMs: () -> Long,
    private val schedule: (Runnable, Long) -> Unit,
    private val cancel: (Runnable) -> Unit,
    private val sendFrame: (ByteArray) -> Unit,
    private val onProgress: (Int, Long) -> Unit,
    private val onFinished: (Long) -> Unit,
    private val onFailed: (String) -> Unit,
    private val onPaused: () -> Unit
) {
    init { require(frames.isNotEmpty() && window in 1..4) }
    private data class Flight(
        val index: Int, val hex: String, var attempts: Int = 0,
        val radioKeys: MutableSet<String> = linkedSetOf(),
        var timeout: Runnable? = null
    )
    private var next = 0
    private var acknowledged = 0
    private var startedAt = 0L
    private var terminated = false
    private var pauseRequested = false
    private var paused = true
    private val active = linkedMapOf<Int, Flight>()
    val completedCount: Int get() = acknowledged
    val inFlight: Int get() = active.size
    val totalCount: Int get() = frames.size

    fun startOrResume() {
        if (terminated) return
        if (startedAt == 0L) startedAt = nowMs().coerceAtLeast(1)
        pauseRequested = false
        paused = false
        pump()
    }
    /** Finish currently in-flight radio frames before urgent control receipts. */
    fun pauseForReceipts() {
        if (terminated) return
        pauseRequested = true
        pump()
    }
    fun cancelTransfer() {
        terminated = true
        active.values.forEach { it.timeout?.let(cancel) }
        active.clear()
    }
    private fun arm(f: Flight, delay: Long) {
        f.timeout?.let(cancel)
        val alarm = Runnable {
            if (!terminated && active[f.index] === f) retry(f, "radio confirmation timeout")
        }
        f.timeout = alarm
        schedule(alarm, delay)
    }
    private fun transmit(f: Flight) {
        if (f.attempts >= 3) { fail("Radio confirmation failed after 3 attempts"); return }
        f.attempts++
        arm(f, 7_500L) // QUEUED should arrive quickly; delivery may take longer.
        try { sendFrame(frames[f.index]) }
        catch (e: Exception) { fail("BLE send refused: ${e.message ?: e.javaClass.simpleName}") }
    }
    private fun retry(f: Flight, reason: String) {
        if (terminated || active[f.index] !== f) return
        if (f.attempts >= 3) { fail("$reason after 3 attempts"); return }
        // Keep old radio IDs: a delayed successful ACK from an earlier attempt
        // must be able to complete the frame without re-encrypting or losing data.
        transmit(f)
    }
    private fun fail(reason: String) {
        if (terminated) return
        cancelTransfer()
        onFailed(reason)
    }
    private fun pump() {
        if (terminated || paused) return
        if (acknowledged == frames.size) {
            terminated = true
            onFinished((nowMs() - startedAt).coerceAtLeast(1))
            return
        }
        if (pauseRequested) {
            if (active.isEmpty()) { paused = true; onPaused() }
            return
        }
        while (!terminated && active.size < window && next < frames.size) {
            val idx = next++
            val slot = Flight(idx, OgmCodec.hex(frames[idx]))
            active[idx] = slot
            transmit(slot)
        }
    }
    fun onQueued(frameHex: String, session: String, packetId: String): Boolean {
        if (terminated || paused) return false
        val slot = active.values.firstOrNull { it.hex.equals(frameHex, ignoreCase = true) } ?: return false
        slot.radioKeys.add("$session:$packetId")
        arm(slot, 16_000L)
        return true
    }
    fun onDelivered(session: String, packetId: String): Boolean {
        if (terminated) return false
        val key = "$session:$packetId"
        val slot = active.values.firstOrNull { key in it.radioKeys } ?: return false
        slot.timeout?.let(cancel)
        active.remove(slot.index)
        acknowledged++
        onProgress(acknowledged, (nowMs() - startedAt).coerceAtLeast(1))
        pump()
        return true
    }
    fun onFailed(session: String, packetId: String): Boolean {
        if (terminated) return false
        val key = "$session:$packetId"
        val slot = active.values.firstOrNull { key in it.radioKeys } ?: return false
        slot.radioKeys.remove(key)
        // Another radio attempt is still pending: wait for its receipt.
        if (slot.radioKeys.isEmpty()) retry(slot, "Radio reported failed")
        return true
    }
}

/** ESP32 already supports APPACKB, used by the approved 1.6.1 web client.
 * Batch only OGB5 media radio ACKs. OGC4 text/controls keep instant APPACK.
 */
class RadioMediaAckBatcher(
    private val schedule: (Runnable, Long) -> Unit,
    private val cancel: (Runnable) -> Unit,
    private val send: (String) -> Unit,
    private val flushMs: Long = 65L,
    private val maxIds: Int = 4
) {
    init { require(maxIds in 1..6 && flushMs in 1..1_000) }
    private data class Group(val mac: String, val session: String,
                             val ids: LinkedHashSet<String> = linkedSetOf(),
                             var alarm: Runnable? = null)
    private val groups = linkedMapOf<String, Group>()
    fun accept(mac: String, session: String, packetId: String) {
        // These fields originate from the ESP32's authenticated RX record.
        if (!Regex("[0-9A-Fa-f:]{17}").matches(mac) ||
            session.toULongOrNull() == null || packetId.toULongOrNull() == null) return
        val key = "$mac:$session"
        val g = groups.getOrPut(key) {
            val value = Group(mac, session)
            val alarm = Runnable { flush(key) }
            value.alarm = alarm
            schedule(alarm, flushMs)
            value
        }
        g.ids.add(packetId)
        if (g.ids.size >= maxIds) flush(key)
    }
    fun flush(key: String) {
        val g = groups.remove(key) ?: return
        g.alarm?.let(cancel)
        if (g.ids.isNotEmpty()) send("APPACKB ${g.mac} ${g.session} ${g.ids.joinToString(" ")}")
    }
    fun clear() {
        groups.values.forEach { g -> g.alarm?.let(cancel) }
        groups.clear()
    }
}
