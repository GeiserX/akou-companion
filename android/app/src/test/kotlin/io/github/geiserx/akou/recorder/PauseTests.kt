// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou.recorder

import io.github.geiserx.akou.core.ogg.OggPage
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pause and resume, from RecordingController.swift:114-159: the user's pause never resumes by
 * itself, the system's does when it ends, and the chronometer's base moves by the paused time.
 */
class PauseTests {
    @get:Rule val tmp = TemporaryFolder()

    private val trace = mutableListOf<String>()
    private var source: FakeSource? = null
    private var live: FakeLive? = null
    private var clock = 1_000L
    private val target = RecordTarget(UUID.randomUUID(), "https://akou.example.com", "ak_test")

    private fun recorder(scope: kotlinx.coroutines.CoroutineScope) = Recorder(
        scope = scope,
        directory = tmp.root,
        hasPermission = { true },
        makeOutput = { f -> TracedFile(f, trace) },
        makeSource = { sink -> FakeSource(sink, trace).also { source = it } },
        makeLive = { _, _, _ -> FakeLive(trace).also { live = it } },
        now = { clock },
    )

    @Test
    fun theChronometerBaseMovesByThePausedTime() = runTest {
        val r = recorder(backgroundScope)
        r.start(target)
        assertEquals(Recorder.State.Recording(1_000L), r.state.value)
        clock = 11_000L
        r.pause()
        assertEquals(Recorder.State.Paused(10_000L), r.state.value, "10 s recorded before the pause")
        clock = 20_000L
        assertTrue(r.resume())
        // 10 s recorded, so the chronometer counts from 10 s ago.
        assertEquals(Recorder.State.Recording(10_000L), r.state.value)
        clock = 25_000L
        r.pause()
        assertEquals(Recorder.State.Paused(15_000L), r.state.value, "9 s of pause are not counted")
        clock = 40_000L
        assertTrue(r.resume())
        assertEquals(Recorder.State.Recording(25_000L), r.state.value)
        assertEquals(15_000L, NotificationContent.of(r.state.value, null).let { 40_000L - it.chronometerBase!! })
        r.stop()
    }

    @Test
    fun aSystemPauseResumesWhenItEnds() = runTest {
        val r = recorder(backgroundScope)
        r.start(target)
        clock = 5_000L
        source!!.interrupt(began = true)
        assertEquals(Recorder.State.Paused(4_000L), r.state.value)
        assertTrue(r.pausedBySystem)
        assertTrue(source!!.paused)
        clock = 65_000L
        source!!.interrupt(began = false, shouldResume = true)
        assertEquals(Recorder.State.Recording(61_000L), r.state.value, "resumed, the call's minute not counted")
        assertFalse(r.pausedBySystem)
        assertEquals(listOf("capture.start", "live.start", "capture.pause", "capture.resume"), trace)
        r.stop()
    }

    @Test
    fun aUserPauseNeverResumesByItself() = runTest {
        val r = recorder(backgroundScope)
        r.start(target)
        r.pause()
        assertFalse(r.pausedBySystem)
        // A call comes and goes while the user has paused: the recording stays paused.
        source!!.interrupt(began = true)
        assertFalse(r.pausedBySystem, "a call during a user pause does not make it the system's")
        source!!.interrupt(began = false, shouldResume = true)
        assertTrue(r.state.value is Recorder.State.Paused)
        assertTrue(source!!.paused)
        r.stop()
    }

    @Test
    fun anEndWithoutShouldResumeLeavesItPaused() = runTest {
        val r = recorder(backgroundScope)
        r.start(target)
        source!!.interrupt(began = true)
        source!!.interrupt(began = false, shouldResume = false)
        assertTrue(r.state.value is Recorder.State.Paused)
        assertTrue(r.pausedBySystem, "still the system's pause")
        r.stop()
    }

