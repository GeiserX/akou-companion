// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou.recorder

import io.github.geiserx.akou.core.client.LiveSession
import io.github.geiserx.akou.core.client.LiveText
import io.github.geiserx.akou.core.ogg.OpusEncoder
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.io.File

/** An encoder that needs no libopus: three bytes per frame, lookahead 104 (pre-skip 312). */
class FakeEncoder : OpusEncoder, AutoCloseable {
    var frames = 0
    var closed = false
    override val lookahead: Int = 104

    override fun close() {
        closed = true
    }

    override fun encode(frame: FloatArray): ByteArray {
        require(frame.size == OpusEncoder.FRAME_SAMPLES)
        frames += 1
        return byteArrayOf(0x08, frames.toByte(), 0x7f)
    }
}

/** A [RecordingFile] with a fake encoder, whose finish is written into [trace]. */
class TracedFile(file: File, private val trace: MutableList<String>) : RecordingOutput {
    val real = RecordingFile(file, FakeEncoder())
    override val file: File get() = real.file
    override val headerPages: List<ByteArray> get() = real.headerPages
    override val pages: ReceiveChannel<RecordingFile.Page> get() = real.pages
    override fun append(samples: FloatArray) = real.append(samples)

    override suspend fun finish(): RecordingFile.Finish {
        trace += "file.finish"
        return real.finish()
    }
}

/** A sample source that delivers [samplesOnStart] at once; the test delivers more and interrupts it. */
class FakeSource(
    private val sink: (FloatArray) -> Unit,
    private val trace: MutableList<String>,
    private val samplesOnStart: Int = 0,
    private val failStart: Boolean = false,
) : SampleSource {
    /** When true, [stop] throws, as a microphone that fails to release would. */
    var failStop = false

    /** When true, [resume] throws, as a microphone that does not come back would. */
    var failResume = false

    var paused = false
        private set

    override var onInterruption: ((began: Boolean, shouldResume: Boolean) -> Unit)? = null

    override var deliveredSamples = 0L
        private set

    override fun start() {
        trace += "capture.start"
        if (failStart) throw IllegalStateException("no microphone")
        if (samplesOnStart > 0) deliver(samplesOnStart)
    }

    /** Samples from the microphone; a paused source delivers none, as AudioCapture. */
    fun deliver(n: Int) {
        if (paused) return
        deliveredSamples += n
        sink(FloatArray(n))
    }

    /** The system takes the microphone ([began] true) or gives it back. */
    fun interrupt(began: Boolean, shouldResume: Boolean = !began) {
        onInterruption?.invoke(began, shouldResume)
    }

    override fun pause() {
        trace += "capture.pause"
        paused = true
    }

    override fun resume() {
        trace += "capture.resume"
        if (failResume) throw IllegalStateException("still in a call")
        paused = false
    }

    override fun stop() {
        trace += "capture.stop"
        if (failStop) throw IllegalStateException("release failed")
    }
}

/** Live text that records what it is given and emits what a test scripts. */
class FakeLive(private val trace: MutableList<String>) : LiveText {
    private val channel = Channel<LiveSession.Event>(Channel.UNLIMITED)
    override val events: Flow<LiveSession.Event> = channel.receiveAsFlow()
    val pages = mutableListOf<Pair<ByteArray, Double>>()
    var started = false

    fun emit(e: LiveSession.Event) {
        channel.trySend(e)
    }

    override suspend fun start() {
        started = true
        trace += "live.start"
    }

    override suspend fun send(page: ByteArray, endsAt: Double) {
        pages += page to endsAt
    }

    /** When set, [stop] waits for it: a server slow to send its last words. */
    var stopGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    override suspend fun stop() {
        stopGate?.await()
        trace += "live.stop(pages=${pages.size})"
        // As LiveSession: the last words come before events ends.
        channel.trySend(LiveSession.Event.StateChanged(LiveSession.State.Off("stopped")))
        channel.close()
    }
}
