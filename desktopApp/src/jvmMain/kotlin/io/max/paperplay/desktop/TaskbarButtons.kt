package io.max.paperplay.desktop

import com.sun.jna.Callback
import com.sun.jna.CallbackThreadInitializer
import com.sun.jna.Function
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import io.max.paperplay.player.PlayLog
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.image.BufferedImage

/**
 * Previous, play or pause, and next under the app's taskbar preview (Windows 7 and later, `ITaskbarList3`).
 *
 * There is no JNA wrapper for the interface, so its methods are called through the COM table by slot number. Clicks arrive as
 * `WM_COMMAND` with `THBN_CLICKED`, so the window procedure is replaced by one that handles those and passes everything
 * else on. Every step is guarded: if anything fails the buttons are simply not there and the window carries on untouched.
 */
internal object TaskbarButtons {
    private const val GWLP_WNDPROC = -4
    private const val WM_COMMAND = 0x0111
    private const val WM_APP = 0x8000
    private const val MSG_ADD = WM_APP + 71
    private const val MSG_UPDATE = WM_APP + 72
    private const val THBN_CLICKED = 0x1800
    private const val THB_ICON = 2
    private const val THB_TOOLTIP = 4
    private const val THB_FLAGS = 8
    private const val SIZE = 32
    private val ids = arrayOf("previous", "playpause", "next")

    @Structure.FieldOrder("data1", "data2", "data3", "data4")
    class Guid : Structure() {
        @JvmField var data1 = 0
        @JvmField var data2: Short = 0
        @JvmField var data3: Short = 0
        @JvmField var data4 = ByteArray(8)

        companion object {
            fun of(text: String): Guid = Guid().also { g ->
                val p = text.split('-')
                g.data1 = p[0].toLong(16).toInt()
                g.data2 = p[1].toInt(16).toShort()
                g.data3 = p[2].toInt(16).toShort()
                val tail = p[3] + p[4]
                for (i in 0 until 8) g.data4[i] = tail.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        }
    }

    @Structure.FieldOrder("mask", "id", "bitmap", "icon", "tip", "flags")
    class Thumb : Structure() {
        @JvmField var mask = 0
        @JvmField var id = 0
        @JvmField var bitmap = 0
        @JvmField var icon: Pointer? = null
        @JvmField var tip = CharArray(260)
        @JvmField var flags = 0
    }

    @Structure.FieldOrder("fIcon", "xHotspot", "yHotspot", "hbmMask", "hbmColor")
    class IconInfo : Structure() {
        @JvmField var fIcon = 1
        @JvmField var xHotspot = 0
        @JvmField var yHotspot = 0
        @JvmField var hbmMask: Pointer? = null
        @JvmField var hbmColor: Pointer? = null
    }

    @Structure.FieldOrder("size", "width", "height", "planes", "bitCount", "compression", "sizeImage", "xppm", "yppm", "clrUsed", "clrImportant", "colors")
    class BitmapInfo : Structure() {
        @JvmField var size = 40
        @JvmField var width = SIZE
        @JvmField var height = -SIZE // top-down
        @JvmField var planes: Short = 1
        @JvmField var bitCount: Short = 32
        @JvmField var compression = 0
        @JvmField var sizeImage = 0
        @JvmField var xppm = 0
        @JvmField var yppm = 0
        @JvmField var clrUsed = 0
        @JvmField var clrImportant = 0
        @JvmField var colors = 0
    }

    /** The replacement window procedure. */
    interface WndProc : StdCallLibrary.StdCallCallback {
        fun callback(hwnd: Pointer?, msg: Int, wParam: Long, lParam: Long): Long
    }

    private interface User32 : StdCallLibrary {
        fun SetWindowLongPtrW(hwnd: Pointer, index: Int, proc: WndProc): Pointer?
        fun SetWindowLongPtrW(hwnd: Pointer, index: Int, proc: Pointer): Pointer?
        fun CallWindowProcW(prev: Pointer, hwnd: Pointer?, msg: Int, wParam: Long, lParam: Long): Long
        fun RegisterWindowMessageW(name: WString): Int
        fun PostMessageW(hwnd: Pointer, msg: Int, wParam: Long, lParam: Long): Boolean
        fun CreateIconIndirect(info: IconInfo): Pointer?
    }

