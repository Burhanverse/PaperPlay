package io.max.paperplay.ui

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.BatteryManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Feeds the optional standby alert icons. Only the app, the call flag and a count leave this class: notification text is never read.
 * The system binds it only after the user grants notification access, so with no grant it simply never runs.
 */
class StandbyNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() = refresh()
    override fun onNotificationPosted(sbn: StatusBarNotification?) = refresh()
    override fun onNotificationRemoved(sbn: StatusBarNotification?) = refresh()
    override fun onListenerDisconnected() { StandbyAlertState.alerts.value = emptyList() }

    private fun refresh() {
        val active = runCatching { activeNotifications }.getOrNull().orEmpty()
        val shown = active.filter { n ->
            val call = n.notification.category == Notification.CATEGORY_CALL
            n.packageName != packageName &&
                (call || !n.isOngoing) &&
                n.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0
        }
        shown.forEach { n ->
            if (!StandbyAlertState.icons.containsKey(n.packageName)) {
                val small = iconOf(n.notification)
                if (small != null) StandbyAlertState.icons[n.packageName] = small
                else appIconOf(n.packageName)?.let { StandbyAlertState.icons[n.packageName] = it; StandbyAlertState.colour.add(n.packageName) }
            }
        }
        // An app whose icon cannot be loaded is left out: no generic placeholder.
        StandbyAlertState.alerts.value = shown.filter { StandbyAlertState.icons.containsKey(it.packageName) }.groupBy { it.packageName }.map { (pkg, list) ->
            StandbyAlert(pkg, list.any { it.notification.category == Notification.CATEGORY_CALL }, list.size)
        }.sortedByDescending { it.call }
    }

    private fun appIconOf(pkg: String): Bitmap? = runCatching {
        val d = packageManager.getApplicationIcon(pkg)
        Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).also { d.setBounds(0, 0, 96, 96); d.draw(Canvas(it)) }
    }.getOrNull()

    private fun iconOf(n: Notification): Bitmap? = runCatching {
        val d = n.smallIcon.loadDrawable(this) ?: return null
        Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).also { d.setBounds(0, 0, 96, 96); d.draw(Canvas(it)) }
    }.getOrNull()
}

internal object StandbyAlertState {
    val alerts = MutableStateFlow<List<StandbyAlert>>(emptyList())
    val icons = ConcurrentHashMap<String, Bitmap>()
    /** Packages whose icon is the full colour app icon, drawn as is, not tinted. */
    val colour: MutableSet<String> = ConcurrentHashMap.newKeySet()
}

@Composable
actual fun rememberStandbyAlerts(): List<StandbyAlert> {
    val list by StandbyAlertState.alerts.collectAsState()
    return list
}

@Composable
actual fun AlertIcon(packageName: String, modifier: Modifier, tint: Color) {
    val bmp = StandbyAlertState.icons[packageName]
    if (bmp != null) Image(bmp.asImageBitmap(), null, modifier, colorFilter = if (packageName in StandbyAlertState.colour) null else ColorFilter.tint(tint), alpha = tint.alpha)
}

@Composable
actual fun rememberNotificationAccess(): NotificationAccess? {
    val ctx = LocalContext.current
    val foreground = rememberAppForeground()
    // Checked again each time the app comes back to the front, i.e. after the system screen.
    val granted = remember(ctx, foreground) { androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName) }
    return remember(granted) {
        NotificationAccess(granted) { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

@Composable
actual fun rememberCharging(): Boolean {
    val ctx = LocalContext.current
    var on by remember { mutableStateOf(false) }
    DisposableEffect(ctx) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) { on = (i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0 }
        }
        receiver.onReceive(ctx, ctx.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED)))
        onDispose { runCatching { ctx.unregisterReceiver(receiver) } }
    }
    return on
}

@Composable
actual fun StandbyHideBars() {
    val view = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(view) {
        // The compose view of a sheet or dialog is itself the window provider; an ancestor in other setups.
        val window = (sequenceOf<Any?>(view) + generateSequence<android.view.ViewParent>(view.parent) { it.parent }.map { it as Any? })
            .filterIsInstance<androidx.compose.ui.window.DialogWindowProvider>().firstOrNull()?.window
        fun hide() {
            if (window == null) return
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
            androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).also { c ->
                c.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                c.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            }
        }
        hide()
        // Hiding before the window is attached can be ignored, so once more after layout.
        view.post { hide() }
        onDispose { }
    }
}
