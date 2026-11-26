package io.max.paperplay.ui

import android.app.Activity
import android.content.ContextWrapper
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow

@Composable
actual fun PlatformBackHandler(
    enabled: Boolean,
    onProgress: (Float) -> Unit,
    onCancel: () -> Unit,
    onBack: () -> Unit,
) {
    PredictiveBackHandler(enabled = enabled) { progress: Flow<BackEventCompat> ->
        var started = false
        try {
            progress.collect {
                started = true
                onProgress(it.progress)
            }
            onBack()
        } catch (_: CancellationException) {
            if (started) onCancel() else onProgress(0f)
        }
    }
}

@Composable
actual fun SystemBarAppearanceEffect(darkIcons: Boolean) {
    val view = LocalView.current
    val ctx = LocalContext.current
    SideEffect {
        val window = generateSequence(ctx) { (it as? ContextWrapper)?.baseContext }
            .filterIsInstance<Activity>()
            .firstOrNull()
            ?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = darkIcons
            isAppearanceLightNavigationBars = darkIcons
        }
    }
}

@Composable
actual fun rememberSystemReduceMotion(): Boolean {
    val ctx = LocalContext.current
    fun readScaleZero(): Boolean = runCatching {
        android.provider.Settings.Global.getFloat(
            ctx.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }.getOrDefault(false)

    var reduced by remember { mutableStateOf(readScaleZero()) }
    DisposableEffect(ctx) {
        val uri = android.provider.Settings.Global.getUriFor(android.provider.Settings.Global.ANIMATOR_DURATION_SCALE)
        val observer = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reduced = readScaleZero()
            }
        }
        runCatching { ctx.contentResolver.registerContentObserver(uri, false, observer) }
        onDispose { runCatching { ctx.contentResolver.unregisterContentObserver(observer) } }
    }
    return reduced
}

@Composable
actual fun rememberAppForeground(): Boolean {
    val owner = generateSequence(LocalContext.current) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<LifecycleOwner>().firstOrNull()
    var foreground by remember { mutableStateOf(owner?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) ?: true) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, _ ->
            val atLeastStarted = owner?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) ?: true
            foreground = atLeastStarted
            AppForegroundState.isForeground = atLeastStarted
            AppForegroundState.isVisible = atLeastStarted
        }
        owner?.lifecycle?.addObserver(observer)
        onDispose { owner?.lifecycle?.removeObserver(observer) }
    }
    return foreground
}

actual fun androidx.compose.ui.Modifier.mouseBackButton(onBack: () -> Unit): androidx.compose.ui.Modifier = this

actual fun shrinkImageBytes(bytes: ByteArray, maxSide: Int): ByteArray? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    if (longest <= maxSide) return@runCatching null
    var sample = 1
    while (longest / (sample * 2) >= maxSide) sample *= 2
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return@runCatching null
    val scale = maxSide.toFloat() / maxOf(decoded.width, decoded.height)
    val out = if (scale < 1f) android.graphics.Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt().coerceAtLeast(1), (decoded.height * scale).toInt().coerceAtLeast(1), true) else decoded
    val buf = java.io.ByteArrayOutputStream()
    out.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, buf)
    if (out !== decoded) out.recycle()
    decoded.recycle()
    buf.toByteArray()
}.getOrNull()

actual fun decodeImageFile(path: String, maxSide: Int): ImageBitmap? = runCatching {
    if (path.startsWith("http://") || path.startsWith("https://")) {
        val conn = (URI.create(path).toURL().openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("User-Agent", "PaperPlay/0.1.0")
        }
        val bytes = try {
            if (conn.responseCode !in 200..299) return@runCatching null
            conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        return readyToDraw(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }), maxSide)
    }

    val file = File(path)
    if (file.exists()) {
        val ext = file.extension.lowercase()
        if (ext in setOf("mp3", "flac", "m4a", "aac", "ogg", "wav", "opus")) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(path)
                val art = retriever.embeddedPicture
                if (art != null) {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(art, 0, art.size, bounds)
                    var sample = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
                    return readyToDraw(BitmapFactory.decodeByteArray(art, 0, art.size, BitmapFactory.Options().apply { inSampleSize = sample }), maxSide)
                }
            } finally {
                runCatching { retriever.release() }
            }
        }
    }

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
    readyToDraw(BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }), maxSide)
}.getOrNull()

/**
 * A decoded cover, scaled down to the size asked for (a power-of-two sample can leave it up to twice as big, which cost memory and a
 * slow first draw) and uploaded to the graphics memory now, on the decoding thread, so the first frame that shows it does not stall.
 */
private fun readyToDraw(decoded: android.graphics.Bitmap?, maxSide: Int): ImageBitmap? {
    decoded ?: return null
    val longest = maxOf(decoded.width, decoded.height)
    val out = if (longest > maxSide * 5 / 4) {
        val k = maxSide.toFloat() / longest
        android.graphics.Bitmap.createScaledBitmap(decoded, (decoded.width * k).toInt().coerceAtLeast(1), (decoded.height * k).toInt().coerceAtLeast(1), true)
            .also { if (it !== decoded) decoded.recycle() }
    } else decoded
    out.prepareToDraw()
    return out.asImageBitmap()
}

actual fun localTimeLabel(epochMs: Long): String =
    java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(epochMs), java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("dd/MM HH:mm:ss"))

actual fun currentHour(): Int = java.time.LocalTime.now().hour

actual val isPhone: Boolean = true

actual fun nowMoment(): io.max.paperplay.core.recs.Moment {
    val t = java.time.LocalDateTime.now()
    return io.max.paperplay.core.recs.Moment(System.currentTimeMillis(), t.year, t.monthValue, t.dayOfMonth, t.hour)
}
