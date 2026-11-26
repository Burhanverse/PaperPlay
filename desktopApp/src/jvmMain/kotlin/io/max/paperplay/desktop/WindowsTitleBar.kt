package io.max.paperplay.desktop

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import java.awt.Window

/** Tints the native Windows title bar to match the app (dark mode, caption and text colours). Windows 10 20H1+; caption colours need Windows 11. */
internal object WindowsTitleBar {
    private interface Dwm : StdCallLibrary {
        fun DwmSetWindowAttribute(hwnd: Pointer, attr: Int, value: IntByReference, size: Int): Int
    }

    /** Windows 11 rounds the corners of an undecorated window itself, cleanly anti-aliased; a transparent window clipped by Compose left dark fringes. Older Windows ignores it. */
    fun roundCorners(window: Window?) {
        val api = dwm ?: return
        val w = window ?: return
        runCatching { Native.getComponentPointer(w)?.let { api.DwmSetWindowAttribute(it, 33, IntByReference(2), 4) } } // DWMWA_WINDOW_CORNER_PREFERENCE = DWMWCP_ROUND
    }

    private val dwm: Dwm? = runCatching {
        if (System.getProperty("os.name").orEmpty().startsWith("Windows")) Native.load("dwmapi", Dwm::class.java) else null
    }.getOrNull()

    private var lastDark: Boolean = true
    private var lastBg: Int = 0xFF111318.toInt()
    private var lastText: Int = 0xFFFFFFFF.toInt()

    /**
     * Paints the underlying AWT/Swing window surface immediately so Windows/Linux never flashes a bright
     * white frame before Skia draws the first frame (on startup, tray restore, mini window, or theme change).
     */
    fun applySurfaceColor(window: Window?, argb: Int) {
        val w = window ?: return
        val color = java.awt.Color(argb, false)
        runCatching {
            w.background = color
            if (w is javax.swing.RootPaneContainer) {
                w.rootPane?.background = color
                w.contentPane?.background = color
                w.layeredPane?.background = color
            }
        }
    }

    /** Re-applies the last known title bar theme (e.g. after leaving borderless fullscreen or tray restore). */
    fun reapply(window: Window?) {
        apply(window, lastDark, lastBg, lastText)
    }

    /** [background] and [text] are ARGB ints from Compose. */
    fun apply(window: Window?, dark: Boolean, background: Int, text: Int) {
        lastDark = dark
        lastBg = background
        lastText = text
        applySurfaceColor(window, background)
        val api = dwm ?: return
        val w = window ?: return
        runCatching {
            val ptr: Pointer = Native.getComponentPointer(w) ?: return
            val hwnd = ptr
            api.DwmSetWindowAttribute(hwnd, 20, IntByReference(if (dark) 1 else 0), 4) // DWMWA_USE_IMMERSIVE_DARK_MODE
            api.DwmSetWindowAttribute(hwnd, 35, IntByReference(bgr(background)), 4) // DWMWA_CAPTION_COLOR
            api.DwmSetWindowAttribute(hwnd, 36, IntByReference(bgr(text)), 4) // DWMWA_TEXT_COLOR
        }
    }

    /** Windows wants 0x00BBGGRR. */
    private fun bgr(argb: Int): Int = ((argb and 0xFF) shl 16) or (argb and 0xFF00) or ((argb shr 16) and 0xFF)
}
