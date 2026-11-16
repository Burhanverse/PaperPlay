package io.max.paperplay.compose

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import io.max.paperplay.player.PlayLog

/**
 * Writes the app's own and the system's events to the shared log: screen lifecycle, memory warnings, screen on/off, power,
 * battery, headphones, network and power saving, and a note when the main thread stops answering. Each is one short line,
 * and only changes are written, so it stays small. Nothing here keeps state beyond a few fields.
 */
internal object AppEvents {
    private var started = false

    fun start(app: Application) {
        if (started) return
        started = true
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            private fun e(a: Activity, what: String) = PlayLog.log("lifecycle", "${a.javaClass.simpleName} $what")
            override fun onActivityCreated(a: Activity, s: Bundle?) = e(a, if (s == null) "created" else "recreated")
            override fun onActivityStarted(a: Activity) = e(a, "started")
            override fun onActivityResumed(a: Activity) = e(a, "resumed")
            override fun onActivityPaused(a: Activity) = e(a, "paused")
            override fun onActivityStopped(a: Activity) = e(a, "stopped")
            override fun onActivitySaveInstanceState(a: Activity, s: Bundle) = e(a, "state saved")
            override fun onActivityDestroyed(a: Activity) = e(a, "destroyed" + if (a.isFinishing) " (finishing)" else "")
        })
        app.registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) = PlayLog.log("memory", "system asks to trim memory, level $level")
            override fun onLowMemory() = PlayLog.log("memory", "system is low on memory")
            override fun onConfigurationChanged(c: Configuration) =
                PlayLog.log("config", "orientation ${c.orientation}, ${c.screenWidthDp}x${c.screenHeightDp} dp, night ${c.uiMode and Configuration.UI_MODE_NIGHT_MASK}, locale ${c.locales.toLanguageTags()}")
        })

        val power = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        val actions = listOf(
            Intent.ACTION_SCREEN_ON, Intent.ACTION_SCREEN_OFF, Intent.ACTION_USER_PRESENT, Intent.ACTION_POWER_CONNECTED, Intent.ACTION_POWER_DISCONNECTED,
            Intent.ACTION_BATTERY_LOW, Intent.ACTION_BATTERY_OKAY, Intent.ACTION_HEADSET_PLUG, android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY,
            Intent.ACTION_AIRPLANE_MODE_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_LOCALE_CHANGED,
            PowerManager.ACTION_POWER_SAVE_MODE_CHANGED, PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED,
        )
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val extra = when (i.action) {
                    PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> " (power saving ${power.isPowerSaveMode})"
                    PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> " (doze ${power.isDeviceIdleMode})"
                    Intent.ACTION_HEADSET_PLUG -> " (state ${i.getIntExtra("state", -1)})"
                    Intent.ACTION_AIRPLANE_MODE_CHANGED -> " (on ${i.getBooleanExtra("state", false)})"
                    else -> ""
                }
                PlayLog.log("system", (i.action ?: "?").substringAfterLast('.') + extra)
            }
        }
        val filter = IntentFilter().also { f -> actions.forEach(f::addAction) }
        if (android.os.Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED) else app.registerReceiver(receiver, filter)

        // Battery level only when it moves by 5 points or the plug changes, not on every sample.
        var lastLevel = -100; var lastPlug = -1
        app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val lvl = i.getIntExtra("level", -1) * 100 / i.getIntExtra("scale", 100).coerceAtLeast(1)
                val plug = i.getIntExtra("plugged", 0)
                if (kotlin.math.abs(lvl - lastLevel) >= 5 || plug != lastPlug) {
                    lastLevel = lvl; lastPlug = plug
                    PlayLog.log("battery", "$lvl%, plugged $plug, temp ${i.getIntExtra("temperature", 0) / 10.0} C, status ${i.getIntExtra("status", 0)}")
                }
            }
        }, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        runCatching {
            val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            fun kind(c: NetworkCapabilities) =
                listOf(NetworkCapabilities.TRANSPORT_WIFI to "wifi", NetworkCapabilities.TRANSPORT_CELLULAR to "mobile", NetworkCapabilities.TRANSPORT_ETHERNET to "ethernet", NetworkCapabilities.TRANSPORT_VPN to "vpn")
                    .filter { c.hasTransport(it.first) }.joinToString("+") { it.second } +
                    (if (c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) ", internet ok" else ", no internet") +
                    (if (c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) "" else ", metered")
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(n: Network) = PlayLog.log("network", "connected")
                override fun onLost(n: Network) = PlayLog.log("network", "lost")
                override fun onCapabilitiesChanged(n: Network, c: NetworkCapabilities) = PlayLog.log("network", "changed: ${kind(c)}")
            })
        }

        // Main thread watchdog: a ping every 2 s; if it is not answered in 4 s the stack of the main thread is logged once per stall.
        val main = Handler(Looper.getMainLooper())
        Thread({
            var stalledSince = 0L
            while (true) {
                val sent = System.currentTimeMillis()
                val answered = java.util.concurrent.atomic.AtomicBoolean(false)
                main.post { answered.set(true) }
                Thread.sleep(2000)
                if (answered.get()) {
                    if (stalledSince != 0L) PlayLog.log("watchdog", "main thread answering again after ${System.currentTimeMillis() - stalledSince} ms")
                    stalledSince = 0L
                } else if (stalledSince == 0L && System.currentTimeMillis() - sent >= 2000) {
                    Thread.sleep(2000)
                    if (!answered.get()) {
                        stalledSince = sent
                        PlayLog.log("watchdog", "main thread not answering for 4 s, it is at:\n" + Looper.getMainLooper().thread.stackTrace.take(25).joinToString("\n") { "    at $it" })
                    }
                }
            }
        }, "event-watchdog").apply { isDaemon = true; start() }

        PlayLog.log("app", "events started, sdk ${android.os.Build.VERSION.SDK_INT}, ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
    }
}
