// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou.opus

/** The JNI functions in akou_opus_jni.c. Only [LibopusEncoder] calls them. */
internal object OpusNative {
    init {
        System.loadLibrary("akouopus")
    }

    /** A 16 kHz mono VOIP encoder, or 0 with the libopus error in `err[0]`. */
    external fun create(err: IntArray): Long

    /** The six settings of akou_opus_shim.c, in its order; OPUS_OK (0) or the first error. */
    external fun configure(handle: Long, bitrate: Int): Int

    external fun lookahead(handle: Long): Int

    external fun bitrate(handle: Long): Int

    /** Encodes exactly 320 samples into exactly 1500 bytes of [out]; the packet length or a negative error. */
    external fun encodeFloat(handle: Long, pcm: FloatArray, out: ByteArray): Int

    external fun destroy(handle: Long)
}
