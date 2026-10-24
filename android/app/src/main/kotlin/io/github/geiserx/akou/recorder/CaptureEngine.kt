// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou.recorder

import java.util.concurrent.atomic.AtomicLong

/**
 * One opened microphone: an `AudioRecord` in the app ([AudioRecordMicrophone]), a fake in the JVM
 * tests. [CaptureEngine] drives it.
 */
interface Microphone {
    /** What the opened microphone reports to the engine, on the main thread. */
    interface Events {
        /** `isClientSilenced` changed: a call or the Assistant took the input, or gave it back. */
        fun silenced(on: Boolean)
    }

    /** 16000, or 48000 on the fallback path. */
    val rate: Int

    /** Starts (or restarts) recording on this microphone; false when it did not start. */
    fun start(): Boolean

    /** Stops recording; a blocking [read] returns. Never throws. */
    fun stop()

    /** True while it is recording. A microphone the system stopped (a lost route) reads false. */
    val isRecording: Boolean

    /** A blocking read of up to [count] samples: the count read, or a negative `AudioRecord` error. */
    fun read(buffer: FloatArray, count: Int): Int

    /** Frees it for good. Never throws. */
    fun release()

    companion object {
        /** `AudioRecord.ERROR_DEAD_OBJECT`: the audio server restarted and this object is gone. */
        const val ERROR_DEAD_OBJECT = -6
    }
}

/**
 * The microphone's rules, apart from Android so the JVM tests drive them with a fake [Microphone]:
 * the port of AudioCapture.swift's pause, resume, interruption, route-change and media-server-reset
 * handling. Reads run on one thread of their own; [start], [pause], [resume], [stop] and the
 * [Microphone.Events] run on the main thread; [onInterruption] is handed to [post] (the main thread).
 *
 * - A user pause stops the microphone, so the system shows it free. A pause while the system has
 *   silenced the input (a call) keeps it open but drops its samples: Android reports the end of the
 *   silencing only to a microphone that is still recording, and that end is what resumes.
 * - A microphone that stopped by itself (a lost route) is restarted: the same object first, as
 *   AudioCapture.swift:122-134 keeps the same file; a new one if that fails.
 * - `ERROR_DEAD_OBJECT` (the audio server restarted) builds a new microphone and goes on into the
 *   same stream (AudioCapture.swift:135-150). If none can be built, it reports an interruption, so
 *   the recorder pauses: the file so far stays whole and a Resume can try again. Never a lost recording.
 */
