// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou.core.ogg

/**
 * Cuts 16 kHz mono audio, arriving in buffers of whatever length the microphone hands out, into
 * the exact 20 ms frames ([OpusEncoder.FRAME_SAMPLES], 320 samples) that [OggOpusWriter.append]
 * takes. The samples left over from one buffer start the next frame.
 *
 * Not thread-safe: the capture thread owns it.
 */
class FrameChunker(val frameSamples: Int = OpusEncoder.FRAME_SAMPLES) {
    private val carry: FloatArray
    private var carryCount = 0

    init {
        require(frameSamples > 0) { "frameSamples must be positive" }
        carry = FloatArray(frameSamples)
    }

    /** The whole frames the carried samples and [samples] make, oldest first. */
    fun push(samples: FloatArray): List<FloatArray> {
        val count = samples.size
        val frames = ArrayList<FloatArray>()
        var i = 0
        if (carryCount > 0) {
            val take = minOf(frameSamples - carryCount, count)
            samples.copyInto(carry, carryCount, 0, take)
            carryCount += take
            i += take
            if (carryCount < frameSamples) return frames
            frames.add(carry.copyOf())
            carryCount = 0
        }
        while (count - i >= frameSamples) {
            frames.add(samples.copyOfRange(i, i + frameSamples))
            i += frameSamples
        }
        samples.copyInto(carry, 0, i, count)
        carryCount = count - i
        return frames
    }

    /** The last partial frame padded with zeros to a whole frame, or null when nothing is carried. */
    fun flush(): FloatArray? {
        if (carryCount == 0) return null
        val last = FloatArray(frameSamples)
        carry.copyInto(last, 0, 0, carryCount)
        carryCount = 0
        return last
    }

    /** Samples waiting for the next frame. */
    val carried: Int get() = carryCount
}
// Positive control: a change under android/ only.
