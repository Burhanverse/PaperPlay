package io.max.paperplay.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/** Local time and date for the standby clock. */
class ClockParts(val hour: Int, val minute: Int, val second: Int, val dateLabel: String)

expect fun clockNow(): ClockParts

/** While composed: keeps the screen on and sets the window brightness (0..1; 1 or more leaves the system level). No-op on desktop. */
@Composable
expect fun StandbyWindowEffect(brightness: Float)

/** Battery level 0..100, or null where unknown (desktop). */
@Composable
expect fun rememberBatteryPercent(): Int?

/** Where sound is going now: the device's name and whether it is headphones, earbuds or a speaker set apart from the phone. */
data class OutputDevice(val name: String, val external: Boolean)

@Composable
expect fun rememberOutputDevice(): OutputDevice

/** Opens the system's audio output chooser (speaker, Bluetooth, cast), or null where the platform has none. */
@Composable
expect fun rememberOutputSwitcher(): (() -> Unit)?

/** True while the device is plugged in. False on desktop. */
@Composable
expect fun rememberCharging(): Boolean

/** One icon on the standby screen for an app that has something to show: [call] for an incoming or ongoing call, [count] for how many. */
class StandbyAlert(val packageName: String, val call: Boolean, val count: Int)

/** Apps with notifications right now (icons only, never their text). Empty until notification access is granted, and on desktop. */
@Composable
expect fun rememberStandbyAlerts(): List<StandbyAlert>

/** The notification icon of [packageName] (the same glyph the status bar shows), tinted with [tint]. */
@Composable
expect fun AlertIcon(packageName: String, modifier: Modifier, tint: Color)

/** Whether the app may read notifications, and a way to open the system screen that grants it; null where the platform has no such thing. */
class NotificationAccess(val granted: Boolean, val open: () -> Unit)

@Composable
expect fun rememberNotificationAccess(): NotificationAccess?

/** Hides the status and navigation bars of the window this is composed in (a sheet or dialog opened over standby). No-op on desktop. */
@Composable
expect fun StandbyHideBars()
