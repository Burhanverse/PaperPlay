package io.max.paperplay.player.usb

import io.max.paperplay.player.PlayLog
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * Diagnostic utility that captures exact integer bytes handed to the native USB ring buffer
 * for the first 30 seconds of playback, storing them in app cache for bit-perfect offline verification.
 * Zero overhead and zero allocations when idle.
 */
object UsbPcmDumper {
    @Volatile
    var isEnabled: Boolean = false

    @Volatile
    var isDumping: Boolean = false
        private set

    @Volatile
    var status: String = "Off"
        private set

    @Volatile
    var dumpFilePath: String? = null
        private set

    @Volatile
    private var targetDurationSec: Int = 30

    private val copyChunk = ByteArray(16384)
    private var outputStream: FileOutputStream? = null
    private var bytesWritten: Long = 0L
    private var maxBytes: Long = 0L

    fun enable(cacheDir: File? = null, durationSec: Int = 30) {
        closeStream()
        targetDurationSec = durationSec.coerceIn(1, 120)
        isEnabled = true
        status = "Armed: waiting for USB playback (${targetDurationSec}s)"
        dumpFilePath = null
        PlayLog.log("usb", "UsbPcmDumper armed for ${targetDurationSec}s PCM capture")
        UsbRingLog.record("UsbPcmDumper", "armed for ${targetDurationSec}s dump", UsbRingLog.CODE_GENERIC)
    }

    fun disable() {
        isEnabled = false
        finish("Disabled by user")
    }

    fun onStreamConfigured(cacheDir: File, sampleRate: Int, bitDepth: Int, channels: Int, subslotSize: Int) {
        if (!isEnabled) return
        closeStream()
        try {
            val dur = targetDurationSec
            val file = File(cacheDir, "dump_usb_${dur}s_${sampleRate}hz_${bitDepth}bit_${subslotSize}b.raw")
            dumpFilePath = file.absolutePath
            outputStream = FileOutputStream(file)
            bytesWritten = 0L
            maxBytes = dur.toLong() * sampleRate * channels * subslotSize
            isDumping = true
            status = "Dumping: 0 / ${dur}s (${file.name})"
            PlayLog.log("usb", "UsbPcmDumper started: target=$maxBytes bytes -> ${file.absolutePath}")
            UsbRingLog.record("UsbPcmDumper", "start dump: ${file.name} target=$maxBytes", UsbRingLog.CODE_GENERIC)
        } catch (t: Throwable) {
            PlayLog.log("usb", "UsbPcmDumper failed to open file: ${t.message}")
            status = "Error: ${t.message}"
            isDumping = false
            isEnabled = false
        }
    }

    fun onBytesWritten(buffer: ByteBuffer, offset: Int, length: Int) {
        if (!isDumping || length <= 0) return
        val os = outputStream ?: return
        try {
            val toWrite = minOf(length.toLong(), maxBytes - bytesWritten).toInt()
            if (toWrite <= 0) {
                finish("${targetDurationSec}s captured successfully")
                return
            }
            val dup = buffer.duplicate()
            dup.position(offset)
            dup.limit(offset + toWrite)
            var rem = toWrite
            while (rem > 0) {
                val n = minOf(rem, copyChunk.size)
                dup.get(copyChunk, 0, n)
                os.write(copyChunk, 0, n)
                rem -= n
            }
            bytesWritten += toWrite
            val sec = if (maxBytes > 0L) bytesWritten.toDouble() / (maxBytes.toDouble() / targetDurationSec) else 0.0
            status = "Dumping: ${sec.toInt()} / ${targetDurationSec}s (${bytesWritten / 1024} KB)"
            if (bytesWritten >= maxBytes) {
                finish("${targetDurationSec}s captured successfully ($bytesWritten bytes)")
            }
        } catch (t: Throwable) {
            PlayLog.log("usb", "UsbPcmDumper write error: ${t.message}")
            finish("Write error: ${t.message}")
        }
    }

    fun finishIfDumping(reason: String = "Stream closed") {
        if (isDumping || outputStream != null) {
            finish(reason)
        }
    }

    private fun finish(reason: String) {
        val f = dumpFilePath
        val count = bytesWritten
        closeStream()
        isDumping = false
        isEnabled = false
        status = if (f != null) "Complete: $count bytes -> ${f.substringAfterLast('/')}" else reason
        PlayLog.log("usb", "UsbPcmDumper finished: $reason ($count bytes)")
        UsbRingLog.record("UsbPcmDumper", "dump finished: $count bytes ($reason)", UsbRingLog.CODE_GENERIC)
    }

    fun closeStream() {
        try {
            outputStream?.flush()
            outputStream?.close()
        } catch (_: Throwable) {}
        outputStream = null
        isDumping = false
    }
}
