// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou.core.ogg

import java.io.File

/** The Swift test fixtures, read in place from the repository (the build passes their directory). */
object Fixtures {
    private val dir: File
        get() = File(checkNotNull(System.getProperty("akou.fixtures")) { "akou.fixtures is not set; run through Gradle" })

    /**
     * An Ogg Opus file made by ffmpeg (its own Ogg muxer and CRC), 1 s of a 440 Hz sine at 16 kHz,
     * libopus 24 kbit/s, 20 ms frames, 200 ms pages, bit-exact flags so the serial is 0.
     */
    fun ffmpegFile(): ByteArray = File(dir, "ffmpeg-1s-16k.opus").readBytes()
}
