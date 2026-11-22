package io.max.paperplay.player.usb

import android.content.Context
import android.media.AudioAttributes as PlatformAudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.media.AudioTrack
import android.os.Build
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.media3.common.AudioAttributes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import io.max.paperplay.player.PlayLog
import io.max.paperplay.player.PlaybackCore
import kotlin.math.abs

/**
 * Android 14+ (API 34+) `MIXER_BEHAVIOR_BIT_PERFECT` host adapter.
 * Always gated behind `PlaybackCore.devApi34MixerEnabled` and all 5 gates in [Api34BitPerfectMixerPolicy].
 */
@OptIn(UnstableApi::class)
internal object Api34BitPerfectMixer {
    @Volatile
    var lastVerification: Api34MixerVerification? = null
        private set

    @Volatile
    var lastDeviceName: String? = null
        private set

    fun clear(context: Context) {
        lastVerification = null
        lastDeviceName = null
        if (Build.VERSION.SDK_INT >= 34) {
            Api34Compat.clearPreferred(context.applicationContext)
        }
    }

    /**
     * Builds an integer-only, zero-DSP [DefaultAudioSink] configured with API 34 bit-perfect mixer
     * attributes, or returns null (failing closed) if Gates 1–4 cannot be satisfied.
     */
    fun buildVerifiedSinkOrNull(
        context: Context,
        enableAudioOutputPlaybackParams: Boolean,
    ): AudioSink? {
        if (!PlaybackCore.useUnifiedBitPerfect || !PlaybackCore.devApi34MixerEnabled || !PlaybackCore.usbExclusiveMode) {
            lastVerification = null
            return null
        }
        if (Build.VERSION.SDK_INT < 34) {
            lastVerification = Api34MixerVerification.Rejected(1, "SDK ${Build.VERSION.SDK_INT} < 34")
            return null
        }
        val appCtx = context.applicationContext
        val dev = Api34Compat.findUsbOutputDevice(appCtx)
        if (dev == null) {
            lastVerification = Api34MixerVerification.Rejected(1, "No USB audio output device active")
            return null
        }
        lastDeviceName = dev.productName?.toString()?.takeIf { it.isNotBlank() } ?: "USB DAC"
        val allowEq = PlaybackCore.allowEqInUsbExclusive && hasOnlyPermittedDirectEq()
        if (!isDspAndEffectsBypassed() && !allowEq) {
            lastVerification = Api34MixerVerification.Rejected(4, "DSP or effects active")
            return null
        }

        val supported = Api34Compat.querySupportedOptions(appCtx, dev)
        if (supported.none { it.isBitPerfectBehavior && Api34BitPerfectMixerPolicy.isSupportedIntEncoding(it.encoding) }) {
            lastVerification = Api34MixerVerification.Rejected(2, "No integer bit-perfect mixer options listed")
            return null
        }

        val provider = object : DefaultAudioSink.AudioTrackProvider {
            override fun getAudioTrack(
                audioTrackConfig: AudioSink.AudioTrackConfig,
                audioAttributes: AudioAttributes,
                audioSessionId: Int,
                context: Context?,
            ): AudioTrack {
                val ctx = (context ?: appCtx)
                val reqRate = audioTrackConfig.sampleRate
                val reqChannels = channelCountFromMask(audioTrackConfig.channelConfig)
                val probe = Api34Compat.applyPreferredAndReadback(ctx, dev, supported, reqRate, reqChannels)
                val track = DefaultAudioSink.AudioTrackProvider.DEFAULT.getAudioTrack(
                    audioTrackConfig,
                    audioAttributes,
                    audioSessionId,
                    context,
                )
                val result = Api34BitPerfectMixerPolicy.verifyAllFiveGates(
                    Api34MixerProbeInput(
                        devFlagEnabled = PlaybackCore.devApi34MixerEnabled,
                        sdkInt = Build.VERSION.SDK_INT,
                        hasUsbAudioOutputDevice = true,
                        supportedOptions = supported,
                        requestedRateHz = reqRate,
                        requestedChannels = reqChannels,
                        setPreferredSucceeded = probe.setOk,
                        readbackOption = probe.readback,
                        floatOutputDisabled = true,
                        dspAndEffectsBypassed = isDspAndEffectsBypassed(),
                        audioTrackEncoding = track.audioFormat,
                        audioTrackRateHz = track.sampleRate,
                        audioTrackChannels = track.channelCount,
                        allowEqInDirectMixer = PlaybackCore.allowEqInUsbExclusive && hasOnlyPermittedDirectEq(),
                    ),
                )
                lastVerification = result
                when (result) {
                    is Api34MixerVerification.Verified -> PlayLog.log(
                        "usb",
                        "Api34BitPerfectMixer VERIFIED${if (result.eqActive) " (USB Direct + EQ)" else ""}: ${result.sampleRateHz} Hz / ${result.bitDepth}-bit (enc=${result.encoding}) on ${lastDeviceName ?: "USB"}",
                    )
                    is Api34MixerVerification.Rejected -> PlayLog.log(
                        "usb",
                        "Api34BitPerfectMixer rejected at gate ${result.failedGate}: ${result.reason}",
                    )
                }
                return track
            }
        }

        return DefaultAudioSink.Builder(appCtx)
            .setEnableFloatOutput(false)
            .setEnableAudioTrackPlaybackParams(enableAudioOutputPlaybackParams)
            .setAudioProcessors(arrayOf(io.max.paperplay.player.TapProcessor(enableDirectPcmEq = true)))
            .setAudioTrackProvider(provider)
            .build()
    }

