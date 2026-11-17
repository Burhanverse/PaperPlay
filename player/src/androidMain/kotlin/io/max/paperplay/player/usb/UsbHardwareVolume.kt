package io.max.paperplay.player.usb

import android.hardware.usb.UsbDeviceConnection
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import io.max.paperplay.player.PlayLog
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Hardware volume and mute controller for USB Audio (UAC 1.0 & UAC 2.0).
 *
 * Implements Tier 1 hardware volume strictly via USB Audio Class Feature Unit requests
 * on Endpoint 0 (SET_CUR with GET_CUR readback verification).
 *
 * Samples are NEVER scaled in software. If hardware volume fails or is unsupported,
 * software attenuation is NOT applied, preserving bit-perfection.
 *
 * Note: Volume parameters are initialized directly from native [DacCapabilities]
 * without executing un-claimed control transfers on Java [UsbDeviceConnection].
 */
class UsbHardwareVolume(
    private val connection: UsbDeviceConnection? = null,
    private val uacVersion: UacVersion,
    private val controlInterfaceId: Int,
    private val featureUnit: UsbFeatureUnit,
    caps: DacCapabilities? = null,
    private val nativeHandleProvider: (() -> Long)? = null
) {
    var isSupported: Boolean = false
        private set

    var minRaw: Short = -0x4000 // approx -64 dB default (-64 * 256)
        private set
    var maxRaw: Short = 0       // 0 dB default
        private set
    var resRaw: Short = 0x0100  // 1 dB default (256 steps per dB)
        private set

    var currentVolume: Float = 1.0f
        private set
    var isMuted: Boolean = false
        private set

    private var lastAppliedRawVolume: Int? = null
    var lastAppliedIndex: Int? = null
        private set
    var lastAppliedDb: Float? = null
        private set

    fun invalidateVolumeCache() {
        lastAppliedRawVolume = null
        lastAppliedIndex = null
        lastAppliedDb = null
    }

    fun resetAppliedVolume() {
        invalidateVolumeCache()
    }

    init {
        val hasFuVol = featureUnit.hasMasterVolume || featureUnit.hasChannelVolume
        if (caps != null && caps.hasVolume) {
            minRaw = (caps.volumeMinDb * 256.0f).roundToInt().toShort()
            maxRaw = (caps.volumeMaxDb * 256.0f).roundToInt().toShort()
            resRaw = (caps.volumeResDb * 256.0f).roundToInt().coerceAtLeast(1).toShort()
            isSupported = hasFuVol
            currentVolume = 1.0f
            PlayLog.log(
                "usb",
                "Hardware volume initialized from DacCapabilities: min=$minRaw (${caps.volumeMinDb} dB), max=$maxRaw (${caps.volumeMaxDb} dB), res=$resRaw (${caps.volumeResDb} dB), supported=$isSupported"
            )
        } else if (hasFuVol) {
            isSupported = true
            minRaw = -0x4000 // -64 dB default
            maxRaw = 0       // 0 dB default
            resRaw = 0x0080  // 0.5 dB default
            currentVolume = 1.0f
            PlayLog.log("usb", "Hardware volume initialized with defaults: supported=$isSupported (FU hasVol=$hasFuVol)")
        } else {
            isSupported = false
            PlayLog.log("usb", "Feature unit ${featureUnit.id} does not support volume control")
        }
    }

    /**
     * Sets hardware volume on the DAC and verifies with GET_CUR readback.
     * [volume] is in 0.0f..1.0f.
     * Maps linearly perceived loudness using a square-root perceptual curve across the
     * DAC's queried range, clamped at 0 dB (0x0000) max to prevent gain clipping.
     * Mutes the Feature Unit when [volume] <= 0.001f.
     */
    fun setVolume(volume: Float, isUser: Boolean = false, force: Boolean = false): Boolean {
        if (!isSupported) return false
        val clamped = volume.coerceIn(0f, 1f)

        val effectiveMaxRaw = maxRaw.toInt().coerceAtMost(0).toShort()
        val span = (effectiveMaxRaw - minRaw).toInt()
        val step = resRaw.toInt().coerceAtLeast(1)

        val channels = if (featureUnit.hasMasterVolume) listOf(0) else listOf(1, 2)
        val handle = nativeHandleProvider?.invoke() ?: 0L
        val reason = if (isUser) "user" else "auto"

        if (clamped <= 0.001f) {
            setMute(true)
            val targetRaw = minRaw
            if (!force && lastAppliedRawVolume == targetRaw.toInt()) {
                currentVolume = 0f
                return true
            }
            if (handle == 0L) {
                currentVolume = 0f
                return false
            }
            for (ch in channels) {
                UsbRingLog.record(
                    "UsbHardwareVolume",
                    "SET_CUR_VOL ch=$ch raw=$targetRaw (${minRaw.toFloat() / 256f} dB) reason=$reason force=$force",
                    UsbRingLog.CODE_SET_CUR_VOL,
                    ch.toLong(),
                    targetRaw.toLong()
                )
                runCatching {
                    UsbEngineNative.setHardwareVolume(
                        handle = handle,
                        featureUnitId = featureUnit.id,
                        controlIface = controlInterfaceId,
                        channel = ch,
                        volumeRaw = targetRaw.toInt()
                    )
                }
            }
            lastAppliedRawVolume = targetRaw.toInt()
            lastAppliedIndex = 0
            lastAppliedDb = minRaw.toFloat() / 256f
            currentVolume = 0f
            return true
        }

        if (isMuted || force) {
            setMute(false)
        }

        val curved = kotlin.math.sqrt(clamped.toDouble()).toFloat()
        val raw = minRaw.toInt() + (curved * span).roundToInt()
        val targetRaw = (minRaw.toInt() + ((raw - minRaw.toInt()) / step) * step)
            .coerceIn(minRaw.toInt(), effectiveMaxRaw.toInt()).toShort()

        if (!force && lastAppliedRawVolume == targetRaw.toInt() && !isMuted) {
            currentVolume = clamped
            return true
        }

        if (handle == 0L) {
            currentVolume = clamped
            return false
        }

        var allOk = true

        for (ch in channels) {
            val db = targetRaw.toFloat() / 256f
            UsbRingLog.record(
                "UsbHardwareVolume",
                "SET_CUR_VOL ch=$ch raw=$targetRaw ($db dB) reason=$reason force=$force",
                UsbRingLog.CODE_SET_CUR_VOL,
                ch.toLong(),
                targetRaw.toLong()
            )
            PlayLog.log("usb", "SET_CUR_VOL ch=$ch raw=$targetRaw ($db dB) reason=$reason force=$force")
            val res = runCatching {
                UsbEngineNative.setHardwareVolume(
                    handle = handle,
                    featureUnitId = featureUnit.id,
                    controlIface = controlInterfaceId,
                    channel = ch,
                    volumeRaw = targetRaw.toInt()
                )
            }.getOrDefault(-1)

            if (res < 0) {
                PlayLog.log("usb", "[VolFlow] Hardware volume SET_CUR failed for ch $ch: code $res")
                allOk = false
                continue
            }

            val readRaw = res.toShort()
            UsbRingLog.recordAudio(UsbRingLog.CODE_GET_CUR_VOL, ch.toLong(), readRaw.toLong())
            val diff = abs(readRaw.toInt() - targetRaw.toInt())
            if (diff > step * 2) {
                PlayLog.log("usb", "[VolFlow] Hardware volume mismatch on ch $ch: target $targetRaw, read back $readRaw")
                allOk = false
            }
        }

        lastAppliedRawVolume = targetRaw.toInt()
        lastAppliedDb = targetRaw.toFloat() / 256f
        if (allOk) {
            currentVolume = clamped
        }
        return allOk
    }

    /**
     * Sets hardware volume based on Android's system [AudioManager.STREAM_MUSIC] index.
     * Uses a single fixed perceptual curve across the queried hardware dB range ending at 0 dB max.
     * Includes safety slew limiter: automatic increases > 1 curve step are clamped to lastApplied + 1 step.
     */
    fun setVolumeFromSystem(
        audioManager: AudioManager,
        index: Int,
        maxIndex: Int,
        isUser: Boolean = false,
        force: Boolean = false
    ): Boolean {
        if (!isSupported) return false
        if (maxIndex <= 0) return false

        val channels = if (featureUnit.hasMasterVolume) listOf(0) else listOf(1, 2)
        val handle = nativeHandleProvider?.invoke() ?: 0L
        val effectiveMaxRaw = maxRaw.toInt().coerceAtMost(0).toShort()
        val step = resRaw.toInt().coerceAtLeast(1)
        val reason = if (isUser) "user" else "auto"

        if (index <= 0) {
            setMute(true)
            val targetRaw = minRaw
            if (!force && lastAppliedRawVolume == targetRaw.toInt()) {
                currentVolume = 0f
                return true
            }
            if (handle == 0L) {
                currentVolume = 0f
                return false
            }
            for (ch in channels) {
                UsbRingLog.record(
                    "UsbHardwareVolume",
                    "SET_CUR_VOL ch=$ch raw=$targetRaw (${minRaw.toFloat() / 256f} dB) reason=$reason force=$force",
                    UsbRingLog.CODE_SET_CUR_VOL,
                    ch.toLong(),
                    targetRaw.toLong()
                )
                runCatching {
                    UsbEngineNative.setHardwareVolume(
                        handle = handle,
                        featureUnitId = featureUnit.id,
                        controlIface = controlInterfaceId,
                        channel = ch,
                        volumeRaw = targetRaw.toInt()
                    )
                }
            }
            lastAppliedRawVolume = targetRaw.toInt()
            lastAppliedIndex = 0
            lastAppliedDb = minRaw.toFloat() / 256f
            currentVolume = 0f
            return true
        }

        if (isMuted || force) {
            setMute(false)
        }

        // Safety slew limiter: an automatic (non-user) increase of more than one curve step
        // is clamped to last applied value + 1 step. User actions apply as-is.
        val effectiveIndex = UsbVolumeCurve.applySlewLimiter(
            requestedIndex = index,
            lastAppliedIndex = lastAppliedIndex,
            maxIndex = maxIndex,
            isUser = isUser
        )
        if (effectiveIndex != index) {
            val msg = "Slew limiter clamped auto volume increase: index $index -> $effectiveIndex (lastApplied=$lastAppliedIndex)"
            PlayLog.log("usb", msg)
            UsbRingLog.record("UsbHardwareVolume", msg, UsbRingLog.CODE_GENERIC)
        }

        val minDb = minRaw.toFloat() / 256.0f
        val maxDb = effectiveMaxRaw.toFloat() / 256.0f
        val resDb = resRaw.toFloat() / 256.0f

        val targetDb = UsbVolumeCurve.computeVolumeDb(
            index = effectiveIndex,
            maxIndex = maxIndex,
            dacMinDb = minDb,
            dacMaxDb = maxDb,
            dacResDb = resDb
        )

        val targetRaw: Short = UsbVolumeCurve.dbToRaw(
            db = targetDb,
            minRaw = minRaw,
            maxRaw = effectiveMaxRaw,
            resRaw = resRaw
        )

        UsbRingLog.record(
            "UsbHardwareVolume",
            "setVolumeFromSystem: idx=$effectiveIndex/$maxIndex (req=$index isUser=$isUser force=$force) targetDb=$targetDb dB dacMin=$minDb dacMax=$maxDb dacRes=$resDb targetRaw=$targetRaw (${targetRaw.toFloat() / 256f} dB)",
            UsbRingLog.CODE_GENERIC
        )

        if (!force && lastAppliedRawVolume == targetRaw.toInt() && !isMuted) {
            currentVolume = (effectiveIndex.toFloat() / maxIndex.toFloat()).coerceIn(0f, 1f)
            lastAppliedIndex = effectiveIndex
            lastAppliedDb = targetDb
            return true
        }

        if (handle == 0L) {
            currentVolume = (effectiveIndex.toFloat() / maxIndex.toFloat()).coerceIn(0f, 1f)
            return false
        }

        var allOk = true
        for (ch in channels) {
            UsbRingLog.record(
                "UsbHardwareVolume",
                "SET_CUR_VOL ch=$ch raw=$targetRaw ($targetDb dB) reason=$reason force=$force",
                UsbRingLog.CODE_SET_CUR_VOL,
                ch.toLong(),
                targetRaw.toLong()
            )
            PlayLog.log("usb", "SET_CUR_VOL ch=$ch raw=$targetRaw ($targetDb dB) reason=$reason force=$force")
            val res = runCatching {
                UsbEngineNative.setHardwareVolume(
                    handle = handle,
                    featureUnitId = featureUnit.id,
                    controlIface = controlInterfaceId,
                    channel = ch,
                    volumeRaw = targetRaw.toInt()
                )
            }.getOrDefault(-1)

            if (res < 0) {
                PlayLog.log("usb", "[VolFlow] Hardware volume SET_CUR failed for ch $ch: code $res")
                allOk = false
                continue
            }

            val readRaw = res.toShort()
            UsbRingLog.recordAudio(UsbRingLog.CODE_GET_CUR_VOL, ch.toLong(), readRaw.toLong())
            val diff = abs(readRaw.toInt() - targetRaw.toInt())
            if (diff > step * 2) {
                PlayLog.log("usb", "[VolFlow] Hardware volume mismatch on ch $ch: target $targetRaw, read back $readRaw")
                allOk = false
            }
        }

        lastAppliedRawVolume = targetRaw.toInt()
        lastAppliedIndex = effectiveIndex
        lastAppliedDb = targetDb
        if (allOk) {
            currentVolume = (effectiveIndex.toFloat() / maxIndex.toFloat()).coerceIn(0f, 1f)
        }
        return allOk
    }

    /**
     * Sets hardware mute on the DAC and verifies with GET_CUR readback.
     */
    fun setMute(mute: Boolean): Boolean {
        if (!featureUnit.hasMasterMute && !isSupported) return false
        val channels = if (featureUnit.hasMasterMute) listOf(0) else listOf(1, 2)
        var allOk = true
        val handle = nativeHandleProvider?.invoke() ?: 0L

        if (handle != 0L) {
            for (ch in channels) {
                val res = runCatching {
                    UsbEngineNative.setHardwareMute(
                        handle = handle,
                        featureUnitId = featureUnit.id,
                        controlIface = controlInterfaceId,
                        channel = ch,
                        mute = mute
                    )
                }.getOrDefault(-1)

                if (res < 0) {
                    allOk = false
                    continue
                }

                val readMute = res != 0
                if (readMute != mute) {
                    allOk = false
                }
            }
        } else {
            // Never issue unclaimed control transfers on interface 0 when native session is not active.
            allOk = false
        }

        if (allOk) {
            isMuted = mute
        }
        return allOk
    }

    /**
     * Restores DAC Feature Unit to unmuted and 0 dB default volume before release, verifying with GET_CUR readback.
     * Only executes USB control transfers while the native session handle is active (interface 0 claimed).
     */
    fun restoreDefaultVolumeAndUnmute(): Boolean {
        if (!isSupported) return false
        val handle = nativeHandleProvider?.invoke() ?: 0L
        val channels = if (featureUnit.hasMasterVolume) listOf(0) else listOf(1, 2)
        var allOk = true

        if (handle != 0L) {
            // 1. Explicitly unmute hardware while interface 0 is claimed
            setMute(false)

            // 2. Set volume to default / 0 dB (0 raw) and verify with GET_CUR
            val defaultRaw: Short = 0
            for (ch in channels) {
                val res = runCatching {
                    UsbEngineNative.setHardwareVolume(
                        handle = handle,
                        featureUnitId = featureUnit.id,
                        controlIface = controlInterfaceId,
                        channel = ch,
                        volumeRaw = defaultRaw.toInt()
                    )
                }.getOrDefault(-1)

                val readRaw = res.toShort()
                val readDb = readRaw.toFloat() / 256.0f
                PlayLog.log("usb", "restoreDefaultVolume: ch=$ch set=0 dB ($defaultRaw raw), GET_CUR read back=$readDb dB ($readRaw raw)")
                UsbRingLog.record("UsbHardwareVolume", "restoreDefault: ch=$ch readback=$readDb dB", UsbRingLog.CODE_GENERIC)
                if (res < 0) allOk = false
            }
        } else {
            // Native session already restored volume before releasing interfaces; never touch unclaimed interface 0.
            isMuted = false
        }
        resetAppliedVolume()
        return allOk
    }
}
