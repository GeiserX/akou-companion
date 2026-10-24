// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou.recorder

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The service's commands. The A1 race: a Stop that came while the Start was still reading the
 * settings found the recorder idle and ended the service, and the Start then opened the microphone
 * with no foreground service. Now the Stop waits for the Start and stops what it made.
 */
class RecordCommandsTests {
    @get:Rule val tmp = TemporaryFolder()

    private val trace = mutableListOf<String>()
    private val target = RecordTarget(UUID.randomUUID(), "https://akou.example.com", "ak_test")

    private fun recorder(scope: kotlinx.coroutines.CoroutineScope, permission: suspend () -> Boolean = { true }) = Recorder(
        scope = scope,
        directory = tmp.root,
        hasPermission = permission,
        makeOutput = { f -> TracedFile(f, trace) },
        makeSource = { sink -> FakeSource(sink, trace, samplesOnStart = 16000) },
        makeLive = { _, _, _ -> FakeLive(trace) },
    )

    @Test
    fun aStopDuringTheStartWaitsAndThenStopsTheRecording() = runTest {
        val r = recorder(backgroundScope)
        val settings = CompletableDeferred<Unit>()
        var ended = 0
        var finished = 0
        r.onFinished = { finished += 1; trace += "onFinished" }
        val c = RecordCommands(r, backgroundScope, resolve = { settings.await(); target to target.id }, endService = { ended += 1 })
        val start = c.start()
        testScheduler.runCurrent()
        val stop = c.stop()
        testScheduler.runCurrent()
        assertEquals(0, ended, "the service must not end under a start that is still going")
        settings.complete(Unit)
        start.join()
        stop.join()
        assertEquals(Recorder.State.Idle, r.state.value, "the late start was stopped, not left recording")
        assertEquals(1, finished)
        assertEquals(0, ended, "a stop that finished a recording ends the service through onFinished")
        assertEquals(listOf("capture.start", "live.start", "capture.stop", "file.finish"), trace.take(4))
    }

    @Test
    fun aStopDuringAStartThatFailsEndsTheService() = runTest {
        val gate = CompletableDeferred<Boolean>()
        val r = recorder(backgroundScope, permission = { gate.await() })
        var ended = 0
        var failed: Exception? = null
        val c = RecordCommands(r, backgroundScope, resolve = { target to target.id }, endService = { ended += 1 }, startFailed = { failed = it })
        c.start()
        testScheduler.runCurrent()
        val stop = c.stop()
        testScheduler.runCurrent()
        assertEquals(0, ended)
        gate.complete(false)
        stop.join()
        assertTrue(failed is Recorder.Failure.MicrophoneDenied)
        assertTrue(ended >= 1, "nothing records: the service ends")
        assertEquals(Recorder.State.Idle, r.state.value)
    }

    @Test
    fun aPauseFromAStaleNotificationEndsAnIdleService() = runTest {
        val r = recorder(backgroundScope)
        var ended = 0
        val c = RecordCommands(r, backgroundScope, resolve = { target to target.id }, endService = { ended += 1 })
        c.pause().join()
        c.resume().join()
        assertEquals(2, ended)
    }

    @Test
    fun commandsRunInOrder() = runTest {
        val r = recorder(backgroundScope)
        val c = RecordCommands(r, backgroundScope, resolve = { target to target.id }, endService = {})
        c.start()
        c.pause()
        c.resume()
        c.stop().join()
        assertEquals(
            listOf("capture.start", "live.start", "capture.pause", "capture.resume", "capture.stop", "file.finish"),
            trace.filter { it.startsWith("capture") || it.startsWith("file") || it == "live.start" },
        )
    }
}
