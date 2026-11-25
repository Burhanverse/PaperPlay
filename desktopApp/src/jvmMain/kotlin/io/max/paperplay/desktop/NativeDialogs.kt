package io.max.paperplay.desktop

import java.awt.FileDialog
import java.io.File

/**
 * The system's own file dialogs. On Windows these are the current Explorer-style dialogs (WinForms wraps IFileDialog), shown from a
 * short PowerShell helper; on Linux zenity or kdialog; AWT's FileDialog is the fallback everywhere, and on macOS it already is the native panel.
 */
internal object NativeDialogs {
    private val windows = System.getProperty("os.name").orEmpty().startsWith("Windows")
    private val linux = System.getProperty("os.name").orEmpty().startsWith("Linux")

    private fun q(s: String) = "'" + s.replace("'", "''") + "'"

    /** Runs a command and returns its stdout lines, or null when it could not run (missing tool) so the caller can fall back. */
    private fun run(vararg cmd: String): List<String>? = runCatching {
        val p = ProcessBuilder(*cmd).redirectErrorStream(false).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val out = p.inputStream.bufferedReader(Charsets.UTF_8).readText().lines().filter { it.isNotBlank() }
        p.waitFor()
        out
    }.getOrNull()

    private fun powershell(script: String): List<String>? {
        val full = "[Console]::OutputEncoding = [Text.Encoding]::UTF8\n" + script
        return run("powershell.exe", "-NoProfile", "-NonInteractive", "-STA", "-EncodedCommand",
            java.util.Base64.getEncoder().encodeToString(full.toByteArray(Charsets.UTF_16LE)))
    }

    /** Windows filter string `Files|*.a;*.b|All files|*.*` from patterns like `*.a;*.b`. */
    private fun winFilter(patterns: String?) = (if (patterns != null) "Files|$patterns|" else "") + "All files|*.*"

    private const val WIN_HOST = """
Add-Type -AssemblyName System.Windows.Forms
[System.Windows.Forms.Application]::EnableVisualStyles()
${'$'}host1 = New-Object System.Windows.Forms.Form
${'$'}host1.TopMost = ${'$'}true; ${'$'}host1.ShowInTaskbar = ${'$'}false; ${'$'}host1.Opacity = 0
${'$'}host1.StartPosition = 'CenterScreen'; ${'$'}host1.Width = 1; ${'$'}host1.Height = 1
${'$'}host1.Show()
"""

    /** Files to open. [patterns] like `*.zip;*.png`. Empty when cancelled. */
    fun openFiles(title: String, patterns: String?, multiple: Boolean = false): List<File> {
        val picked = when {
            windows -> powershell(WIN_HOST + """
${'$'}d = New-Object System.Windows.Forms.OpenFileDialog
${'$'}d.Title = ${q(title)}; ${'$'}d.Filter = ${q(winFilter(patterns))}; ${'$'}d.Multiselect = ${if (multiple) "${'$'}true" else "${'$'}false"}
if (${'$'}d.ShowDialog(${'$'}host1) -eq 'OK') { ${'$'}d.FileNames }
""")
            linux -> run("zenity", "--file-selection", "--title=$title", if (multiple) "--multiple" else "--title=$title", "--separator=\n",
                *patterns?.let { arrayOf("--file-filter=" + it.replace(';', ' ')) } ?: emptyArray())
                ?: run("kdialog", "--getopenfilename", ".", patterns?.replace(';', ' ') ?: "*", "--title", title, *(if (multiple) arrayOf("--multiple", "--separate-output") else emptyArray()))
            else -> null
        }
        if (picked != null) return picked.map(::File).filter { it.exists() }
        val d = FileDialog(null as java.awt.Frame?, title, FileDialog.LOAD)
        d.isMultipleMode = multiple
        if (patterns != null) d.file = patterns
        d.isVisible = true
        return d.files.toList()
    }

    /** A place to save to, or null when cancelled. */
    fun saveFile(title: String, name: String): File? {
        val ext = name.substringAfterLast('.', "")
        val picked = when {
            windows -> powershell(WIN_HOST + """
${'$'}d = New-Object System.Windows.Forms.SaveFileDialog
${'$'}d.Title = ${q(title)}; ${'$'}d.FileName = ${q(name)}; ${'$'}d.OverwritePrompt = ${'$'}true
${'$'}d.Filter = ${q(if (ext.isNotEmpty()) "${ext.uppercase()} file|*.$ext|All files|*.*" else "All files|*.*")}; ${'$'}d.DefaultExt = ${q(ext)}
if (${'$'}d.ShowDialog(${'$'}host1) -eq 'OK') { ${'$'}d.FileName }
""")
            linux -> run("zenity", "--file-selection", "--save", "--confirm-overwrite", "--title=$title", "--filename=$name")
                ?: run("kdialog", "--getsavefilename", name, "--title", title)
            else -> null
        }
        if (picked != null) return picked.firstOrNull()?.let(::File)
        val d = FileDialog(null as java.awt.Frame?, title, FileDialog.SAVE)
        d.file = name
        d.isVisible = true
        return d.files.firstOrNull()
    }

