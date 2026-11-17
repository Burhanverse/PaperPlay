package io.max.paperplay.compose

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import io.max.paperplay.core.app.AppServices
import io.max.paperplay.core.backup.BackupRestorer
import io.max.paperplay.core.backup.BackupSource
import io.max.paperplay.core.backup.RestoreOptions
import io.max.paperplay.core.backup.RestoreTarget
import io.max.paperplay.core.backup.androidSecretStore
import io.max.paperplay.core.backup.streamBackupSource
import io.max.paperplay.core.db.libraryDriver
import io.max.paperplay.player.initPlayer
import io.max.paperplay.ui.App
import io.max.paperplay.ui.AppEnv
import io.max.paperplay.ui.RestoreHooks
import io.max.paperplay.ui.app.FolderScan
import io.max.paperplay.ui.app.IncomingShares
import java.io.File
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var services: AppServices? = null
    /** Where a notification tap asked the app to open ("player" or "tasks"). */
    private val open = MutableStateFlow<String?>(null)
    private val auth = BrowserAuth { startActivity(it) }

    private val recordAudioLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    fun requestRecordAudioPermission() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            recordAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    /** Copies audio shared to the app into the cache and hands the paths to the uploads page. */
    private fun takeShared(intent: Intent) {
        if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE) return
        val uris = if (intent.action == Intent.ACTION_SEND) listOfNotNull(sharedUri(intent))
        else (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra("android.intent.extra.STREAM", android.net.Uri::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableArrayListExtra<android.net.Uri>("android.intent.extra.STREAM")).orEmpty()
        if (uris.isEmpty()) return
        val dir = File(cacheDir, "uploads").apply { mkdirs() }
        open.value = "upload"
        Thread {
            val files = uris.mapNotNull { copyToCache(it, dir) }
            if (files.isNotEmpty()) IncomingShares.files.update { it + files }
        }.start()
    }

    private fun sharedUri(intent: Intent): android.net.Uri? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra("android.intent.extra.STREAM", android.net.Uri::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra("android.intent.extra.STREAM")

    /** Copies one picked or shared document into [dir] under its display name; null when it cannot be read. */
    private fun copyToCache(uri: android.net.Uri, dir: File): String? {
        val sub = File(dir, System.nanoTime().toString()).apply { mkdirs() }
        return runCatching {
            var name: String? = null
            var size = 0L
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) { name = c.getString(0); if (!c.isNull(1)) size = c.getLong(1) }
            }
            // Keep room for the app's own database and Telegram's files: a full disk makes TDLib abort the whole app.
            if (cacheDir.usableSpace < size + 300L * 1024 * 1024) error("not enough free space: ${cacheDir.usableSpace / 1024 / 1024} MB free, file $size bytes")
            val out = File(sub, (name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "song").replace('/', '_').replace('\\', '_'))
            contentResolver.openInputStream(uri)!!.use { input -> out.outputStream().use { o -> input.copyTo(o) } }
            out.path
        }.getOrElse { io.max.paperplay.core.app.appLog("copy", "failed $uri: ${it.message}"); sub.deleteRecursively(); null }
    }

    private fun handleUsbDeviceIntent(intent: Intent?) {
        if (intent?.action != android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        val device: android.hardware.usb.UsbDevice? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(android.hardware.usb.UsbManager.EXTRA_DEVICE, android.hardware.usb.UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(android.hardware.usb.UsbManager.EXTRA_DEVICE)
        }
        if (device != null) {
            io.max.paperplay.player.usb.UsbAudioManager.instance?.onDeviceAttachedFromIntent(device)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleUsbDeviceIntent(intent)
        intent.getStringExtra(OPEN_EXTRA)?.let { open.value = it }
        takeShared(intent)
        auth.deliver(intent.data)
    }

    /**
     * Shows what the app is doing in the background (library scan, song info, covers, uploads) as a notification with a progress bar.
     * With "keep running" on it is the foreground service's notification; otherwise it is an ordinary one that goes away when the work ends.
     */
    @OptIn(FlowPreview::class)
    private fun watchActivity(s: AppServices) {
        fun allowed() = s.library.pref("keep_running") == "true"
        if (allowed()) KeepAliveService.start(applicationContext)
        // Updates are dropped while the service is off, so the collector can run either way.
        s.scope.launch {
            var idleSince = 0L
            combine(
                combine(s.scanner.progress, s.ready, s.error, s.activity) { scan, ready, error, activity -> arrayOf(scan, ready, error, activity) },
                combine(s.enricher.progress, s.uploader.jobs, s.covers.progress, s.deleteQueue.jobs) { enrich, uploads, covers, deletes -> arrayOf(enrich, uploads, covers, deletes) },
            ) { v, w -> v to w }
                .sample(1000)
                .collect { (v, w) ->
                    val scan = v[0] as io.max.paperplay.core.app.ScanProgress?
                    val ready = v[1] as Boolean
                    val error = v[2] as String?
                    val activity = v[3] as String?
                    @Suppress("UNCHECKED_CAST")
                    val enrich = w[0] as io.max.paperplay.core.app.EnrichProgress?
                    @Suppress("UNCHECKED_CAST")
                    val uploads = w[1] as List<io.max.paperplay.core.app.UploadJob>
                    @Suppress("UNCHECKED_CAST")
                    val covers = w[2] as io.max.paperplay.core.app.CoverProgress?
                    @Suppress("UNCHECKED_CAST")
                    val deletes = w[3] as List<io.max.paperplay.core.dupes.DeleteJob>
                    val deleting = deletes.count { it.status == io.max.paperplay.core.dupes.DeleteStatus.Queued || it.status == io.max.paperplay.core.dupes.DeleteStatus.Deleting }
                    val open = uploads.count { it.status == io.max.paperplay.core.app.UploadStatus.Queued || it.status == io.max.paperplay.core.app.UploadStatus.Uploading }
                    val busy = scan != null || activity != null || enrich != null || open > 0 || deleting > 0 || covers != null || !ready
                    val now = System.currentTimeMillis()
                    if (busy) {
                        idleSince = 0L
                        // Work began again: bring the service back if the pref allows it.
                        if (allowed()) KeepAliveService.start(applicationContext)
                    } else {
                        if (idleSince == 0L) idleSince = now
                        if (now - idleSince >= 20_000L) KeepAliveService.stop(applicationContext)
                    }
                    // Each running task is its own notification with its own bar; the service's own notification says nothing about them.
                    val jobs = buildList {
                        scan?.let { add(KeepAliveJob(1, "Library scan", it.text, it.fraction?.let { f -> (f * 1000).toInt() }, it.fraction?.let { 1000 })) }
                        enrich?.let { add(KeepAliveJob(2, "Looking up song info", "${it.done} of ${it.total}" + if (it.current.isNotBlank()) " - ${it.current}" else "", if (it.total > 0) it.done else null, if (it.total > 0) it.total else null)) }
                        if (open > 0) {
                            val done = uploads.count { it.status == io.max.paperplay.core.app.UploadStatus.Done }
                            add(KeepAliveJob(3, "Uploading songs", "$done of ${uploads.size}", done, uploads.size))
                        }
                        if (deleting > 0) {
                            val finished = deletes.count { it.status != io.max.paperplay.core.dupes.DeleteStatus.Queued && it.status != io.max.paperplay.core.dupes.DeleteStatus.Deleting }
                            add(KeepAliveJob(4, "Deleting songs", "${deletes.count { it.status == io.max.paperplay.core.dupes.DeleteStatus.Done }} of ${deletes.size}, ${deletes.count { it.status == io.max.paperplay.core.dupes.DeleteStatus.Skipped }} kept", finished, deletes.size))
                        }
                        covers?.let {
                            val total = it.total + it.lowTotal
                            add(KeepAliveJob(5, "Loading covers", listOfNotNull(
                                if (it.total > 0) "Missing: ${it.total - it.done} left of ${it.total}" else null,
                                if (it.lowTotal > 0) "Low quality: ${it.lowTotal - it.lowDone} left of ${it.lowTotal}" else null,
                            ).joinToString("\n"), if (total > 0) it.done + it.lowDone else null, if (total > 0) total else null))
                        }
                        // The activity line often repeats the scan or another task; it is shown only when it says something they do not.
                        activity?.let { line -> if (none { j -> j.text.contains(line) || line.contains(j.text) }) add(KeepAliveJob(6, "Paper Play", line, null, null)) }
                    }
                    KeepAliveService.updateJobs(applicationContext, jobs)
                    when {
                        error != null -> KeepAliveService.update(applicationContext, "Paper Play is running", error, null, null, false)
                        jobs.isNotEmpty() -> Unit // the first task is the service's own notification (updateJobs)
                        !ready -> KeepAliveService.update(applicationContext, "Paper Play is running", "Connecting to Telegram", null, null, true)
                        else -> KeepAliveService.update(applicationContext, "Paper Play is running", "Everything is up to date", null, null, false)
                    }
                }
        }
    }

    private val audioPermission get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun granted(permission: String) = checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun openSettings(action: String, withPackage: Boolean = false) {
        val intent = Intent(action).apply {
            if (withPackage) data = android.net.Uri.parse("package:$packageName")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { startActivity(intent) }.onFailure {
            runCatching { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    /** Runs the system prompt the first time; after a refusal Android shows no prompt, so [fallback] opens Settings instead. */
    private fun askOnce(permission: String, launch: (String) -> Unit, fallback: () -> Unit) {
        val prefs = getSharedPreferences("permission_checker", MODE_PRIVATE)
        if (prefs.getBoolean(permission, false)) { fallback(); return }
        prefs.edit().putBoolean(permission, true).apply()
        launch(permission)
    }

    private fun bluetoothOn(): Boolean? = runCatching {
        getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter?.isEnabled
    }.getOrNull()

    private fun onWifi(): Boolean = runCatching {
        val cm = getSystemService(android.net.ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        caps != null && (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET))
    }.getOrDefault(false)

    private fun permissionStates(): List<io.max.paperplay.ui.PermissionState> {
        val list = ArrayList<io.max.paperplay.ui.PermissionState>()
        val notifications = getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS))
        list += io.max.paperplay.ui.PermissionState("notifications", "Notifications", "Shows the playing song with controls, and scan progress", notifications)
        list += io.max.paperplay.ui.PermissionState(
            "audio", "Music files", "Lets the app read a music folder you choose where it is, so uploading it needs no copy and no extra space",
            granted(audioPermission), action = "Allow",
        )
        val btOn = bluetoothOn()
        val btPermission = Build.VERSION.SDK_INT < 31 || granted(Manifest.permission.BLUETOOTH_CONNECT)
        list += io.max.paperplay.ui.PermissionState(
            "bluetooth", "Bluetooth devices", "Lets the app see headphones and speakers, so media buttons and output switching work",
            granted = btPermission && btOn != false,
            detail = when {
                btOn == null -> "This device has no Bluetooth"
                !btPermission -> "Not allowed to see Bluetooth devices"
                !btOn -> "Bluetooth is turned off"
                else -> "Bluetooth is on"
            },
            action = if (btPermission) "Open settings" else "Allow",
        )
        val power = getSystemService(android.os.PowerManager::class.java)
        list += io.max.paperplay.ui.PermissionState(
            "battery", "Battery", "Stops Android from pausing the app in the background, which keeps scans, uploads and playback running",
            power.isIgnoringBatteryOptimizations(packageName), action = "Open settings",
        )
        list += io.max.paperplay.ui.PermissionState(
            "wifi", "Wi-Fi network", "Local sync and Network play only reach devices on the same Wi-Fi or wired network",
            onWifi(), action = "Open settings",
        )
        val shizuku = io.max.paperplay.compose.update.ShizukuInstaller(this)
        if (shizuku.installed && shared?.updates?.prefs?.shizuku == true) {
            list += io.max.paperplay.ui.PermissionState(
                "shizuku", "Shizuku", "Lets updates install without the confirmation screen",
                shizuku.allowed,
                detail = when { !shizuku.running -> "Shizuku is not running"; !shizuku.allowed -> "Not allowed in Shizuku"; else -> "Shizuku is running and allowed" },
                action = if (shizuku.running) "Allow" else "Open Shizuku",
            )
        }
        list += io.max.paperplay.ui.PermissionState(
            "install", "Install updates", "Lets the app install a downloaded update after you confirm it",
            packageManager.canRequestPackageInstalls(), action = "Open settings",
        )
        val micGranted = granted(Manifest.permission.RECORD_AUDIO)
        list += io.max.paperplay.ui.PermissionState(
            "record_audio", "Microphone (USB DAC headset)",
            "Required by Android to remember USB DAC permissions for dongles that have a headset microphone input",
            granted = micGranted,
            action = "Allow",
        )
        return list
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentInstance = this
        io.max.paperplay.player.usb.UsbAudioManager.onRequestRecordAudioPermission = {
            requestRecordAudioPermission()
        }
        enableEdgeToEdge()
        // No grey scrim behind 3-button navigation; the app draws under both bars.
        if (android.os.Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        initPlayer(applicationContext)
        handleUsbDeviceIntent(intent)
        takeShared(intent)
        // Started by the Spotify return link after Android ended the app: keep it until services can finish the login.
        auth.deliver(intent?.data)
        // A crash leaves its error text in a file, shown in Settings > Telegram > Bot activity on the next start.
        val crashFile = File(filesDir, "crash.txt")
        // The error handler is installed by PaperPlayApp, before any screen.
        intent?.getStringExtra(OPEN_EXTRA)?.let { open.value = it }
        val library = File(filesDir, "library.sqlite")
        val onboardedFile = File(filesDir, "onboarded")
        val target = RestoreTarget(
            library.path.replace(File.separatorChar, '/'),
            File(filesDir, "covers").path,
            File(filesDir, "profile").path,
            androidSecretStore(File(filesDir, "secrets")),
            File(filesDir, "user_td").path.replace(File.separatorChar, '/'),
        ) { libraryDriver(applicationContext, it) }
        setContent {
            // Remembered, so a recomposition while the picker is open does not forget who asked.
            val pending = remember { arrayOfNulls<(BackupSource) -> Unit>(1) }
            val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) pending[0]?.invoke(streamBackupSource { contentResolver.openInputStream(uri)!! })
            }
            // Save backup: the system "save as" picker, then the finished zip is copied to the chosen document.
            val savePending = remember { arrayOfNulls<(io.max.paperplay.ui.app.BackupDestination?) -> Unit>(1) }
            val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
                savePending[0]?.invoke(
                    uri?.let {
                        io.max.paperplay.ui.app.BackupDestination { path ->
                            runCatching { contentResolver.openOutputStream(it, "wt")!!.use { out -> File(path).inputStream().use { src -> src.copyTo(out) } } }.isSuccess
                        }
                    },
                )
            }
            androidx.compose.runtime.DisposableEffect(saver) {
                io.max.paperplay.ui.app.BackupFiles.pickDestination = { name, onPicked -> savePending[0] = onPicked; saver.launch(name) }
                io.max.paperplay.ui.app.BackupFiles.shareFile = { name, text ->
                    runCatching {
                        val dir = File(filesDir, "shared").apply { mkdirs() }
                        val f = File(dir, name).apply { writeText(text) }
                        val uri = androidx.core.content.FileProvider.getUriForFile(this@MainActivity, "$packageName.share", f)
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
                            .putExtra(Intent.EXTRA_SUBJECT, name).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        startActivity(Intent.createChooser(send, "Share log").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    }
                }
                io.max.paperplay.ui.app.BackupFiles.shareFilePath = { name, path ->
                    runCatching {
                        val uri = androidx.core.content.FileProvider.getUriForFile(this@MainActivity, "$packageName.share", File(path))
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
                            .putExtra(Intent.EXTRA_SUBJECT, name).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        startActivity(Intent.createChooser(send, "Share log").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    }
                }
                io.max.paperplay.ui.app.BackupFiles.deviceInfo =
                    "PaperPlay ${packageManager.getPackageInfo(packageName, 0).versionName}, ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.SUPPORTED_ABIS.joinToString()}"
                io.max.paperplay.ui.app.BackupFiles.shareText = { text ->
                    runCatching {
                        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share log"))
                    }
                }
                onDispose {
                    io.max.paperplay.ui.app.BackupFiles.pickDestination = null
                    io.max.paperplay.ui.app.BackupFiles.shareFile = null
                    io.max.paperplay.ui.app.BackupFiles.shareFilePath = null
                    io.max.paperplay.ui.app.BackupFiles.shareText = null
                }
            }
            // The media notification needs this on Android 13 and later; playing works without it.
            val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
            val bluetooth = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
            val audioGrant = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
            // New installs are asked on the onboarding permissions page, after reading why.
            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= 33 && (onboardedFile.exists() || library.exists())) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            // Profile photo: copied into the app's files, so the picked document can be released.
            val photoPending = remember { arrayOfNulls<(String?) -> Unit>(1) }
            val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                val path = uri?.let {
                    runCatching {
                        val dir = File(filesDir, "profile").apply { mkdirs() }
                        dir.listFiles()?.filter { f -> f.name.startsWith("photo-") }?.forEach { f -> f.delete() }
                        val out = File(dir, "photo-${System.currentTimeMillis()}.jpg")
                        contentResolver.openInputStream(it)!!.use { input -> out.outputStream().use { o -> input.copyTo(o) } }
                        out.path
                    }.getOrNull()
                }
                photoPending[0]?.invoke(path)
            }
            val imagePending = remember { arrayOfNulls<(ByteArray?) -> Unit>(1) }
            val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                val bytes = uri?.let { runCatching { contentResolver.openInputStream(it)!!.use { s -> s.readBytes() } }.getOrNull() }
                imagePending[0]?.invoke(bytes)
            }
            val audioPending = remember { arrayOfNulls<(List<String>, Boolean) -> Unit>(1) }
            val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
                val cb = audioPending[0]
                if (uris.isNotEmpty() && cb != null) {
                    Thread {
                        // Songs on the phone's or an SD card's storage are uploaded where they are; only the rest (cloud, Downloads, ...) is copied.
                        val direct = if (granted(audioPermission)) uris.associateWith { pickedFile(it) } else emptyMap()
                        val inPlace = direct.values.filterNotNull().map { it.path }
                        if (inPlace.isNotEmpty()) cb(inPlace, false)
                        val dir = File(cacheDir, "uploads").apply { mkdirs() }
                        val copied = uris.filter { direct[it] == null }.mapNotNull { copyToCache(it, dir) }
                        if (copied.isNotEmpty()) cb(copied, true)
                    }.start()
                }
            }
            val folderPending = remember { arrayOfNulls<(FolderScan) -> Unit>(1) }
            val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
                val cb = folderPending[0]
                if (tree != null && cb != null) Thread {
                    // On the phone's own storage with audio access the songs are read where they are, as the Flutter app did: no copy.
                    val inPlace = if (granted(audioPermission)) treeFolder(tree)?.takeIf { it.listFiles() != null } else null
                    val dir = File(cacheDir, "uploads").apply { mkdirs() }
                    cb(if (inPlace != null) scanFolderPath(inPlace) else scanFolderTree(contentResolver, tree) { copyToCache(it, dir) })
                }.start()
            }
            // Asked once, when a folder is first chosen. Declined: the folder is still read, but its songs are copied first.
            val audioAccess = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { folderPicker.launch(null) }
            val env = remember {
                AppEnv(
                    restore = RestoreHooks(
                        pickBackup = { onPicked -> pending[0] = onPicked; picker.launch(arrayOf("*/*")) },
                        restore = { source, pw -> shared = null; BackupRestorer().restore(source, target, RestoreOptions(password = pw)) },
                    ),
                    libraryExists = { library.exists() },
                    // One AppServices per process. A recreated activity (theme, dark mode, rotation) used to build a second
                    // one beside the first: two Telegram clients on one database and two sockets on the sync port.
                    createServices = {
                        val old = shared
                        // Services shut down for a restore are dead (their scope is cancelled) and are never reused.
                        if (old != null && old.scope.isActive) {
                            services = old
                            auth.onLateReturn { q -> old.scope.launch { runCatching { old.spotify.resume(q) } } }
                            old
                        } else AppServices(AndroidPlatform(applicationContext)).also {
                            shared = it
                            services = it
                            watchActivity(it)
                            auth.onLateReturn { q -> it.scope.launch { runCatching { it.spotify.resume(q) } } }
                        }
                    },
                    open = open,
                    setKeepRunning = { on -> if (on) KeepAliveService.start(applicationContext) else KeepAliveService.stop(applicationContext) },
                    pickPhoto = { onPicked -> photoPending[0] = onPicked; photoPicker.launch(arrayOf("image/*")) },
                    lastCrash = { crashFile.takeIf { it.exists() }?.readText() },
                    clearCrash = { crashFile.delete() },
                    pickAudioFolder = { onPicked ->
                        folderPending[0] = onPicked
                        if (granted(audioPermission)) folderPicker.launch(null) else audioAccess.launch(audioPermission)
                    },
                    pickAudio = { onPicked -> audioPending[0] = onPicked; audioPicker.launch(arrayOf("audio/*", "application/ogg", "application/octet-stream")) },
                    pickImage = { onPicked -> imagePending[0] = onPicked; imagePicker.launch(arrayOf("image/*")) },
                    authRedirect = auth,
                    authRedirectUri = SPOTIFY_REDIRECT,
                    onboarded = { onboardedFile.exists() },
                    setOnboarded = { runCatching { onboardedFile.writeText("1") } },
                    requestNotifications = if (Build.VERSION.SDK_INT >= 33) ({ notifications.launch(Manifest.permission.POST_NOTIFICATIONS) }) else null,
                    permissions = io.max.paperplay.ui.PermissionHooks(
                        check = { permissionStates() },
                        request = { id ->
                            when (id) {
                                "notifications" -> {
                                    val openNotificationSettings = {
                                        openSettings(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    }
                                    if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
                                        askOnce(Manifest.permission.POST_NOTIFICATIONS, { notifications.launch(it) }, openNotificationSettings)
                                    } else openNotificationSettings()
                                }
                                "audio" -> askOnce(audioPermission, { audioGrant.launch(it) }, { openSettings(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, true) })
                                "bluetooth" ->
                                    if (Build.VERSION.SDK_INT >= 31 && !granted(Manifest.permission.BLUETOOTH_CONNECT)) {
                                        askOnce(Manifest.permission.BLUETOOTH_CONNECT, { bluetooth.launch(it) }, { openSettings(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, true) })
                                    } else openSettings(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)
                                "battery" -> openSettings(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                "wifi" -> openSettings(android.provider.Settings.ACTION_WIFI_SETTINGS)
                                "shizuku" -> {
                                    val z = io.max.paperplay.compose.update.ShizukuInstaller(this@MainActivity)
                                    if (z.running) rikka.shizuku.Shizuku.requestPermission(4218) else z.openShizuku()
                                }
                                "install" -> openSettings(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, true)
                                "record_audio" -> askOnce(
                                    Manifest.permission.RECORD_AUDIO,
                                    { recordAudioLauncher.launch(it) },
                                    { openSettings(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, true) }
                                )
                            }
                        },
                        openAppSettings = { openSettings(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, true) },
                    ),
                )
            }
            App(env)
        }
    }

    override fun onDestroy() {
        if (currentInstance === this) currentInstance = null
        if (io.max.paperplay.player.usb.UsbAudioManager.onRequestRecordAudioPermission != null) {
            io.max.paperplay.player.usb.UsbAudioManager.onRequestRecordAudioPermission = null
        }
        super.onDestroy()
    }

    companion object {
        const val OPEN_EXTRA = "open"

        /** The services of this process, shared by every activity instance. Cleared by a restore, which closes them. */
        @Volatile
        var shared: AppServices? = null

        /** True while an activity of the app is visible. */
        @Volatile
        var foreground = false

        @Volatile
        var currentInstance: MainActivity? = null
            private set
    }

    override fun onStart() {
        super.onStart()
        foreground = true
    }

    override fun onResume() {
        super.onResume()
        io.max.paperplay.player.usb.UsbAudioManager.instance?.onActivityResumed()
    }

    override fun onPause() {
        io.max.paperplay.player.usb.UsbAudioManager.instance?.onActivityPaused()
        super.onPause()
    }

    override fun onStop() {
        foreground = false
        super.onStop()
    }
}
