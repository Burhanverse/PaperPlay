package io.max.paperplay.ui

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider

actual fun clockNow(): ClockParts {
    val t = java.time.LocalDateTime.now()
    return ClockParts(t.hour, t.minute, t.second, t.format(java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM")))
}

@Composable
actual fun StandbyWindowEffect(brightness: Float) {
    val view = LocalView.current
    val ctx = LocalContext.current
    DisposableEffect(brightness) {
        val activity = generateSequence(ctx) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<Activity>().firstOrNull()
        val windows = listOfNotNull((view.parent as? DialogWindowProvider)?.window, activity?.window)
        val level = if (brightness >= 1f) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else brightness.coerceIn(0.01f, 1f)
        windows.forEach { w ->
            w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            w.attributes = w.attributes.also {
                it.screenBrightness = level
                // Full screen: draw under the camera cutout too.
                if (android.os.Build.VERSION.SDK_INT >= 28) it.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            // No status or navigation bar; a swipe from the edge shows them for a moment.
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(w, false)
            androidx.core.view.WindowCompat.getInsetsController(w, w.decorView).also { c ->
                c.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                c.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            // The activity's window first, each step on its own: if the dialog window is already gone and one step throws, the screen must still be allowed to turn off.
            // A flag left on the activity window kept the screen awake for the whole app.
            val last = activity?.window
            val order = listOfNotNull(last) + windows.filter { it !== last }
            order.forEach { w ->
                runCatching { w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                runCatching { w.attributes = w.attributes.also { it.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE } }
                runCatching { androidx.core.view.WindowCompat.getInsetsController(w, w.decorView).show(androidx.core.view.WindowInsetsCompat.Type.systemBars()) }
            }
        }
    }
}

@Composable
actual fun StandbyOrientation(on: Boolean) {
    val ctx = LocalContext.current
    DisposableEffect(on) {
        val activity = generateSequence(ctx) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<Activity>().firstOrNull()
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? android.hardware.SensorManager
        val sensor = sm?.getDefaultSensor(android.hardware.Sensor.TYPE_GRAVITY) ?: sm?.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)
        if (!on || activity == null || sm == null || sensor == null) return@DisposableEffect onDispose {}
        val before = activity.requestedOrientation
        val sides = intArrayOf(
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            // On its left edge the top points left: the phone's own landscape. It was the other way round, so the screen turned upside down.
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT,
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
        )
        var current = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        var candidate = current
        var since = 0L
        val listener = object : android.hardware.SensorEventListener {
            override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) {}
            override fun onSensorChanged(e: android.hardware.SensorEvent) {
                // Reclined on a stand or a pillow counts too; only lying almost flat keeps what it is.
                val side = io.max.paperplay.ui.gravitySide(e.values[0], e.values[1], sides.indexOf(current).takeIf { it >= 0 }) ?: return
                val want = sides[side]
                val now = e.timestamp / 1_000_000L
                if (want != candidate) { candidate = want; since = now; return }
                // Held for a third of a second, so a jolt does not turn the screen.
                if (want != current && now - since >= 350) {
                    current = want
                    runCatching { activity.requestedOrientation = want }
                }
            }
        }
        sm.registerListener(listener, sensor, android.hardware.SensorManager.SENSOR_DELAY_NORMAL)
        onDispose {
            runCatching { sm.unregisterListener(listener) }
            runCatching { activity.requestedOrientation = before }
        }
    }
}

@Composable
actual fun rememberBatteryPercent(): Int? {
    val ctx = LocalContext.current
    var pct by remember { mutableStateOf<Int?>(null) }
    DisposableEffect(ctx) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
                if (level >= 0 && scale > 0) pct = level * 100 / scale
            }
        }
        val sticky = ctx.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        receiver.onReceive(ctx, sticky)
        onDispose { runCatching { ctx.unregisterReceiver(receiver) } }
    }
    return pct
}

@Composable
actual fun rememberOutputDevice(): OutputDevice {
    val ctx = LocalContext.current
    val am = remember(ctx) { ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager }
    fun read(): OutputDevice {
        val outs = am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
        val ext = outs.firstOrNull {
            it.type in setOf(
                android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, android.media.AudioDeviceInfo.TYPE_BLE_HEADSET,
                android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES, android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET,
                android.media.AudioDeviceInfo.TYPE_USB_HEADSET, android.media.AudioDeviceInfo.TYPE_USB_DEVICE,
            )
        }
        return if (ext != null) OutputDevice(ext.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Headphones", true)
        else OutputDevice(android.os.Build.MODEL ?: "This phone", false)
    }
    var device by remember { mutableStateOf(read()) }
    DisposableEffect(am) {
        val cb = object : android.media.AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out android.media.AudioDeviceInfo>?) { device = read() }
            override fun onAudioDevicesRemoved(removed: Array<out android.media.AudioDeviceInfo>?) { device = read() }
        }
        am.registerAudioDeviceCallback(cb, null)
        onDispose { am.unregisterAudioDeviceCallback(cb) }
    }
    return device
}

@Composable
actual fun rememberOutputSwitcher(): (() -> Unit)? {
    val ctx = LocalContext.current
    return remember(ctx) {
        {
            // Android 14 has a proper call; older versions have the Settings panel, then plain Bluetooth settings.
            val shown = android.os.Build.VERSION.SDK_INT >= 34 && runCatching { android.media.MediaRouter2.getInstance(ctx).showSystemOutputSwitcher() }.getOrDefault(false)
            if (!shown) {
                val panel = Intent("com.android.settings.panel.action.MEDIA_OUTPUT")
                    .putExtra("com.android.settings.panel.extra.PACKAGE_NAME", ctx.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { ctx.startActivity(panel) }.onFailure {
                    runCatching { ctx.startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
            }
        }
    }
}
