// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou.core.ogg

/**
 * A stand-in for libopus on the JVM: it hands out the ffmpeg fixture's audio packets in order, one
 * per frame, and reports a lookahead of 104 samples, so pre-skip 312 as in the fixture. It proves the
 * Ogg framing and the granules, not the encoder; the encoder checks run against libopus in `opus`.
 *
 * The fixture holds 51 audio packets: 50 for one second of input and one for the frame of silence
 * [OggOpusWriter.finish] adds. Asking for more fails, so a writer that encodes too much is caught.
 */
class FixtureOpusEncoder(
    private val packets: List<ByteArray> = fixturePackets(),
    override val lookahead: Int = 104,
) : OpusEncoder {
    private var next = 0

    override fun encode(frame: FloatArray): ByteArray {
        require(frame.size == OpusEncoder.FRAME_SAMPLES) { "a frame is ${OpusEncoder.FRAME_SAMPLES} samples, got ${frame.size}" }
        check(next < packets.size) { "the fixture has only ${packets.size} packets" }
        return packets[next++].copyOf()
    }

    companion object {
        /** Every packet after the two header pages, in stream order. */
        fun fixturePackets(): List<ByteArray> = OggPage.readAll(Fixtures.ffmpegFile()).drop(2).flatMap { it.packets }
    }
}
