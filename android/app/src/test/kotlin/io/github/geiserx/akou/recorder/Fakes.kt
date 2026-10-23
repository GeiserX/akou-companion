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
class FakeEncoder : OpusEncoder {
    var frames = 0
    var closed = false
    override val lookahead: Int = 104

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

/** A sample source that delivers [samplesOnStart] at once and [samplesOnStop] just before it stops. */
class FakeSource(
    private val sink: (FloatArray) -> Unit,
    private val trace: MutableList<String>,
    private val samplesOnStart: Int = 0,
    private val failStart: Boolean = false,
) : SampleSource {
    override var deliveredSamples = 0L
        private set

    override fun start() {
        trace += "capture.start"
        if (failStart) throw IllegalStateException("no microphone")
        if (samplesOnStart > 0) deliver(samplesOnStart)
    }

    fun deliver(n: Int) {
        deliveredSamples += n
        sink(FloatArray(n))
    }

    override fun stop() {
        trace += "capture.stop"
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

    override suspend fun stop() {
        trace += "live.stop(pages=${pages.size})"
        // As LiveSession: the last words come before events ends.
        channel.trySend(LiveSession.Event.StateChanged(LiveSession.State.Off("stopped")))
        channel.close()
    }
}
