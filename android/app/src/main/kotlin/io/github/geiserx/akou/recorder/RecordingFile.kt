// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou.recorder

import io.github.geiserx.akou.core.ogg.FrameChunker
import io.github.geiserx.akou.core.ogg.OggOpusWriter
import io.github.geiserx.akou.core.ogg.OpusEncoder
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * What the recorder writes to: [RecordingFile] in the app, a tracing wrapper in the tests.
 */
interface RecordingOutput {
    val file: File

    /** The OpusHead and OpusTags pages, the same bytes every live session starts with. */
    val headerPages: List<ByteArray>

    /** Every audio page written to the file, in order; closed by [finish]. */
    val pages: ReceiveChannel<RecordingFile.Page>

    /** 16 kHz mono samples, any count, from any thread. */
    fun append(samples: FloatArray)

    /** Pads and writes the last frame, closes the file and ends [pages]. */
    suspend fun finish(): RecordingFile.Finish
}

/**
 * The recording on disk, the port of RecordingFile.swift. Microphone samples go in, are cut into
 * 20 ms frames, encoded, and every 200 ms Ogg Opus page is appended to the file and handed to
 * [pages] for the live session. The same bytes go to the file and to the socket. All of it runs on
 * one thread of its own, off the main thread and off the capture thread.
 *
 * The file is in credential-encrypted storage (`filesDir`), readable after the first unlock, so a
 * locked phone keeps writing.
 */
class RecordingFile(override val file: File, private val encoder: OpusEncoder) : RecordingOutput {
    class Page(val data: ByteArray, /** Where the page ends in the recording, in seconds. */ val end: Double)

    /** The seconds of audio in the file, and the first write error if there was one. */
    data class Finish(val seconds: Double, val error: String?)

    private val executor = Executors.newSingleThreadExecutor { Thread(it, "akou-recording-file") }
    private val dispatcher = executor.asCoroutineDispatcher()
    private val channel = Channel<Page>(Channel.UNLIMITED)
    override val pages: ReceiveChannel<Page> get() = channel
    override val headerPages: List<ByteArray>

    private val out: FileOutputStream
    private val chunker = FrameChunker()
    private val writer = OggOpusWriter(encoder)

    // Touched only on the executor's thread.
    private var failure: String? = null
    private var finished = false
    @Volatile private var result: Finish? = null

    init {
        file.parentFile?.mkdirs()
        val created = try {
            file.createNewFile()
        } catch (e: IOException) {
            executor.shutdown()
            (encoder as? AutoCloseable)?.close()
            throw e
        }
        if (!created) {
            // Someone else's file: never overwritten, never deleted, and the encoder is freed.
            executor.shutdown()
            (encoder as? AutoCloseable)?.close()
            throw IOException("could not create ${file.name}")
        }
        var stream: FileOutputStream? = null
        try {
            stream = FileOutputStream(file)
            val headers = writer.headerPages()
            for (page in headers) stream.write(page)
            out = stream
            headerPages = headers
        } catch (e: Exception) {
            runCatching { stream?.close() }
            file.delete()
            executor.shutdown()
            (encoder as? AutoCloseable)?.close()
            throw e
        }
    }

    override fun append(samples: FloatArray) {
        try {
            executor.execute { encode(samples) }
        } catch (_: RejectedExecutionException) {
            // Finished: late samples from a stopping microphone have nowhere to go.
        }
    }

    override suspend fun finish(): Finish {
        result?.let { return it }
        val done = try {
            withContext(dispatcher) { finishOnThread() }
        } finally {
            executor.shutdown()
        }
        return done
    }

    private fun finishOnThread(): Finish {
        result?.let { return it }
        finished = true
        try {
            if (failure == null) chunker.flush()?.let { last -> writer.append(last)?.let(::write) }
            if (failure == null) write(writer.finish())
            out.flush()
            out.fd.sync()
        } catch (e: Exception) {
            failure = failure ?: e.toString()
        }
        runCatching { out.close() }
        (encoder as? AutoCloseable)?.close()
        channel.close()
        return Finish(writer.seconds, failure).also { result = it }
    }

    private fun encode(samples: FloatArray) {
        if (finished || failure != null) return
        try {
            for (frame in chunker.push(samples)) writer.append(frame)?.let(::write)
        } catch (e: Exception) {
            failure = e.toString()
        }
    }

    private fun write(page: ByteArray) {
        out.write(page)
        channel.trySend(Page(page, writer.seconds))
    }
}
