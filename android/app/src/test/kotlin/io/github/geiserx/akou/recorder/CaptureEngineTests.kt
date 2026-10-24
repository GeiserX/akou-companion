// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou.recorder

import org.junit.After
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A scripted microphone: each [read] takes the next entry, a sample count (delivered as that many
 * samples of [value]) or a negative AudioRecord error. Blocks like AudioRecord until there is one or
 * it is stopped.
 */
class FakeMicrophone(val events: Microphone.Events, override val rate: Int = 16000, private val value: Float = 0.5f) : Microphone {
    val script = LinkedBlockingQueue<Int>()
    val starts = AtomicInteger(0)
    var failStart = false
    @Volatile var recording = false
    @Volatile var released = false

    /** The system stopped it, as a lost route does: reads return 0 at once. */
    fun lose() {
        recording = false
    }

    override fun start(): Boolean {
        starts.incrementAndGet()
        if (failStart) return false
        recording = true
        return true
    }

    override fun stop() {
        recording = false
    }

    override val isRecording: Boolean get() = recording

    override fun read(buffer: FloatArray, count: Int): Int {
        while (recording && !released) {
            val n = script.poll(5, TimeUnit.MILLISECONDS) ?: continue
            if (!recording) {
                // Stopped while waiting: a stopped AudioRecord records nothing more.
                script.add(n)
                return 0
            }
            if (n < 0) return n
            val k = minOf(n, count)
            buffer.fill(value, 0, k)
            if (n > k) script.add(n - k) // the rest on the next read; order is fine for these tests
            return k
        }
        return 0
    }

    override fun release() {
        released = true
        recording = false
    }
}

class CaptureEngineTests {
    private val mics = mutableListOf<FakeMicrophone>()
    private val delivered = LinkedBlockingQueue<FloatArray>()
    private val main = LinkedBlockingQueue<Runnable>()
    private val interruptions = mutableListOf<Pair<Boolean, Boolean>>()

    /** Null makes the next opens fail, as a dead audio server does. */
    @Volatile private var canOpen = true
    private lateinit var engine: CaptureEngine

    private val openCalls = AtomicInteger(0)

    private fun makeEngine(attempts: Int = 3, delayMs: Long = 10): CaptureEngine = CaptureEngine(
        open = { events ->
            openCalls.incrementAndGet()
            if (canOpen) FakeMicrophone(events).also { synchronized(mics) { mics += it } } else null
        },
        sink = { delivered.add(it) },
        post = { main.add(it) },
        rebuildAttempts = attempts,
        rebuildDelayMs = delayMs,
    ).also {
        engine = it
        it.onInterruption = { began, resume -> interruptions += began to resume }
    }

    @After
    fun tearDown() {
        if (::engine.isInitialized) engine.stop()
    }

    private fun mic(i: Int) = synchronized(mics) { mics[i] }

    /** Samples delivered so far, waiting until [n] have come. */
    private fun awaitDelivered(n: Long) {
        val until = System.nanoTime() + 3_000_000_000
        while (engine.deliveredSamples < n) {
            if (System.nanoTime() > until) fail("only ${engine.deliveredSamples} of $n samples delivered")
            Thread.sleep(2)
        }
    }

    private fun awaitTrue(what: String, cond: () -> Boolean) {
        val until = System.nanoTime() + 3_000_000_000
        while (!cond()) {
            if (System.nanoTime() > until) fail(what)
            Thread.sleep(2)
        }
    }

    /** Runs what was posted to the main thread, waiting up to a moment for the first. */
    private fun drainMain(wait: Boolean = true) {
        if (wait) main.poll(3, TimeUnit.SECONDS)?.run() ?: fail("nothing was posted to the main thread")
        while (true) main.poll()?.run() ?: break
    }

    @Test
    fun aUserPauseFreesTheMicrophoneAndDeliversNothingUntilResume() {
        val e = makeEngine()
        e.start()
        mic(0).script.add(320)
        awaitDelivered(320)
        e.pause()
        assertFalse(mic(0).recording, "a user pause stops the AudioRecord")
        mic(0).script.add(320) // nothing reads while stopped
        Thread.sleep(50)
        assertEquals(320L, e.deliveredSamples)
        e.resume()
        assertEquals(2, mic(0).starts.get(), "the same AudioRecord starts again")
        awaitDelivered(640)
        assertEquals(1, mics.size)
    }

