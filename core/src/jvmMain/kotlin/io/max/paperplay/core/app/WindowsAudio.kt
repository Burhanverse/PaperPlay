package io.max.paperplay.core.app

import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.ptr.PointerByReference

/** Windows' default sound output: its name and whether it is headphones. */
class WindowsOutput(val name: String, val headphones: Boolean)

private interface PropVariantApi : com.sun.jna.Library {
    fun PropVariantClear(pv: Pointer): Int
}

/**
 * The default playback endpoint, as Windows names it ("Speakers (Realtek(R) Audio)", "Headphones (realme Buds)") and whether its
 * form factor is headphones or a headset. Null off Windows or when Windows cannot say. Calls the Core Audio COM interfaces by
 * their method tables; safe to call from any thread.
 */
fun windowsOutput(): WindowsOutput? {
    if (!System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) return null
    return runCatching { read() }.getOrNull()
}

private fun vcall(obj: Pointer, index: Int, vararg args: Any?): Int {
    val table = obj.getPointer(0)
    val fn = Function.getFunction(table.getPointer(index.toLong() * Native.POINTER_SIZE), Function.ALT_CONVENTION)
    return fn.invokeInt(arrayOf<Any?>(obj, *args))
}

private fun release(p: Pointer?) { if (p != null) vcall(p, 2) }

private fun key(fmtid: String, pid: Int): Memory {
    val m = Memory(20)
    val g = Guid.GUID.fromString(fmtid)
    g.write()
    m.write(0, g.pointer.getByteArray(0, 16), 0, 16)
    m.setInt(16, pid)
    return m
}

private fun read(): WindowsOutput? {
    val hr = Ole32.INSTANCE.CoInitializeEx(null, 0)
    val initialised = hr.toInt() >= 0
    var enumerator: Pointer? = null
    var device: Pointer? = null
    var store: Pointer? = null
    try {
        val ppv = PointerByReference()
        val clsid = Guid.GUID.fromString("{BCDE0395-E52F-467C-8E3D-C4579291692E}")
        val iid = Guid.GUID.fromString("{A95664D2-9614-4F35-A746-DE8DB63617E6}")
        if (Ole32.INSTANCE.CoCreateInstance(clsid, null, 1 /* CLSCTX_INPROC_SERVER */, iid, ppv).toInt() < 0) return null
        enumerator = ppv.value
        val dev = PointerByReference()
        // GetDefaultAudioEndpoint(eRender = 0, eMultimedia = 1)
        if (vcall(enumerator, 4, 0, 1, dev) < 0) return null
        device = dev.value
        val ps = PointerByReference()
        // OpenPropertyStore(STGM_READ = 0)
        if (vcall(device, 4, 0, ps) < 0) return null
        store = ps.value
        val api = Native.load("ole32", PropVariantApi::class.java)
        val value = Memory(24)
        fun get(k: Memory): Memory? {
            value.clear()
            return if (vcall(store, 5, k, value) >= 0) value else null
        }
        val nameKey = key("{A45C254E-DF1C-4EFD-8020-67D146A850E0}", 14)
        val name = get(nameKey)?.let { v ->
            val vt = v.getShort(0).toInt()
            if (vt == 31) v.getPointer(8)?.getWideString(0) else null
        }
        api.PropVariantClear(value)
        val formKey = key("{1DA5D803-D492-4EDD-8C23-E0C0FFEE7F0E}", 0)
        val form = get(formKey)?.let { v -> if (v.getShort(0).toInt() == 19) v.getInt(8) else -1 } ?: -1
        api.PropVariantClear(value)
        if (name.isNullOrBlank()) return null
        // EndpointFormFactor: 3 = headphones, 5 = headset.
        return WindowsOutput(name, form == 3 || form == 5)
    } finally {
        release(store); release(device); release(enumerator)
        if (initialised) Ole32.INSTANCE.CoUninitialize()
    }
}