    fun hasOnlyPermittedDirectEq(): Boolean {
        if (PlaybackCore.bassDb >= 0.05f || PlaybackCore.correction != null || PlaybackCore.options != 0) return false
        if (PlaybackCore.normalize || abs(PlaybackCore.speed - 1f) > 0.001f || abs(PlaybackCore.width - 1f) > 0.005f) return false
        return true
    }

    fun isDspAndEffectsBypassed(): Boolean {
        if (PlaybackCore.engineActive || PlaybackCore.usbEqActive) return false
        if (PlaybackCore.parametricEnabled && PlaybackCore.parametricEq?.hasActiveShaping() == true) return false
        if (PlaybackCore.eqGains?.any { abs(it) >= 0.05f } == true) return false
        if (PlaybackCore.bassDb >= 0.05f || PlaybackCore.correction != null || PlaybackCore.options != 0) return false
        if (PlaybackCore.normalize || abs(PlaybackCore.speed - 1f) > 0.001f || abs(PlaybackCore.width - 1f) > 0.005f) return false
        return true
    }

    private fun channelCountFromMask(channelMask: Int): Int =
        Integer.bitCount(channelMask).coerceAtLeast(2)

    @RequiresApi(34)
    private object Api34Compat {
        data class ApplyResult(val setOk: Boolean, val readback: Api34MixerOption?)

        private val mediaAttrs = PlatformAudioAttributes.Builder()
            .setUsage(PlatformAudioAttributes.USAGE_MEDIA)
            .setContentType(PlatformAudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        fun findUsbOutputDevice(context: Context): AudioDeviceInfo? {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
                it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
            }
        }

        fun querySupportedOptions(context: Context, dev: AudioDeviceInfo): List<Api34MixerOption> {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            return runCatching {
                am.getSupportedMixerAttributes(dev).map { attr ->
                    Api34MixerOption(
                        encoding = attr.format.encoding,
                        sampleRateHz = attr.format.sampleRate,
                        channels = attr.format.channelCount,
                        isBitPerfectBehavior = attr.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT,
                    )
                }
            }.getOrDefault(emptyList())
        }

        fun applyPreferredAndReadback(
            context: Context,
            dev: AudioDeviceInfo,
            supported: List<Api34MixerOption>,
            rateHz: Int,
            channels: Int,
        ): ApplyResult {
            val best = Api34BitPerfectMixerPolicy.selectBestOption(supported, rateHz, channels)
                ?: return ApplyResult(setOk = false, readback = null)
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val mask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
            val fmt = AudioFormat.Builder()
                .setEncoding(best.encoding)
                .setSampleRate(rateHz)
                .setChannelMask(mask)
                .build()
            val preferred = AudioMixerAttributes.Builder(fmt)
                .setMixerBehavior(AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT)
                .build()
            val setOk = runCatching { am.setPreferredMixerAttributes(mediaAttrs, dev, preferred) }.getOrDefault(false)
            val rb = runCatching { am.getPreferredMixerAttributes(mediaAttrs, dev) }.getOrNull()?.let { attr ->
                Api34MixerOption(
                    encoding = attr.format.encoding,
                    sampleRateHz = attr.format.sampleRate,
                    channels = attr.format.channelCount,
                    isBitPerfectBehavior = attr.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT,
                )
            }
            return ApplyResult(setOk = setOk, readback = rb)
        }

        fun clearPreferred(context: Context) {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val dev = findUsbOutputDevice(context) ?: return
            runCatching { am.clearPreferredMixerAttributes(mediaAttrs, dev) }
        }
    }
}