    @Test
    fun aSilencedInputIsAnInterruptionAndItsEndResumes() {
        val e = makeEngine()
        e.start()
        mic(0).events.silenced(true)
        // What the system writes into a silenced input, before the recorder has paused.
        mic(0).script.add(320)
        drainMain()
        assertEquals(listOf(true to false), interruptions)
        Thread.sleep(50)
        assertEquals(0L, e.deliveredSamples, "silenced samples never reach the file, paused or not")
        // The recorder pauses on that; the microphone stays open so the end can be heard.
        e.pause()
        assertTrue(mic(0).recording, "a pause while silenced keeps the AudioRecord recording")
        mic(0).script.add(320)
        Thread.sleep(50)
        assertEquals(0L, e.deliveredSamples, "silenced samples never reach the file")
        assertFailsWith<CaptureEngine.Failure> { e.resume() }
        mic(0).events.silenced(false)
        drainMain()
        assertEquals(listOf(true to false, false to true), interruptions)
        e.resume()
        mic(0).script.add(320)
        awaitDelivered(320)
        assertEquals(1, mic(0).starts.get())
    }

    @Test
    fun aDeadObjectBuildsANewMicrophoneAndGoesOn() {
        val e = makeEngine()
        e.start()
        mic(0).script.addAll(listOf(320, Microphone.ERROR_DEAD_OBJECT))
        awaitTrue("a new microphone") { synchronized(mics) { mics.size } == 2 }
        assertTrue(mic(0).released)
        mic(1).script.add(320)
        awaitDelivered(640)
        assertTrue(main.isEmpty(), "no interruption: the recording never paused")
        assertEquals(2, e.opened)
    }

    @Test
    fun aDeadObjectWithNoNewMicrophoneBecomesAPause() {
        val e = makeEngine(attempts = 3)
        e.start()
        canOpen = false
        mic(0).script.addAll(listOf(320, Microphone.ERROR_DEAD_OBJECT))
        drainMain()
        assertEquals(listOf(true to false), interruptions, "began, and never shouldResume")
        assertEquals(320L, e.deliveredSamples)
        // The recorder pauses; once the audio server is back, Resume builds a new microphone.
        e.pause()
        canOpen = true
        e.resume()
        mic(1).script.add(320)
        awaitDelivered(640)
    }

    /** A Pause and Resume while the rebuild waits between tries: Resume's microphone is kept, none leaks. */
    @Test
    fun aResumeDuringTheRebuildWaitIsNeverReplaced() {
        val e = makeEngine(attempts = 3, delayMs = 300)
        e.start()
        canOpen = false
        mic(0).script.add(Microphone.ERROR_DEAD_OBJECT)
        awaitTrue("a first rebuild try") { openCalls.get() >= 2 }
        canOpen = true
        e.pause()
        e.resume()
        assertEquals(2, mics.size, "Resume built a microphone")
        Thread.sleep(700) // past the remaining tries
        assertEquals(2, mics.size, "the rebuild did not build another over it")
        assertFalse(mic(1).released)
        mic(1).script.add(320)
        awaitDelivered(320)
    }

    @Test
    fun aMicrophoneThatStoppedByItselfRestartsTheSameObject() {
        val e = makeEngine()
        e.start()
        mic(0).script.add(320)
        awaitDelivered(320)
        mic(0).lose()
        awaitTrue("restarted") { mic(0).starts.get() == 2 }
        mic(0).script.add(320)
        awaitDelivered(640)
        assertEquals(1, mics.size, "the same AudioRecord, no new one")
    }

    @Test
    fun eventsFromAReplacedMicrophoneAreIgnored() {
        val e = makeEngine()
        e.start()
        val old = mic(0)
        old.script.add(Microphone.ERROR_DEAD_OBJECT)
        awaitTrue("a new microphone") { synchronized(mics) { mics.size } == 2 }
        old.events.silenced(true)
        Thread.sleep(20)
        drainMain(wait = false)
        assertTrue(interruptions.isEmpty())
    }

    @Test
    fun afterStopTheSinkIsNotCalledAgain() {
        val e = makeEngine()
        e.start()
        mic(0).script.add(320)
        awaitDelivered(320)
        e.stop()
        assertTrue(mic(0).released)
        mic(0).script.add(320)
        Thread.sleep(30)
        assertEquals(320L, e.deliveredSamples)
        assertFailsWith<CaptureEngine.Failure> { e.resume() }
    }

    @Test
    fun aMicrophoneThatDoesNotOpenFailsTheStart() {
        canOpen = false
        val e = makeEngine()
        assertFailsWith<CaptureEngine.Failure> { e.start() }
    }
}
