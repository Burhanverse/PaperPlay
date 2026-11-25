package io.max.paperplay.player.usb

/** True when the next song needs nothing changed at the DAC: same rate, channels, sample format and kind. Unknown values never match. */
fun sameUsbStream(rate: Int, channels: Int, encoding: Int, mime: String?, rate2: Int, channels2: Int, encoding2: Int, mime2: String?): Boolean =
    rate > 0 && channels > 0 && rate == rate2 && channels == channels2 && encoding == encoding2 && mime == mime2
