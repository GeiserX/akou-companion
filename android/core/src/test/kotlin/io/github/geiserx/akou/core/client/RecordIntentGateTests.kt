// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou.core.client

import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeRecorder : RecordIntentRecorder {
    enum class State { IDLE, RECORDING, PAUSED }
    class Refused : Exception()

    var state = State.IDLE
    var refuseStart = false
    var refuseResume = false
    val calls = mutableListOf<String>()
    var startedIn: String? = null
        private set

    override val isRecording: Boolean get() = state != State.IDLE
    override val isPaused: Boolean get() = state == State.PAUSED

    override suspend fun start(workspace: String?, title: String?) {
        calls.add("start(${title ?: "nil"})")
        startedIn = workspace
        if (refuseStart) throw Refused()
        state = State.RECORDING
    }

    override fun resume() {
        calls.add("resume")
        if (refuseResume) return
        state = State.RECORDING
    }

    override suspend fun stopRecording() {
        calls.add("stop")
        state = State.IDLE
    }
}

/** Tests/AkouKitTests/RecordIntentGateTests.swift. */
class RecordIntentGateTests {
    private val statuses = mutableListOf<Boolean>()
    private val note: (Boolean) -> Unit = { statuses.add(it) }

    @BeforeTest
    fun setUp() {
        statuses.clear()
    }

    @Test
    fun testStartsWhenIdle() = runTest {
        val r = FakeRecorder()
        RecordIntentGate.start(r, title = "Standup", onStatus = note)
        assertEquals(listOf("start(Standup)"), r.calls)
        assertEquals(listOf(true), statuses)
    }

    @Test
    fun testStartsInTheWorkspaceItIsGiven() = runTest {
        val r = FakeRecorder()
        RecordIntentGate.start(r, title = null, workspace = "home", onStatus = note)
        assertEquals("home", r.startedIn)
        val none = FakeRecorder()
        RecordIntentGate.start(none, title = null, onStatus = note)
        assertNull(none.startedIn)
    }

    @Test
    fun testDoesNotStartASecondRecording() = runTest {
        val r = FakeRecorder()
        r.state = FakeRecorder.State.RECORDING
        RecordIntentGate.start(r, title = null, onStatus = note)
        assertEquals(emptyList(), r.calls)
        assertEquals(listOf(true), statuses)
    }

    @Test
    fun testResumesAPausedRecordingInsteadOfStartingOne() = runTest {
        val r = FakeRecorder()
        r.state = FakeRecorder.State.PAUSED
        RecordIntentGate.start(r, title = null, onStatus = note)
        assertEquals(listOf("resume"), r.calls)
        assertEquals(listOf(true), statuses)
    }

    @Test
    fun testAFailedResumeThrowsAndStaysPaused() = runTest {
        val r = FakeRecorder()
        r.state = FakeRecorder.State.PAUSED
        r.refuseResume = true
        assertFailsWith<RecordIntentGateException.ResumeFailed>("a resume that left the recording paused must not report success") {
            RecordIntentGate.start(r, title = null, onStatus = note)
        }
        assertEquals(listOf("resume"), r.calls)
        assertTrue(r.isPaused)
        // A paused recording still holds its file, so the control keeps showing it.
        assertEquals(listOf(true), statuses)
    }

    @Test
    fun testAFailedStartStillReportsNotRecording() = runTest {
        val r = FakeRecorder()
        r.refuseStart = true
        assertFailsWith<FakeRecorder.Refused>("the recorder's error must reach the intent") {
            RecordIntentGate.start(r, title = null, onStatus = note)
        }
        assertEquals(listOf(false), statuses)
    }

    @Test
    fun testStopsARunningRecording() = runTest {
        val r = FakeRecorder()
        r.state = FakeRecorder.State.RECORDING
        RecordIntentGate.stop(r, onStatus = note)
        assertEquals(listOf("stop"), r.calls)
        assertEquals(listOf(false), statuses)
    }

    @Test
    fun testStopsAPausedRecording() = runTest {
        val r = FakeRecorder()
        r.state = FakeRecorder.State.PAUSED
        RecordIntentGate.stop(r, onStatus = note)
        assertEquals(listOf("stop"), r.calls)
        assertEquals(listOf(false), statuses)
    }

    @Test
    fun testStopWithNothingRunningDoesNothing() = runTest {
        val r = FakeRecorder()
        RecordIntentGate.stop(r, onStatus = note)
        assertEquals(emptyList(), r.calls)
        assertEquals(listOf(false), statuses)
    }
}
