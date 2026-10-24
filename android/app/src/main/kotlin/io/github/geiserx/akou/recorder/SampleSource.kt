// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou.recorder

/**
 * What the recorder takes samples from: 16 kHz mono Float, handed to the sink it was made with on a
 * thread of the source's own. The microphone ([AudioCapture]) in the app; a fake in the tests.
 * Every call, and [onInterruption], happens on the main thread.
 */
interface SampleSource {
    /**
     * The system took the microphone (`began` true: a phone call, the Assistant, a microphone that
     * could not be rebuilt) or gave it back (`began` false, with whether the source may resume).
     * The port of AudioCapture.swift's `onInterruption`.
     */
    var onInterruption: ((began: Boolean, shouldResume: Boolean) -> Unit)?

    /** Starts delivering. Throws when the microphone cannot be opened. */
    fun start()

    /** Stops delivering until [resume]; nothing is delivered for the paused time. */
    fun pause()

    /** Delivers again, into the same stream. Throws when the microphone does not come back. */
    fun resume()

    /** Stops delivering. When it returns, the sink is not called again. */
    fun stop()

    /** 16 kHz samples handed to the sink so far. */
    val deliveredSamples: Long
}

/**
 * 48 kHz to 16 kHz for a microphone that refuses 16 kHz: a windowed-sinc low-pass at 7 kHz, then
 * every third sample. Its state carries across buffers, so any buffer sizes give the same output.
 *
 * Not thread-safe: the capture thread owns it.
 */
class Downsampler48To16 {
    private val history = FloatArray(TAPS.size - 1)
    private var phase = 0

    fun process(input: FloatArray, count: Int = input.size): FloatArray {
        val out = FloatArray((phase + count) / 3 + 1)
        var n = 0
        val h = history.size
        for (i in 0 until count) {
            // The newest TAPS.size samples are history[..] then input[..i].
            if ((phase + i) % 3 == 0) {
                var acc = 0.0
                for (k in TAPS.indices) {
                    // k = 0 is the newest sample.
                    val j = i - k
                    val x = if (j >= 0) input[j] else history[h + j]
                    acc += TAPS[k] * x
                }
                out[n++] = acc.toFloat()
            }
        }
        // Keep the last h samples for the next buffer.
        if (count >= h) {
            input.copyInto(history, 0, count - h, count)
        } else {
            history.copyInto(history, 0, count, h)
            input.copyInto(history, h - count, 0, count)
        }
        phase = (phase + count) % 3
        return out.copyOf(n)
    }

    companion object {
        /** A Hann-windowed sinc, 47 taps, cutoff 7 kHz at 48 kHz, normalised to a gain of 1 at DC. */
        internal val TAPS: DoubleArray = run {
            val n = 47
            val fc = 7000.0 / 48000.0
            val m = (n - 1) / 2.0
            val raw = DoubleArray(n) { i ->
                val x = i - m
                val sinc = if (x == 0.0) 2 * fc else kotlin.math.sin(2 * Math.PI * fc * x) / (Math.PI * x)
                sinc * (0.5 - 0.5 * kotlin.math.cos(2 * Math.PI * i / (n - 1)))
            }
            val sum = raw.sum()
            DoubleArray(n) { raw[it] / sum }
        }
    }
}