    private interface Gdi32 : StdCallLibrary {
        fun CreateDIBSection(hdc: Pointer?, info: BitmapInfo, usage: Int, bits: PointerByReference, section: Pointer?, offset: Int): Pointer?
        fun CreateBitmap(width: Int, height: Int, planes: Int, bitsPerPixel: Int, bits: Pointer?): Pointer?
        fun DeleteObject(o: Pointer): Boolean
    }

    private interface Ole32 : StdCallLibrary {
        fun CoInitializeEx(reserved: Pointer?, flags: Int): Int
        fun CoCreateInstance(clsid: Guid, outer: Pointer?, context: Int, iid: Guid, out: PointerByReference): Int
    }

    private var user32: User32? = null
    private var gdi32: Gdi32? = null
    private var ole32: Ole32? = null
    private var hwnd: Pointer? = null
    private var previous: Pointer? = null
    private var taskbar: Pointer? = null
    private var createdMessage = 0
    private var added = false
    private var onAction: ((String) -> Unit)? = null
    private var icons: Map<String, Pointer>? = null
    @Volatile private var playing = false

    // Kept in a field: the native side only holds a pointer to it, so it must not be collected.
    private val proc = object : WndProc {
        override fun callback(hwnd: Pointer?, msg: Int, wParam: Long, lParam: Long): Long {
            try {
                when {
                    msg == createdMessage && createdMessage != 0 -> { added = false; hwnd?.let(::addButtons) }
                    msg == MSG_ADD -> hwnd?.let(::addButtons)
                    msg == MSG_UPDATE -> hwnd?.let(::updateButtons)
                    msg == WM_COMMAND && ((wParam shr 16) and 0xFFFF).toInt() == THBN_CLICKED -> {
                        ids.getOrNull((wParam and 0xFFFF).toInt())?.let { onAction?.invoke(it) }
                        return 0
                    }
                }
            } catch (t: Throwable) {
                PlayLog.log("taskbar", "message handler failed: $t")
            }
            val prev = previous ?: return 0
            return user32?.CallWindowProcW(prev, hwnd, msg, wParam, lParam) ?: 0
        }
    }

    /** Adds the buttons to [window]. [action] gets "previous", "playpause" or "next". Does nothing off Windows or on any failure. */
    fun install(window: java.awt.Window, action: (String) -> Unit) {
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows") || hwnd != null) return
        runCatching {
            val handle = Native.getComponentPointer(window) ?: error("no window handle")
            val u = Native.load("user32", User32::class.java)
            user32 = u; gdi32 = Native.load("gdi32", Gdi32::class.java); ole32 = Native.load("ole32", Ole32::class.java)
            onAction = action
            createdMessage = u.RegisterWindowMessageW(WString("TaskbarButtonCreated"))
            // The callback runs on the window's own thread; it must not keep the JVM from exiting.
            Native.setCallbackThreadInitializer(proc, CallbackThreadInitializer(true, false, "taskbar-buttons"))
            previous = u.SetWindowLongPtrW(handle, GWLP_WNDPROC, proc) ?: error("could not replace the window procedure")
            hwnd = handle
            // The taskbar button already exists, so its "created" message has passed: add the buttons now, on the window's thread.
            u.PostMessageW(handle, MSG_ADD, 0, 0)
        }.onFailure {
            PlayLog.log("taskbar", "buttons not installed: $it")
            hwnd = null
        }
    }

    /** The window was destroyed; forget its handle so the next window can install again. */
    fun release() { hwnd = null; added = false }

    /** The play button shows pause while a song plays. Safe from any thread. */
    fun setPlaying(now: Boolean) {
        if (playing == now) return
        playing = now
        val h = hwnd ?: return
        runCatching { user32?.PostMessageW(h, MSG_UPDATE, 0, 0) }
    }

    // ---- on the window's thread from here

    private fun addButtons(h: Pointer) {
        if (added) return
        val tb = taskbar ?: createTaskbar() ?: return
        val set = buttons()
        val hr = com(tb, 15, h, set.size, set[0]) // ThumbBarAddButtons
        if (hr < 0) { PlayLog.log("taskbar", "ThumbBarAddButtons failed: 0x${Integer.toHexString(hr)}"); return }
        added = true
        PlayLog.log("taskbar", "buttons added")
    }

