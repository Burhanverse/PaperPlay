package io.max.paperplay.ui

import androidx.compose.runtime.Composable

actual fun clockNow(): ClockParts {
    val t = java.time.LocalDateTime.now()
    return ClockParts(t.hour, t.minute, t.second, t.format(java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM")))
}

@Composable
actual fun StandbyWindowEffect(brightness: Float) {}

@Composable
actual fun rememberBatteryPercent(): Int? = null

/** The default Windows output ("Speakers (Realtek(R) Audio)", "Headphones (...)"), looked at again every few seconds. */
@Composable
actual fun rememberOutputDevice(): OutputDevice {
    val device = androidx.compose.runtime.produceState(OutputDevice("This computer", external = false)) {
        while (true) {
            val now = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { io.max.paperplay.core.app.windowsOutput() }
            if (now != null) value = OutputDevice(now.name, now.headphones)
            kotlinx.coroutines.delay(5_000)
        }
    }
    return device.value
}

@Composable
actual fun rememberOutputSwitcher(): (() -> Unit)? = null

@Composable
actual fun rememberCharging(): Boolean = false

@Composable
actual fun rememberStandbyAlerts(): List<StandbyAlert> = emptyList()

@Composable
actual fun AlertIcon(packageName: String, modifier: androidx.compose.ui.Modifier, tint: androidx.compose.ui.graphics.Color) {}

@Composable
actual fun rememberNotificationAccess(): NotificationAccess? = null

@Composable
actual fun StandbyHideBars() {}
