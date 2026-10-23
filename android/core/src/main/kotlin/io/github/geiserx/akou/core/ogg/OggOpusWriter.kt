// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou.core.ogg

import kotlin.random.Random

/**
 * Turns 20 ms frames into Ogg Opus pages: the two header pages first, then one page per
 * [packetsPerPage] packets (10, so 200 ms per page). Each returned page is appended to the
 * recording file and, while connected, sent as one binary WebSocket message.
 *
 * Granule positions follow RFC 7845: the 48 kHz sample count at the end of the page's last packet,
 * pre-skip excluded on audio pages, as the iOS writer and ffmpeg's muxer do; the last page's is
 * pre-skip plus the input's samples, which trims the flushing frame. The server reads the clock
 * from them, so the phone sends no timestamps.
 *
 * Not thread-safe: one recording owns one writer and calls it from one thread.
 */
class OggOpusWriter(
    private val encoder: OpusEncoder,
    val serial: UInt = Random.nextInt().toUInt(),
    val packetsPerPage: Int = DEFAULT_PACKETS_PER_PAGE,
    private val vendor: String = "akou-companion",
) {
    private var sequence: UInt = 0u
    private val pending = ArrayList<ByteArray>(packetsPerPage + 1)
    private var packetsEncoded = 0L
    private var headers: List<ByteArray>? = null
    private var started = false
    private var finished = false

    sealed class Failure(message: String) : IllegalStateException(message) {
        class HeadersNotWritten : Failure("header pages not written yet")
        class AlreadyFinished : Failure("writer already finished")
    }

    init {
        require(packetsPerPage in 1..255) { "packetsPerPage must be 1 to 255" }
    }

    /**
     * The OpusHead page (beginning of stream) and the OpusTags page, in that order. Built once:
     * every later call returns the same bytes, so a reconnect can resend them without breaking
     * the page sequence. The returned arrays are copies, so a caller cannot change the cache.
     */
    fun headerPages(): List<ByteArray> {
        headers?.let { cached -> return cached.map { it.copyOf() } }
        if (finished) throw Failure.AlreadyFinished()
        val head = OpusHeaders.head(preSkip = encoder.preSkip48k, inputSampleRate = OpusEncoder.SAMPLE_RATE)
        val tags = OpusHeaders.tags(vendor)
        val pages = listOf(
            page(OggPage.Flags.BEGIN_OF_STREAM, 0, listOf(head)),
            page(OggPage.Flags.NONE, 0, listOf(tags)),
        )
        started = true
        headers = pages
        return pages.map { it.copyOf() }
    }

    /** Encodes one 20 ms frame. Returns a finished page every [packetsPerPage] frames, null otherwise. */
    fun append(frame: FloatArray): ByteArray? {
        if (!started) throw Failure.HeadersNotWritten()
        if (finished) throw Failure.AlreadyFinished()
        pending.add(encoder.encode(frame))
        packetsEncoded += 1
        if (pending.size != packetsPerPage) return null
        val out = page(OggPage.Flags.NONE, packetsEncoded * OpusEncoder.FRAME_SAMPLES_48K, pending)
        pending.clear()
        return out
    }

    /**
     * Writes the last page, marked end of stream, with whatever packets are still pending.
     *
     * The encoder holds back its lookahead, so one frame of silence goes in after the audio to push
     * the last real samples out; the page's granule then ends the stream exactly where the input
     * ended (pre-skip plus the input's samples), which trims that padding again (RFC 7845 4.4).
     */
    fun finish(): ByteArray {
        if (!started) throw Failure.HeadersNotWritten()
        if (finished) throw Failure.AlreadyFinished()
        val inputPackets = packetsEncoded
        var granule = 0L
        if (inputPackets > 0) {
            pending.add(encoder.encode(FloatArray(OpusEncoder.FRAME_SAMPLES)))
            granule = encoder.preSkip48k + inputPackets * OpusEncoder.FRAME_SAMPLES_48K
        }
        val last = page(OggPage.Flags.END_OF_STREAM, granule, pending)
        pending.clear()
        finished = true
        return last
    }

    /** Seconds of audio encoded so far. */
    val seconds: Double get() = (packetsEncoded * OpusEncoder.FRAME_SAMPLES_48K).toDouble() / 48000

    private fun page(flags: OggPage.Flags, granule: Long, packets: List<ByteArray>): ByteArray {
        val bytes = OggPage(flags, granule, serial, sequence, packets.toList()).encoded()
        sequence += 1u
        return bytes
    }

    companion object {
        const val DEFAULT_PACKETS_PER_PAGE = 10
    }
}
