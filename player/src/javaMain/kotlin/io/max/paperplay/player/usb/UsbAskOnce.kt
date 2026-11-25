package io.max.paperplay.player.usb

/** Decides when the app itself may ask for a USB device: once per plug-in, and not for one that was just plugged in, because Android asks then. */
class UsbAskOnce {
    private val asked = HashSet<Int>()

    /** Plugged in while the app runs: Android's own question ("Open PaperPlay to handle ...") is on the screen. */
    @Synchronized fun attached(deviceId: Int) { asked.add(deviceId) }

    @Synchronized fun detached(deviceId: Int) { asked.remove(deviceId) }

    /** The owner wants it (a tap on "allow", or the mode was switched on): the next [mayAsk] says yes again. */
    @Synchronized fun askAgain() { asked.clear() }

    @Synchronized fun mayAsk(deviceId: Int): Boolean = asked.add(deviceId)
}