    /** A folder, from the system's folder picker (IFileOpenDialog), or null when cancelled. Blocks until closed. */
    fun pickFolder(title: String): File? {
        if (!windows) {
            if (linux) (run("zenity", "--file-selection", "--directory", "--title=$title") ?: run("kdialog", "--getexistingdirectory", ".", "--title", title))
                ?.let { return it.firstOrNull()?.let(::File) }
            // macOS: the folder mode must be switched on before the dialog is created.
            System.setProperty("apple.awt.fileDialogForDirectories", "true")
            try {
                val d = FileDialog(null as java.awt.Frame?, title, FileDialog.LOAD)
                d.isVisible = true
                return d.files.firstOrNull()
            } finally { System.setProperty("apple.awt.fileDialogForDirectories", "false") }
        }
        val log = io.max.paperplay.player.PlayLog
        // The answer goes through a UTF-8 file: console output is in the OEM code page, which garbles folder names with accents or other scripts.
        val outFile = File.createTempFile("paperplay-folder", ".txt")
        // Without progress records: they come as console text, and a cancel would be taken for a broken dialog.
        val script = ("\$ProgressPreference = 'SilentlyContinue'" + WIN_HOST + FOLDER_SCRIPT).replace("__TITLE__", title.replace("'", "''")).replace("__OUT__", outFile.path.replace("'", "''"))
        val t0 = System.currentTimeMillis()
        var console = ""
        val failed = try {
            val p = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-STA", "-ExecutionPolicy", "Bypass", "-EncodedCommand",
                java.util.Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE)))
                .redirectErrorStream(true).start()
            console = p.inputStream.bufferedReader().readText().trim()
            p.waitFor()
            false
        } catch (e: Throwable) {
            log.log("upload", "folder dialog could not start: ${e::class.simpleName} ${e.message}")
            true
        }
        val answer = runCatching { outFile.readText(Charsets.UTF_8).trim() }.getOrDefault("")
        outFile.delete()
        log.log("upload", "folder dialog closed after ${System.currentTimeMillis() - t0} ms, answer \"$answer\"" + if (console.isNotEmpty()) ", console: ${console.take(300)}" else "")
        // No answer file content with console text, or an exception, means the dialog broke (a cancel prints nothing): use Java's own chooser.
        if (failed || (answer.isEmpty() && console.isNotEmpty())) return swingFolder(title)
        return answer.takeIf { it.isNotEmpty() && File(it).isDirectory }?.let(::File)
    }

    /** Java's own folder chooser, used when the Windows dialog cannot be shown. */
    private fun swingFolder(title: String): File? {
        var result: File? = null
        javax.swing.SwingUtilities.invokeAndWait {
            val c = javax.swing.JFileChooser().apply { dialogTitle = title; fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY }
            if (c.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) result = c.selectedFile
        }
        return result
    }

    private val FOLDER_SCRIPT = """
Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class FolderPick {
  [ComImport, Guid("DC1C5A9C-E88A-4DDE-A5A1-60F82A20AEF7")] class FileOpenDialog {}
  [ComImport, Guid("43826d1e-e718-42ee-bc55-a1e261c37bfe"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
  interface IShellItem {
    void BindToHandler(IntPtr pbc, ref Guid bhid, ref Guid riid, out IntPtr ppv);
    void GetParent(out IShellItem ppsi);
    void GetDisplayName(uint sigdnName, [MarshalAs(UnmanagedType.LPWStr)] out string name);
    void GetAttributes(uint mask, out uint attrs);
    void Compare(IShellItem psi, uint hint, out int order);
  }
  [ComImport, Guid("42f85136-db7e-439c-85f1-e4075d135fc8"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
  interface IFileDialog {
    [PreserveSig] int Show(IntPtr hwnd);
    void SetFileTypes(uint c, IntPtr t);
    void SetFileTypeIndex(uint i);
    void GetFileTypeIndex(out uint i);
    void Advise(IntPtr p, out uint cookie);
    void Unadvise(uint cookie);
    void SetOptions(uint o);
    void GetOptions(out uint o);
    void SetDefaultFolder(IShellItem s);
    void SetFolder(IShellItem s);
    void GetFolder(out IShellItem s);
    void GetCurrentSelection(out IShellItem s);
    void SetFileName([MarshalAs(UnmanagedType.LPWStr)] string n);
    void GetFileName([MarshalAs(UnmanagedType.LPWStr)] out string n);
    void SetTitle([MarshalAs(UnmanagedType.LPWStr)] string t);
    void SetOkButtonLabel([MarshalAs(UnmanagedType.LPWStr)] string t);
    void SetFileNameLabel([MarshalAs(UnmanagedType.LPWStr)] string t);
    void GetResult(out IShellItem s);
  }
  public static string Pick(string title, IntPtr owner) {
    var d = (IFileDialog)new FileOpenDialog();
    uint o; d.GetOptions(out o);
    d.SetOptions(o | 0x20 | 0x40);
    d.SetTitle(title);
    if (d.Show(owner) != 0) return "";
    IShellItem r; d.GetResult(out r);
    string path; r.GetDisplayName(0x80058000, out path);
    return path;
  }
}
'@
${'$'}r = [FolderPick]::Pick('__TITLE__', ${'$'}host1.Handle)
[IO.File]::WriteAllText('__OUT__', ${'$'}r, (New-Object Text.UTF8Encoding ${'$'}false))
""".trimIndent()
}
