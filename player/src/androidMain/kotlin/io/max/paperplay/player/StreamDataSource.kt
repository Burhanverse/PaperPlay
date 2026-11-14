package io.max.paperplay.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException
import java.io.InterruptedIOException

/** Range-reads a [StreamSource] looked up by the DataSpec uri. */
@OptIn(UnstableApi::class)
internal class StreamDataSource(private val sources: Map<String, StreamSource>) : BaseDataSource(false) {
    private var uri: Uri? = null
    private var source: StreamSource? = null
    private var position = 0L
    private var remaining = 0L
    private var opened = 0L
    private var firstRead = true
    private var reads = 0

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val s = sources[dataSpec.uri.toString()] ?: throw IOException("Unknown stream ${dataSpec.uri}")
        if (dataSpec.position > s.size) {
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }
        source = s
        uri = dataSpec.uri
        position = dataSpec.position
        val left = s.size - position
        remaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) left else minOf(dataSpec.length, left)
        opened = position
        firstRead = true
        reads = 0
        PlayLog.log("stream", "open ${dataSpec.uri} from byte $position, will serve $remaining of ${s.size} bytes (requested length ${dataSpec.length})")
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) {
            PlayLog.log("stream", "end of data at byte $position of ${source?.size} (all promised bytes served)")
            return C.RESULT_END_OF_INPUT
        }
        val want = minOf(length.toLong(), remaining).toInt()
        val t0 = System.nanoTime()
        val chunk = try {
            source!!.read(position, want)
        } catch (e: IOException) {
            PlayLog.log("stream", "read failed at byte $position (wanted $want): ${e::class.java.simpleName} ${e.message}")
            throw e
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException(e.message ?: "Stream read interrupted").apply { initCause(e) }
        } catch (e: Exception) {
            PlayLog.log("stream", "read failed at byte $position (wanted $want): ${e::class.java.simpleName} ${e.message}")
            throw IOException(e.message ?: "Stream read failed", e)
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        reads++
        if (firstRead) { firstRead = false; PlayLog.log("stream", "first read: ${chunk.size} bytes at $position took ${ms}ms") }
        else if (ms >= 1000) PlayLog.log("stream", "slow read: ${chunk.size} bytes at $position took ${ms}ms")
        if (chunk.isEmpty()) {
            PlayLog.log("stream", "EMPTY read at byte $position of ${source?.size} (wanted $want, $remaining still promised): the player takes this as the end of the song")
            return C.RESULT_END_OF_INPUT
        }
        if (chunk.size < want && remaining - chunk.size > 0) PlayLog.log("stream", "short read: got ${chunk.size} of $want at byte $position")
        System.arraycopy(chunk, 0, buffer, offset, chunk.size)
        position += chunk.size
        remaining -= chunk.size
        bytesTransferred(chunk.size)
        return chunk.size
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        if (source != null) {
            PlayLog.log("stream", "close ${uri} at byte $position of ${source?.size}, read ${position - opened} bytes in $reads reads, $remaining promised bytes never read")
            transferEnded()
        }
        source = null
        uri = null
    }
}

/** Sends registered stream keys to [StreamDataSource], everything else to [upstream]. */
@OptIn(UnstableApi::class)
internal class RoutingDataSource(
    private val sources: Map<String, StreamSource>,
    private val stream: StreamDataSource,
    private val upstream: DataSource,
) : DataSource {
    private var active: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        stream.addTransferListener(transferListener)
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val d = if (sources.containsKey(dataSpec.uri.toString())) stream else upstream
        active = d
        return d.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int) = active!!.read(buffer, offset, length)
    override fun getUri(): Uri? = active?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders ?: emptyMap()
    override fun close() {
        active?.close()
        active = null
    }
}
