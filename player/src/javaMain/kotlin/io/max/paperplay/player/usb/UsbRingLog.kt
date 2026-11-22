package io.max.paperplay.player.usb

import java.util.concurrent.atomic.AtomicLong

/**
 * Preallocated, lock-free, zero-allocation in-memory ring log for USB exclusive mode.
 * Capacity is 2048 entries (~128 KB total memory footprint, strictly <= 256 KB).
 *
 * In the audio thread (handleBuffer, getCurrentPositionUs, hasPendingData, isEnded),
 * logging uses recordAudio(...) which writes only primitive fields into preallocated
 * slots through an atomic cursor. No heap allocations occur on the audio path.
 */
object UsbRingLog {
    const val CAPACITY = 8192
    private const val MASK = CAPACITY - 1

    const val CODE_GENERIC = 0
    const val CODE_PERMISSION_REQ = 1
    const val CODE_PERMISSION_RES = 2
    const val CODE_DEVICE_ATTACH = 3
    const val CODE_DEVICE_DETACH = 4
    const val CODE_CLAIM_IFACE = 10
    const val CODE_CLAIM_RES = 11
    const val CODE_RELEASE_IFACE = 12
    const val CODE_SET_ALT_SETTING = 13
    const val CODE_SET_CUR_RATE = 14
    const val CODE_GET_CUR_RATE = 15
    const val CODE_SET_CUR_VOL = 16
    const val CODE_GET_CUR_VOL = 17
    const val CODE_SINK_CONFIGURE = 20
    const val CODE_SINK_PLAY = 21
    const val CODE_SINK_PAUSE = 22
    const val CODE_SINK_FLUSH = 23
    const val CODE_SINK_SEEK = 24
    const val CODE_SINK_HANDLE_BUFFER = 25
    const val CODE_SINK_GET_POS = 26
    const val CODE_SINK_PENDING_DATA = 27
    const val CODE_SINK_IS_ENDED = 28
    const val CODE_SINK_FALLBACK = 29
    const val CODE_PERIODIC_STATS = 30
    const val CODE_CONTROL_REQ = 31
    const val CODE_SINK_UNDERRUN = 32
    const val CODE_POWER_EVENT = 33
    const val CODE_ERROR = 99

    class Entry {
        @Volatile var timestampMs: Long = 0L
        @Volatile var code: Int = 0
        @Volatile var arg1: Long = 0L
        @Volatile var arg2: Long = 0L
        @Volatile var tag: String? = null
        @Volatile var message: String? = null
    }

    private val cursor = AtomicLong(0L)
    private val entries = Array(CAPACITY) { Entry() }

    /**
     * Allocation-free write for high-frequency audio path threads.
     * Only primitive parameters and writes into existing Entry object.
     */
    fun recordAudio(code: Int, arg1: Long = 0L, arg2: Long = 0L) {
        val idx = (cursor.getAndIncrement() and MASK.toLong()).toInt()
        val entry = entries[idx]
        entry.timestampMs = System.currentTimeMillis()
        entry.arg1 = arg1
        entry.arg2 = arg2
        entry.tag = null
        entry.message = null
        entry.code = code
    }

    /**
     * Non-audio thread log helper for state changes, configuration, permissions, errors.
     */
    fun record(tag: String, message: String, code: Int = CODE_GENERIC, arg1: Long = 0L, arg2: Long = 0L) {
        val idx = (cursor.getAndIncrement() and MASK.toLong()).toInt()
        val entry = entries[idx]
        entry.timestampMs = System.currentTimeMillis()
        entry.arg1 = arg1
        entry.arg2 = arg2
        entry.tag = tag
        entry.message = message
        entry.code = code
    }

    fun clear() {
        cursor.set(0L)
        for (i in 0 until CAPACITY) {
            val e = entries[i]
            e.timestampMs = 0L
            e.code = 0
            e.arg1 = 0L
            e.arg2 = 0L
            e.tag = null
            e.message = null
        }
    }

    fun size(): Int {
        val current = cursor.get()
        return current.coerceAtMost(CAPACITY.toLong()).toInt()
    }