    private fun updateButtons(h: Pointer) {
        val tb = taskbar ?: return
        if (!added) return
        val set = buttons()
        val hr = com(tb, 16, h, set.size, set[0]) // ThumbBarUpdateButtons
        if (hr < 0) PlayLog.log("taskbar", "ThumbBarUpdateButtons failed: 0x${Integer.toHexString(hr)}")
    }

    private fun createTaskbar(): Pointer? {
        ole32?.CoInitializeEx(null, 2) // a no-op if the thread is already set up
        val out = PointerByReference()
        val hr = ole32!!.CoCreateInstance(Guid.of("56FDF344-FD6D-11d0-958A-006097C9A090"), null, 1, Guid.of("EA1AFB91-9E28-4B86-90E9-9E9F8A5EEFAF"), out)
        val p = out.value
        if (hr < 0 || p == null) { PlayLog.log("taskbar", "taskbar object not created: 0x${Integer.toHexString(hr)}"); return null }
        if (com(p, 3) < 0) { PlayLog.log("taskbar", "HrInit failed"); return null } // ITaskbarList::HrInit
        taskbar = p
        return p
    }

    /** Calls slot [slot] of the COM object's table. */
    private fun com(obj: Pointer, slot: Int, vararg args: Any?): Int {
        val table = obj.getPointer(0)
        val fn = Function.getFunction(table.getPointer(slot.toLong() * Native.POINTER_SIZE))
        return fn.invokeInt(arrayOf<Any?>(obj, *args))
    }

    private fun buttons(): Array<Thumb> {
        val set = Thumb().toArray(3) as Array<Thumb>
        val pics = icons ?: makeIcons().also { icons = it }
        val tips = arrayOf("Previous", if (playing) "Pause" else "Play", "Next")
        val pic = arrayOf("previous", if (playing) "pause" else "play", "next")
        for (i in 0 until 3) {
            set[i].mask = THB_ICON or THB_TOOLTIP or THB_FLAGS
            set[i].id = i
            set[i].icon = pics[pic[i]]
            set[i].tip = CharArray(260).also { tips[i].toCharArray().copyInto(it) }
            set[i].flags = 0
            set[i].write()
        }
        return set
    }

    // ---- icons: white glyphs drawn here, made into Windows icons

    private fun makeIcons(): Map<String, Pointer> {
        val out = HashMap<String, Pointer>()
        for (name in listOf("previous", "play", "pause", "next")) toIcon(glyph(name))?.let { out[name] = it }
        return out
    }

    private fun glyph(name: String): BufferedImage {
        val img = BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = java.awt.Color.WHITE
        fun triangle(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
            val p = Path2D.Float(); p.moveTo(x1, y1); p.lineTo(x2, y2); p.lineTo(x3, y3); p.closePath(); g.fill(p)
        }
        when (name) {
            "previous" -> { g.fillRect(6, 8, 3, 16); triangle(25f, 8f, 25f, 24f, 11f, 16f) }
            "next" -> { g.fillRect(23, 8, 3, 16); triangle(7f, 8f, 7f, 24f, 21f, 16f) }
            "play" -> triangle(10f, 6f, 10f, 26f, 26f, 16f)
            else -> { g.fillRect(8, 7, 6, 18); g.fillRect(18, 7, 6, 18) }
        }
        g.dispose()
        return img
    }

    private fun toIcon(img: BufferedImage): Pointer? = runCatching {
        val gdi = gdi32 ?: return null
        val bits = PointerByReference()
        val color = gdi.CreateDIBSection(null, BitmapInfo(), 0, bits, null, 0) ?: return null
        bits.value.write(0, img.getRGB(0, 0, SIZE, SIZE, null, 0, SIZE), 0, SIZE * SIZE)
        val mask = gdi.CreateBitmap(SIZE, SIZE, 1, 1, null) ?: return null
        val info = IconInfo().also { it.hbmMask = mask; it.hbmColor = color }
        val icon = user32?.CreateIconIndirect(info)
        gdi.DeleteObject(mask); gdi.DeleteObject(color)
        icon
    }.getOrNull()
}
