// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou.core.ogg

import java.io.ByteArrayOutputStream

/** The two header packets every Ogg Opus stream starts with (RFC 7845 section 5). */
object OpusHeaders {
    /**
     * The identification header: version 1, mono or stereo, channel mapping family 0.
     * [preSkip] is in 48 kHz samples; [inputSampleRate] is informational (16000 here).
     */
    fun head(channels: Int = 1, preSkip: Int, inputSampleRate: Int, outputGain: Int = 0): ByteArray {
        require(channels in 1..2) { "channel mapping family 0 is mono or stereo" }
        require(preSkip in 0..0xFFFF) { "pre-skip $preSkip does not fit 16 bits" }
        require(outputGain in Short.MIN_VALUE..Short.MAX_VALUE) { "output gain $outputGain does not fit 16 bits" }
        val d = ByteArrayOutputStream(19)
        d.write("OpusHead".toByteArray(Charsets.US_ASCII))
        d.write(1) // version
        d.write(channels)
        d.writeLE16(preSkip)
        d.writeLE32(inputSampleRate)
        d.writeLE16(outputGain)
        d.write(0) // channel mapping family 0: mono or stereo, no table
        return d.toByteArray()
    }

    /** The comment header: the vendor string and `key=value` comments. */
    fun tags(vendor: String, comments: List<String> = emptyList()): ByteArray {
        val d = ByteArrayOutputStream()
        d.write("OpusTags".toByteArray(Charsets.US_ASCII))
        val v = vendor.toByteArray(Charsets.UTF_8)
        d.writeLE32(v.size)
        d.write(v)
        d.writeLE32(comments.size)
        for (c in comments) {
            val b = c.toByteArray(Charsets.UTF_8)
            d.writeLE32(b.size)
            d.write(b)
        }
        return d.toByteArray()
    }

    private fun ByteArrayOutputStream.writeLE16(v: Int) {
        write(v and 0xFF)
        write((v ushr 8) and 0xFF)
    }

    private fun ByteArrayOutputStream.writeLE32(v: Int) {
        write(v and 0xFF)
        write((v ushr 8) and 0xFF)
        write((v ushr 16) and 0xFF)
        write((v ushr 24) and 0xFF)
    }
}