    @Test
    fun aResumeThatFailsStaysPausedAndTheSystemsEndStillResumes() = runTest {
        val r = recorder(backgroundScope)
        r.start(target)
        source!!.interrupt(began = true)
        // The user taps Resume during the call: the microphone is still taken.
        source!!.failResume = true
        assertFalse(r.resume())
        assertTrue(r.state.value is Recorder.State.Paused)
        assertNotNull(r.lastError.value)
        assertTrue(r.pausedBySystem, "a failed resume keeps the system's pause, so its end resumes")
        source!!.failResume = false
        source!!.interrupt(began = false, shouldResume = true)
        assertTrue(r.state.value is Recorder.State.Recording)
        assertNull(r.lastError.value)
        r.stop()
    }

    @Test
    fun aMicrophoneThatCouldNotBeRebuiltBecomesAPauseNotALostRecording() = runTest {
        val r = recorder(backgroundScope)
        r.start(target)
        source!!.deliver(16_000)
        // What CaptureEngine reports when no new AudioRecord can be built: began, never shouldResume.
        source!!.interrupt(began = true, shouldResume = false)
        assertTrue(r.state.value is Recorder.State.Paused)
        val f = assertNotNull(r.stop())
        assertEquals(1.0, f.seconds)
        val pages = OggPage.readAll(f.file.readBytes())
        assertEquals(OggPage.Flags.END_OF_STREAM, pages.last().flags)
    }

    /**
     * Paused time writes nothing, and the file stays one stream: the pages run on in sequence and
     * the granules go on from where they stopped, with no hole, so the file's length is the time
     * recorded, without the pause.
     */
    @Test
    fun pausedTimeWritesNoPagesAndTheStreamStaysContinuous() = runTest {
        val r = recorder(backgroundScope)
        r.start(target)
        source!!.deliver(16_000)
        clock = 2_000L
        r.pause()
        source!!.deliver(16_000) // nothing comes from a paused microphone
        clock = 62_000L
        assertTrue(r.resume())
        source!!.deliver(8_000)
        val f = assertNotNull(r.stop())
        assertEquals(1.5, f.seconds)
        assertEquals(24_000L, f.deliveredSamples)
        val pages = OggPage.readAll(f.file.readBytes())
        assertEquals((0 until pages.size).map { it.toUInt() }, pages.map { it.sequence })
        assertEquals(1, pages.map { it.serial }.toSet().size)
        val audio = pages.drop(2)
        // A page every 200 ms of audio: 7 full ones and the end of stream, granules 9600 apart.
        assertEquals((1..7).map { it * 9600L } + (312L + 72_000L), audio.map { it.granulePosition })
        // Live text got the same pages, and none for the paused minute.
        assertEquals(listOf(0.2, 0.4, 0.6, 0.8, 1.0, 1.2, 1.4, 1.5), live!!.pages.map { it.second })
    }

    @Test
    fun aPausedRecordingStops() = runTest {
        val r = recorder(backgroundScope)
        r.start(target)
        r.pause()
        assertNotNull(r.stop())
        assertEquals(Recorder.State.Idle, r.state.value)
        assertFalse(r.pausedBySystem)
        // The microphone is let go of: a late interruption from it changes nothing.
        source!!.interrupt(began = false, shouldResume = true)
        assertEquals(Recorder.State.Idle, r.state.value)
    }

    @Test
    fun pauseAndResumeDoNothingWhenIdle() = runTest {
        val r = recorder(backgroundScope)
        r.pause()
        assertFalse(r.resume())
        assertEquals(Recorder.State.Idle, r.state.value)
    }

    @Test
    fun theNotificationFollowsTheState() {
        val rec = NotificationContent.of(Recorder.State.Recording(10_000L), "Office")
        assertEquals(10_000L, rec.chronometerBase)
        assertEquals("To Office", rec.text)
        assertEquals(listOf(NotificationContent.Action.PAUSE, NotificationContent.Action.STOP), rec.actions)
        val paused = NotificationContent.of(Recorder.State.Paused(83_000L), null)
        assertNull(paused.chronometerBase, "the chronometer stops while paused")
        assertEquals("Paused at 1:23", paused.title)
        assertEquals("On this phone only", paused.text)
        assertEquals(listOf(NotificationContent.Action.RESUME, NotificationContent.Action.STOP), paused.actions)
        assertEquals(emptyList(), NotificationContent.of(Recorder.State.Stopping, null).actions)
    }
}
