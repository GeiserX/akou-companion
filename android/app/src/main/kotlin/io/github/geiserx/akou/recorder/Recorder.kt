// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou.recorder

import io.github.geiserx.akou.core.client.LiveSession
import io.github.geiserx.akou.core.client.LiveText
import io.github.geiserx.akou.core.client.LiveTranscript
import io.github.geiserx.akou.core.protocol.LiveHello
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Where a recording goes: live text there, and the upload. */
data class RecordTarget(val id: UUID, val url: String, val key: String)

/** A recording that stopped: its file is complete and ready to upload. */
data class FinishedRecording(
    /** Also the upload's `Idempotency-Key`, so a retried upload finds the first job. */
    val id: UUID,
    /** `filesDir/Recordings/<id>.opus`. */
    val file: File,
    val startedAt: Long,
    /** Seconds of audio in the file, as [RecordingFile.Finish] reports them. */
    val seconds: Double,
    /** 16 kHz samples the microphone delivered, to compare with [seconds]. */
    val deliveredSamples: Long,
    /** `auto` or a BCP 47 code, as the live session asked for it. */
    val language: String,
    val model: String,
    /** The server it was recorded for: its upload goes there. */
    val serverId: UUID?,
)

/** What the Record screen shows of the live text: closed lines and gaps, and the line still growing. */
data class TranscriptSnapshot(val items: List<LiveTranscript.Item> = emptyList(), val openLine: LiveTranscript.Line? = null)

/**
 * The one recorder, the port of RecordingController.swift (start and stop; pausing comes in A2).
 * [RecordingService] owns it and runs every call on the main thread, as iOS runs the controller on
 * the main actor.
 *
 * The file is the recording; live text is a bonus that never holds it up. With no server, no key,
 * or a server without live text, it records just the same.
 */
class Recorder(
    private val scope: CoroutineScope,
    private val directory: File,
    private val hasPermission: suspend () -> Boolean,
    private val makeOutput: (File) -> RecordingOutput,
    private val makeSource: (sink: (FloatArray) -> Unit) -> SampleSource,
    private val makeLive: (RecordTarget, List<ByteArray>, LiveHello) -> LiveText = { t, headers, hello ->
        LiveSession(t.url, t.key, hello, headers)
    },
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> UUID = UUID::randomUUID,
) {
    sealed interface State {
        data object Idle : State

        data class Recording(val startedAt: Long) : State

        data object Stopping : State
    }

    sealed class Failure(message: String) : Exception(message) {
        class MicrophoneDenied : Failure("akou needs the microphone")

        class AlreadyRecording : Failure("already recording")
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * Nothing is recording or stopping. A Stop that finds the recorder busy stopping must leave the
     * service up: the stop under way ends it through [onFinished].
     */
    val isIdle: Boolean get() = _state.value == State.Idle && current == null

    private val _transcript = MutableStateFlow(TranscriptSnapshot())
    val transcript: StateFlow<TranscriptSnapshot> = _transcript.asStateFlow()

    private val _liveState = MutableStateFlow<LiveSession.State>(LiveSession.State.Off("idle"))
    val liveState: StateFlow<LiveSession.State> = _liveState.asStateFlow()

    /** A write error from the last recording, if any. */
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /**
     * From the Record screen's server check: false means the server has no live text, so no socket
     * is opened. Null (not checked) still tries.
     */
    @Volatile
    var serverSupportsLive: Boolean? = null

    /** Called with every recording that stops, after the state is back to idle. */
    var onFinished: (FinishedRecording) -> Unit = {}

    private class Current(
        val id: UUID,
        val output: RecordingOutput,
        val source: SampleSource,
        val live: LiveText?,
        val startedAt: Long,
        val language: String,
        val model: String,
        val serverId: UUID?,
        val forwarder: Job,
        var listener: Job? = null,
    )

    private var current: Current? = null
    private var transcriptModel = LiveTranscript()

    /**
     * Starts a recording for [target] (live text and upload), or for [serverId] alone when the
     * server's URL or key is missing, so it is still queued for that server.
     */
    suspend fun start(target: RecordTarget?, serverId: UUID? = target?.id) {
        if (_state.value != State.Idle || current != null) throw Failure.AlreadyRecording()
        if (!hasPermission()) throw Failure.MicrophoneDenied()
        if (_state.value != State.Idle || current != null) throw Failure.AlreadyRecording()

        val id = newId()
        val output = makeOutput(File(directory, "$id.opus"))
        val source = makeSource { samples -> output.append(samples) }
        try {
            source.start()
        } catch (e: Exception) {
            output.finish()
            output.file.delete()
            throw e
        }

        // A1 has no language or model setting yet: auto, as the upload reads it.
        val hello = LiveHello()
        val live = if (serverSupportsLive != false && target != null) makeLive(target, output.headerPages, hello) else null
        val forwarder = scope.launch {
            for (page in output.pages) live?.send(page.data, page.end)
        }
        val startedAt = now()
        val rec = Current(id, output, source, live, startedAt, hello.language, hello.model, target?.id ?: serverId, forwarder)
        current = rec
        transcriptModel = LiveTranscript()
        publishTranscript()
        _lastError.value = null
        _state.value = State.Recording(startedAt)

        if (live != null) {
            _liveState.value = LiveSession.State.Connecting
            rec.listener = scope.launch { live.events.collect(::handle) }
            live.start()
        } else {
            _liveState.value = LiveSession.State.Off("no_server")
        }
    }

    /**
     * Stops the recording. The order is RecordingController.swift's: the microphone, the file, the
     * forwarder, live text, then the listener is awaited (never cancelled, so it takes the last
     * words), the transcript closes, and only then is the state idle and [onFinished] called.
     */
    suspend fun stop(): FinishedRecording? {
        val rec = current ?: return null
        if (_state.value !is State.Recording) return null
        _state.value = State.Stopping
        val (seconds, error) = try {
            rec.source.stop()
            val finish = rec.output.finish()
            rec.forwarder.join()
            rec.live?.stop()
            rec.listener?.join()
            finish
        } catch (e: Exception) {
            // A shutdown step threw: still close the file, then leave Stopping so the service can
            // end and the next Start works. The caller sees the exception.
            withContext(NonCancellable) { runCatching { rec.output.finish() } }
            rec.listener?.cancel()
            current = null
            _lastError.value = "Stopping failed: ${e.message ?: e.javaClass.simpleName}"
            _state.value = State.Idle
            throw e
        }
        transcriptModel.close()
        publishTranscript()
        current = null
        _lastError.value = error
        val finished = FinishedRecording(
            id = rec.id, file = rec.output.file, startedAt = rec.startedAt, seconds = seconds,
            deliveredSamples = rec.source.deliveredSamples, language = rec.language, model = rec.model,
            serverId = rec.serverId,
        )
        _state.value = State.Idle
        onFinished(finished)
        return finished
    }

    private fun handle(event: LiveSession.Event) {
        when (event) {
            is LiveSession.Event.Words -> transcriptModel.append(event.words)
            is LiveSession.Event.Gap -> transcriptModel.gap(event.from, event.to)
            is LiveSession.Event.StateChanged -> _liveState.value = event.state
        }
        publishTranscript()
    }

    private fun publishTranscript() {
        _transcript.value = TranscriptSnapshot(transcriptModel.items, transcriptModel.openLine)
    }
}