    fun dump(): List<String> {
        val current = cursor.get()
        val count = current.coerceAtMost(CAPACITY.toLong()).toInt()
        val start = if (current > CAPACITY) current - CAPACITY else 0L
        val result = ArrayList<String>(count)
        for (i in start until current) {
            val entry = entries[(i and MASK.toLong()).toInt()]
            if (entry.code == 0 && entry.tag == null && entry.message == null) continue
            result.add(formatEntry(entry))
        }
        return result
    }

    private fun formatEntry(entry: Entry): String {
        val ts = entry.timestampMs
        val tag = entry.tag ?: "AudioPath"
        val desc = when (entry.code) {
            CODE_PERMISSION_REQ -> "PERMISSION_REQ devId=${entry.arg1}"
            CODE_PERMISSION_RES -> "PERMISSION_RES granted=${entry.arg1 == 1L} devId=${entry.arg2}"
            CODE_DEVICE_ATTACH -> "DEVICE_ATTACH devId=${entry.arg1}"
            CODE_DEVICE_DETACH -> "DEVICE_DETACH devId=${entry.arg1}"
            CODE_CLAIM_IFACE -> "CLAIM_IFACE iface=${entry.arg1} attempt=${entry.arg2}"
            CODE_CLAIM_RES -> "CLAIM_RES iface=${entry.arg1} success=${entry.arg2 == 1L}"
            CODE_RELEASE_IFACE -> "RELEASE_IFACE iface=${entry.arg1}"
            CODE_SET_ALT_SETTING -> "SET_ALT alt=${entry.arg1} result=${entry.arg2}"
            CODE_SET_CUR_RATE -> "SET_CUR_RATE rate=${entry.arg1} result=${entry.arg2}"
            CODE_GET_CUR_RATE -> "GET_CUR_RATE rate=${entry.arg1} result=${entry.arg2}"
            CODE_SET_CUR_VOL -> "SET_CUR_VOL ch=${entry.arg1} vol=${entry.arg2}"
            CODE_GET_CUR_VOL -> "GET_CUR_VOL ch=${entry.arg1} res=${entry.arg2}"
            CODE_SINK_CONFIGURE -> "SINK_CONFIGURE rate=${entry.arg1} ch/enc=${entry.arg2}"
            CODE_SINK_PLAY -> "SINK_PLAY"
            CODE_SINK_PAUSE -> "SINK_PAUSE"
            CODE_SINK_FLUSH -> "SINK_FLUSH"
            CODE_SINK_SEEK -> "SINK_SEEK posUs=${entry.arg1}"
            CODE_SINK_HANDLE_BUFFER -> "SINK_HANDLE_BUF bytes=${entry.arg1} ptsUs=${entry.arg2}"
            CODE_SINK_GET_POS -> "SINK_GET_POS posUs=${entry.arg1}"
            CODE_SINK_PENDING_DATA -> "SINK_PENDING pending=${entry.arg1 == 1L} fillBytes=${entry.arg2}"
            CODE_SINK_IS_ENDED -> "SINK_IS_ENDED ended=${entry.arg1 == 1L}"
            CODE_SINK_FALLBACK -> "SINK_FALLBACK reason=${entry.message}"
            CODE_PERIODIC_STATS -> "PERIODIC_STATS framesConsumed=${entry.arg1} underruns=${entry.arg2}"
            CODE_CONTROL_REQ -> "CONTROL_REQ ${entry.message}"
            CODE_POWER_EVENT -> "POWER_EVENT ${entry.message}"
            CODE_ERROR -> "ERROR err=${entry.message} code=${entry.arg1}"
            else -> entry.message ?: "EVENT(${entry.code}) a1=${entry.arg1} a2=${entry.arg2}"
        }
        val extra = if (entry.message != null &&
            entry.code != CODE_GENERIC &&
            entry.code != CODE_SINK_FALLBACK &&
            entry.code != CODE_CONTROL_REQ &&
            entry.code != CODE_POWER_EVENT &&
            entry.code != CODE_ERROR
        ) {
            " [${entry.message}]"
        } else ""
        return "@${ts}ms [$tag] $desc$extra"
    }
}

