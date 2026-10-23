// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou.recorder

import io.github.geiserx.akou.core.client.LiveSession
import io.github.geiserx.akou.core.client.LiveTranscript
import io.github.geiserx.akou.core.ogg.OggPage
import io.github.geiserx.akou.core.protocol.LiveToken
import io.github.geiserx.akou.core.protocol.LiveWords
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The recorder's rules, from RecordingController.swift, with a fake microphone and fake live text. */
class RecorderTests {
    @get:Rule val tmp = TemporaryFolder()

    private val trace = mutableListOf<String>()
    private var source: FakeSource? = null
    private var live: FakeLive? = null
    private val target = RecordTarget(UUID.randomUUID(), "https://akou.example.com", "ak_test")

    private fun recorder(
        scope: kotlinx.coroutines.CoroutineScope,
        permission: Boolean = true,
        samplesOnStart: Int = 16000,
        failStart: Boolean = false,
    ) = Recorder(
        scope = scope,
        directory = tmp.root,
        hasPermission = { permission },
        makeOutput = { f -> TracedFile(f, trace) },
        makeSource = { sink -> FakeSource(sink, trace, samplesOnStart, failStart).also { source = it } },
        makeLive = { _, headers, _ ->
            assertEquals(2, headers.size)
            FakeLive(trace).also { live = it }
        },
        now = { 1_000L },
    )

    @Test
    fun stopRunsInTheIosOrderAndCallsOnFinishedLast() = runTest {
        val r = recorder(backgroundScope)
        r.onFinished = { f ->
            trace += "onFinished(state=${r.state.value})"
            assertEquals(1.0, f.seconds)
            assertEquals(16000L, f.deliveredSamples)
        }
        r.start(target)
        assertEquals(Recorder.State.Recording(1_000L), r.state.value)
        live!!.emit(LiveSession.Event.Words(LiveWords(listOf(LiveToken(" hello", 0.5)))))
        yield()
        val finished = assertNotNull(r.stop())
        // The microphone first, then the file, then live text (after every page, the last one too, went to it), then
        // the listener has taken the last event, and onFinished sees the recorder idle.
        assertEquals(
            listOf("capture.start", "live.start", "capture.stop", "file.finish", "live.stop(pages=6)", "onFinished(state=Idle)"),
            trace,
        )
        assertEquals(LiveSession.State.Off("stopped"), r.liveState.value)
        // The transcript closed: the open line became a closed one.
        assertEquals(listOf<LiveTranscript.Item>(LiveTranscript.Line("hello", 0.5, 0.5)), r.transcript.value.items)
        assertNull(r.transcript.value.openLine)
        assertEquals(target.id, finished.serverId)
        assertEquals("${finished.id}.opus", finished.file.name)
        assertNull(r.stop(), "a second stop does nothing")
    }

    @Test
    fun pagesReachLiveTextAsWrittenToTheFile() = runTest {
        val r = recorder(backgroundScope)
        r.start(target)
        val f = assertNotNull(r.stop())
        val bytes = f.file.readBytes()
        val pages = OggPage.readAll(bytes)
        // Two header pages, five audio pages of 200 ms, the end-of-stream page.
        assertEquals(8, pages.size)
        val headerBytes = pages[0].encoded().size + pages[1].encoded().size
        val sent = live!!.pages.map { it.first }.fold(ByteArray(0)) { a, p -> a + p }
        // Every page after the headers, the end-of-stream page included, went to the socket as written.
        assertTrue(bytes.copyOfRange(headerBytes, bytes.size).contentEquals(sent), "the socket gets the file's bytes")
        assertEquals(listOf(0.2, 0.4, 0.6, 0.8, 1.0, 1.0), live!!.pages.map { it.second })
    }

    @Test
    fun aGapReachesTheTranscript() = runTest {
        val r = recorder(backgroundScope)
        r.start(target)
        live!!.emit(LiveSession.Event.Gap(3.0, 33.0))
        yield()
        assertEquals(listOf<LiveTranscript.Item>(LiveTranscript.Gap(3.0, 33.0)), r.transcript.value.items)
        r.stop()
    }

    @Test
    fun noLiveTextWhenTheServerHasNone() = runTest {
        val r = recorder(backgroundScope)
        r.serverSupportsLive = false
        r.start(target)
        assertNull(live)
        assertEquals(LiveSession.State.Off("no_server"), r.liveState.value)
        assertEquals(target.id, assertNotNull(r.stop()).serverId)
    }

    @Test
    fun unknownLiveSupportStillTries() = runTest {
        val r = recorder(backgroundScope)
        r.serverSupportsLive = null
        r.start(target)
        assertTrue(live!!.started)
        r.stop()
    }

    @Test
    fun withoutATargetItRecordsForTheServerIdAlone() = runTest {
        val r = recorder(backgroundScope)
        val id = UUID.randomUUID()
        r.start(null, id)
        assertNull(live)
        assertEquals(id, assertNotNull(r.stop()).serverId)
    }

    @Test
    fun noPermissionNoCapture() = runTest {
        val r = recorder(backgroundScope, permission = false)
        assertFailsWith<Recorder.Failure.MicrophoneDenied> { r.start(target) }
        assertNull(source, "the microphone is never opened before the permission")
        assertEquals(Recorder.State.Idle, r.state.value)
        assertEquals(0, tmp.root.listFiles()!!.size)
    }

    @Test
    fun aFailedCaptureStartDeletesTheFile() = runTest {
        val r = recorder(backgroundScope, failStart = true)
        assertFailsWith<IllegalStateException> { r.start(target) }
        assertEquals(listOf("capture.start", "file.finish"), trace)
        assertEquals(0, tmp.root.listFiles()!!.size)
        assertEquals(Recorder.State.Idle, r.state.value)
        assertNull(live)
    }

    @Test
    fun aSecondStartIsRefused() = runTest {
        val r = recorder(backgroundScope)
        r.start(target)
        assertFailsWith<Recorder.Failure.AlreadyRecording> { r.start(target) }
        r.stop()
        assertFalse(r.state.value is Recorder.State.Recording)
    }
}