class CaptureEngine(
    private val open: (Microphone.Events) -> Microphone?,
    private val sink: (FloatArray) -> Unit,
    private val post: (Runnable) -> Unit,
    /** New microphones tried after a dead object, [rebuildDelayMs] apart: the audio server takes a moment to return. */
    private val rebuildAttempts: Int = 6,
    private val rebuildDelayMs: Long = 500,
    private val log: (String) -> Unit = {},
) : SampleSource {
    class Failure(message: String) : Exception(message)

    override var onInterruption: ((began: Boolean, shouldResume: Boolean) -> Unit)? = null

    private val delivered = AtomicLong(0)
    override val deliveredSamples: Long get() = delivered.get()

    /** The rate the current microphone runs at. */
    @Volatile
    var captureRate: Int = 0
        private set

    /** Microphones opened so far, the first one included; the tests read it. */
    @Volatile
    var opened: Int = 0
        private set

    private val lock = Object()

    // Guarded by lock.
    private var mic: Microphone? = null
    private var generation = 0
    private var micRunning = false
    private var paused = false
    private var silenced = false
    private var stopped = false
    private var down: Downsampler48To16? = null
    private var thread: Thread? = null

    override fun start() {
        synchronized(lock) {
            check(thread == null && !stopped) { "already started" }
            val m = openAndStart() ?: throw Failure("the microphone could not be opened")
            use(m)
            thread = Thread(::loop, "akou-audio-capture").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }
        }
    }

    override fun pause() {
        synchronized(lock) {
            if (stopped || paused) return
            paused = true
            // While silenced the microphone stays open (its samples are dropped), so the end of the
            // silencing still reaches us.
            if (!silenced) {
                mic?.stop()
                micRunning = false
            }
        }
    }

    override fun resume() {
        synchronized(lock) {
            if (stopped) throw Failure("the recording has stopped")
            if (!paused) return
            if (silenced) throw Failure("a call or another app has the microphone")
            val m = mic
            when {
                m != null && micRunning -> {}
                m != null && m.start() -> micRunning = true
                else -> {
                    // The microphone is gone (a failed rebuild, a dead object while paused): a new one.
                    m?.let(::drop)
                    use(openAndStart() ?: throw Failure("the microphone could not be opened"))
                }
            }
            paused = false
            lock.notifyAll()
        }
    }

    override fun stop() {
        val t: Thread?
        synchronized(lock) {
            if (stopped) return
            stopped = true
            mic?.stop()
            micRunning = false
            lock.notifyAll()
            t = thread
        }
        // stop() makes the blocking read return, so the thread ends and the sink is not called again.
        t?.join(STOP_JOIN_MS)
        synchronized(lock) {
            mic?.release()
            mic = null
        }
    }

    private fun loop() {
        var buf = FloatArray(0)
        while (true) {
            val m: Microphone
            val gen: Int
            synchronized(lock) {
                while (!stopped && (mic == null || !micRunning)) lock.wait()
                if (stopped) return
                m = mic!!
                gen = generation
                // 100 ms per read. A pause stops the microphone, which ends the read in flight
                // early with what was recorded before the pause, so nothing is lost or added.
                if (buf.size != m.rate / 10) buf = FloatArray(m.rate / 10)
            }
            val n = m.read(buf, buf.size)
            var out: FloatArray? = null
            var recover = false
            synchronized(lock) {
                if (stopped) return
                // Paused or replaced while the read ran: what it read before a user pause is kept,
                // anything else from this microphone is not ours to judge.
                if (gen != generation) return@synchronized
                when {
                    n < 0 -> if (micRunning) recover = true
                    n == 0 -> if (micRunning && !m.isRecording) recover = true
                    // Silenced input is silence the system wrote, and a system pause keeps the
                    // microphone open: neither goes into the file.
                    silenced || (paused && micRunning) -> {}
                    else -> out = down?.process(buf, n) ?: buf.copyOf(n)
                }
            }
            out?.let {
                delivered.addAndGet(it.size.toLong())
                sink(it)
            }
            if (recover) recover(m, gen, dead = n == Microphone.ERROR_DEAD_OBJECT, code = n)
            else if (n == 0) Thread.sleep(2)
        }
    }

    /** On the capture thread: the same microphone again if it can, else a new one, else a pause. */
    private fun recover(m: Microphone, gen: Int, dead: Boolean, code: Int) {
        log(if (dead) "the audio server restarted (read $code): building a new microphone" else "the microphone stopped (read $code): restarting it")
        synchronized(lock) {
            if (stopped || gen != generation) return
            if (paused) {
                // A system pause keeps the microphone open; a dead one is let go, and Resume builds a new one.
                drop(m)
                return
            }
            if (!dead && m.start()) {
                log("the same microphone records again")
                return
            }
            drop(m)
        }
        for (attempt in 0 until rebuildAttempts) {
            if (attempt > 0) Thread.sleep(rebuildDelayMs)
            synchronized(lock) {
                // A pause or a stop during the wait: Resume builds the microphone when it is wanted.
                if (stopped || paused) return
                val next = openAndStart()
                if (next != null) {
                    use(next)
                    lock.notifyAll()
                    log("a new microphone records into the same file (attempt ${attempt + 1})")
                    return
                }
            }
        }
        log("no microphone after $rebuildAttempts attempts: pausing")
        post(Runnable { onInterruption?.invoke(true, false) })
    }

    /** Under the lock. A new microphone, started, or null; one that did not start is released. */
    private fun openAndStart(): Microphone? {
        val gen = generation + 1
        val m = open(eventsFor(gen)) ?: return null
        opened += 1
        if (!m.start()) {
            m.release()
            return null
        }
        generation = gen
        return m
    }

    /** Under the lock: [m], already started, is now the microphone. */
    private fun use(m: Microphone) {
        mic = m
        micRunning = true
        silenced = false
        captureRate = m.rate
        down = if (m.rate == AudioCapture.TARGET_RATE) null else Downsampler48To16()
    }

    /** Under the lock: [m] is done with. */
    private fun drop(m: Microphone) {
        m.stop()
        m.release()
        if (mic === m) {
            mic = null
            micRunning = false
            silenced = false
        }
    }

    private fun eventsFor(gen: Int) = object : Microphone.Events {
        override fun silenced(on: Boolean) {
            synchronized(lock) {
                // A microphone already replaced, or no change: nothing to tell.
                if (gen != generation || stopped || on == silenced) return
                silenced = on
            }
            log(if (on) "the system silenced the microphone" else "the system gave the microphone back")
            post(Runnable { onInterruption?.invoke(on, !on) })
        }
    }

    private companion object {
        const val STOP_JOIN_MS = 2000L
    }
}
