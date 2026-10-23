// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou.recorder

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.annotation.RequiresPermission
import java.util.concurrent.atomic.AtomicLong

/**
 * The microphone: an [AudioRecord] in 32-bit float, 16 kHz mono, source `VOICE_RECOGNITION` (no
 * call-style processing), read with blocking reads on one thread of its own and handed to [sink].
 * If the device refuses 16 kHz it records at 48 kHz and [Downsampler48To16] brings it down.
 *
 * Interruptions, route changes and the audio-server restart come in A2 (docs/ANDROID.md, section 4).
 */
class AudioCapture(private val sink: (FloatArray) -> Unit) : SampleSource {
    class Failure(message: String) : Exception(message)

    private val delivered = AtomicLong(0)
    override val deliveredSamples: Long get() = delivered.get()

    /** The rate the microphone runs at: 16000, or 48000 on the fallback path. */
    @Volatile
    var captureRate: Int = 0
        private set

    private var record: AudioRecord? = null
    private var thread: Thread? = null

    @Volatile
    private var running = false

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun start() {
        check(record == null) { "already started" }
        val (r, rate) = build(TARGET_RATE) ?: build(FALLBACK_RATE) ?: throw Failure("the microphone could not be opened")
        try {
            r.startRecording()
            if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw Failure("the microphone did not start")
        } catch (e: Exception) {
            r.release()
            throw if (e is Failure) e else Failure("the microphone did not start: ${e.message}")
        }
        record = r
        captureRate = rate
        running = true
        thread = Thread({ loop(r, rate) }, "akou-audio-capture").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    override fun stop() {
        running = false
        val r = record ?: return
        // stop() makes the blocking read return, so the thread ends and the sink is not called again.
        runCatching { r.stop() }
        thread?.join(STOP_JOIN_MS)
        r.release()
        record = null
        thread = null
    }

    private fun loop(r: AudioRecord, rate: Int) {
        val down = if (rate == TARGET_RATE) null else Downsampler48To16()
        val buf = FloatArray(rate / 10) // 100 ms per read
        while (running) {
            val n = r.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
            if (n < 0) {
                // ERROR_DEAD_OBJECT (the audio server restarted) is handled in A2; stop here, the file stays.
                Log.w(TAG, "AudioRecord.read returned $n")
                break
            }
            if (n == 0 || !running) continue
            val out = down?.process(buf, n) ?: buf.copyOf(n)
            delivered.addAndGet(out.size.toLong())
            sink(out)
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun build(rate: Int): Pair<AudioRecord, Int>? {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(rate)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        if (min <= 0) return null
        return try {
            val r = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(format)
                // Half a second of float samples, at least twice the minimum.
                .setBufferSizeInBytes(maxOf(min * 2, rate * 4 / 2))
                .build()
            if (r.state != AudioRecord.STATE_INITIALIZED || r.sampleRate != rate) {
                r.release()
                null
            } else {
                r to rate
            }
        } catch (e: UnsupportedOperationException) {
            Log.w(TAG, "AudioRecord refused $rate Hz: ${e.message}")
            null
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "AudioRecord refused $rate Hz: ${e.message}")
            null
        }
    }

    companion object {
        const val TARGET_RATE = 16000
        const val FALLBACK_RATE = 48000
        private const val STOP_JOIN_MS = 2000L
        private const val TAG = "AudioCapture"
    }
}
