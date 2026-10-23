// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou.core.ogg

/**
 * An Opus encoder set up the way akou-companion records: 16 kHz mono in, 20 ms frames. The real one
 * is libopus in the `opus` module; the JVM tests use a fake that replays known packets.
 */
interface OpusEncoder {
    /** The encoder's lookahead in 16 kHz samples. */
    val lookahead: Int

    /** The Ogg Opus pre-skip: the lookahead in 48 kHz samples (RFC 7845 section 4.2). */
    val preSkip48k: Int get() = lookahead * 3

    /** Encodes exactly one 20 ms frame of [FRAME_SAMPLES] mono samples in [-1, 1] into one Opus packet. */
    fun encode(frame: FloatArray): ByteArray

    companion object {
        /** The rate every akou engine takes, so the server never resamples the phone's audio. */
        const val SAMPLE_RATE = 16000
        /** 20 ms at 16 kHz. */
        const val FRAME_SAMPLES = 320
        /** The same 20 ms in the 48 kHz units Ogg Opus granule positions use. */
        const val FRAME_SAMPLES_48K = 960L
    }
}
