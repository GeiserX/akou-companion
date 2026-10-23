// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou.opus

import io.github.geiserx.akou.core.ogg.OpusEncoder

/**
 * libopus set up the way akou-companion records, as the iOS OpusEncoder does: 16 kHz mono in, 20 ms
 * frames, VBR at the given bitrate (24 kbit/s by default), wideband at most, voice signal, no DTX and
 * no in-band FEC.
 *
 * The encoder lives in native memory: [close] frees it, and an encoder is unusable after that.
 * [encode] and [close] are synchronized, so a service can close it while the capture thread encodes.
 */
class LibopusEncoder(bitrate: Int = 24000) : OpusEncoder, AutoCloseable {
    /** A libopus error code (negative, as in opus_defines.h). */
    class Failure(val code: Int) : Exception("libopus error $code")

    private var handle: Long
    private val out = ByteArray(MAX_PACKET_BYTES)

    override val lookahead: Int

    /** The bitrate libopus reports after configuration, in bits per second. */
    val bitrate: Int

    init {
        val err = IntArray(1)
        val h = OpusNative.create(err)
        if (h == 0L || err[0] != OPUS_OK) throw Failure(err[0])
        try {
            val rc = OpusNative.configure(h, bitrate)
            if (rc != OPUS_OK) throw Failure(rc)
            val la = OpusNative.lookahead(h)
            val br = OpusNative.bitrate(h)
            if (la < 0 || br <= 0) throw Failure(if (la < 0) la else br)
            lookahead = la
            this.bitrate = br
        } catch (e: Failure) {
            OpusNative.destroy(h)
            throw e
        }
        handle = h
    }

    /** Encodes exactly one 20 ms frame of 320 mono samples in [-1, 1] into one Opus packet. */
    @Synchronized
    override fun encode(frame: FloatArray): ByteArray {
        if (frame.size != OpusEncoder.FRAME_SAMPLES) throw Failure(OPUS_BAD_ARG)
        check(handle != 0L) { "encoder closed" }
        val n = OpusNative.encodeFloat(handle, frame, out)
        if (n <= 0) throw Failure(n)
        return out.copyOf(n)
    }

    @Synchronized
    override fun close() {
        if (handle == 0L) return
        OpusNative.destroy(handle)
        handle = 0L
    }

    companion object {
        /** The output buffer for one packet, as on iOS (OpusEncoder.swift). */
        const val MAX_PACKET_BYTES = 1500
        private const val OPUS_OK = 0
        private const val OPUS_BAD_ARG = -1
    }
}
