package io.max.paperplay.player.usb

import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import io.max.paperplay.player.PlayLog

/**
 * Passive Android power, Doze, thermal, and app standby bucket telemetry for USB audio diagnostics.
 * Zero USB device opens, zero native library loads (safe to run even when Bit-perfect is OFF).
 */
object UsbPowerTelemetry {
    private const val MAX_EVENTS = 512

    private val lock = Any()
    private val events = ArrayDeque<String>(MAX_EVENTS)

    @Volatile
    private var started = false

    @Volatile
    private var appContext: Context? = null

    fun start(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        synchronized(lock) {
            if (started) return
            started = true
        }
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
        record(ctx, "INIT", buildSnapshot(ctx, pm))

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val power = c.getSystemService(Context.POWER_SERVICE) as? PowerManager
                val kind = when (intent.action) {
                    Intent.ACTION_SCREEN_ON -> "SCREEN_ON"
                    Intent.ACTION_SCREEN_OFF -> "SCREEN_OFF"
                    PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> "DOZE_CHANGED"
                    PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> "POWER_SAVE_CHANGED"
                    else -> intent.action?.substringAfterLast('.') ?: "POWER_EVENT"
                }
                record(c, kind, buildSnapshot(c, power))
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                ctx.registerReceiver(receiver, filter)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && pm != null) {
            runCatching {
                pm.addThermalStatusListener { status ->
                    record(ctx, "THERMAL_CHANGED", "thermal=${thermalStatusName(status)}($status) ${buildSnapshot(ctx, pm)}")
                }
            }
        }
    }

    fun recordCustom(kind: String, detail: String) {
        val ctx = appContext
        val line = formatLine(kind, detail)
        appendLine(line)
        UsbRingLog.record("PowerTelemetry", "$kind: $detail", UsbRingLog.CODE_POWER_EVENT)
        PlayLog.log("usb", "POWER_TELEMETRY $kind: $detail")
        if (ctx != null && kind == "SNAPSHOT") {
            // no-op extra
        }
    }

    private fun record(context: Context, kind: String, detail: String) {
        val line = formatLine(kind, detail)
        appendLine(line)
        UsbRingLog.record("PowerTelemetry", "$kind: $detail", UsbRingLog.CODE_POWER_EVENT)
        PlayLog.log("usb", "POWER_TELEMETRY $kind: $detail")
    }

    private fun formatLine(kind: String, detail: String): String {
        val wallMs = System.currentTimeMillis()
        val monoMs = SystemClock.elapsedRealtime()
        return "@${wallMs}ms (mono=${monoMs}ms) $kind: $detail"
    }

    private fun appendLine(line: String) {
        synchronized(lock) {
            if (events.size >= MAX_EVENTS) {
                events.removeFirst()
            }
            events.addLast(line)
        }
    }

    fun snapshotSummary(context: Context): String {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return buildSnapshot(context, pm)
    }

    fun dumpEvents(): List<String> = synchronized(lock) { events.toList() }

    private fun buildSnapshot(context: Context, pm: PowerManager?): String {
        val interactive = pm?.isInteractive ?: true
        val doze = pm?.isDeviceIdleMode ?: false
        val powerSave = pm?.isPowerSaveMode ?: false
        val ignoringOpt = runCatching { pm?.isIgnoringBatteryOptimizations(context.packageName) ?: false }.getOrDefault(false)
        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && pm != null) {
            val s = runCatching { pm.currentThermalStatus }.getOrDefault(-1)
            "${thermalStatusName(s)}($s)"
        } else {
            "n/a"
        }
        val bucket = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            val b = runCatching { usm?.appStandbyBucket ?: -1 }.getOrDefault(-1)
            "${standbyBucketName(b)}($b)"
        } else {
            "n/a"
        }
        return "interactive=$interactive doze=$doze powerSave=$powerSave thermal=$thermal standbyBucket=$bucket ignoringBatteryOpt=$ignoringOpt"
    }

    internal fun thermalStatusName(status: Int): String = when (status) {
        0 -> "NONE"
        1 -> "LIGHT"
        2 -> "MODERATE"
        3 -> "SEVERE"
        4 -> "CRITICAL"
        5 -> "EMERGENCY"
        6 -> "SHUTDOWN"
        else -> "UNKNOWN"
    }

    internal fun standbyBucketName(bucket: Int): String = when (bucket) {
        5 -> "EXEMPTED"
        10 -> "ACTIVE"
        20 -> "WORKING_SET"
        30 -> "FREQUENT"
        40 -> "RARE"
        45 -> "RESTRICTED"
        50 -> "NEVER"
        else -> "UNKNOWN"
    }
}
