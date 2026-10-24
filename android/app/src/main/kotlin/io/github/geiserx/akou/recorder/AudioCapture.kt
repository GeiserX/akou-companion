// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou.recorder

import android.Manifest
import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresPermission
import java.util.concurrent.Executor

/**
 * The microphone: an [AudioRecord] in 32-bit float, 16 kHz mono, source `VOICE_RECOGNITION` (no
 * call-style processing), read with blocking reads on one thread of its own and handed to [sink].
 * If the device refuses 16 kHz it records at 48 kHz and [Downsampler48To16] brings it down.
 *
 * Pause, resume, the system silencing the input (`registerAudioRecordingCallback` and
 * `isClientSilenced`), a lost route and the audio-server restart (`ERROR_DEAD_OBJECT`) are
 * [CaptureEngine]'s rules; this class opens the real [AudioRecord] for it.
 */
class AudioCapture private constructor(
    context: Context,
    private val engine: CaptureEngine,
) : SampleSource by engine {
    class Failure(message: String) : Exception(message)

    private val audio = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    /** The rate the microphone runs at: 16000, or 48000 on the fallback path. */
    val captureRate: Int get() = engine.captureRate

    /** Microphones opened so far: more than one after a rebuild. */
    val opened: Int get() = engine.opened

    // Android moves an AudioRecord to the new input by itself (a headset in or out, Bluetooth). The
    // engine restarts one the system stopped; this only says, in the log, what changed.
    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = logInputs("added", added)

        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = logInputs("removed", removed)
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun start() {
        try {
            engine.start()
        } catch (e: CaptureEngine.Failure) {
            throw Failure(e.message ?: "the microphone could not be opened")
        }
        audio.registerAudioDeviceCallback(devices, main)
    }

    override fun stop() {
        audio.unregisterAudioDeviceCallback(devices)
        engine.stop()
    }

    private fun logInputs(what: String, list: Array<out AudioDeviceInfo>) {
        val inputs = list.filter { it.isSource }
        if (inputs.isNotEmpty()) Log.i(TAG, "input $what: ${inputs.joinToString { "${it.type}:${it.productName}" }}")
    }

    companion object {
        const val TARGET_RATE = 16000
        const val FALLBACK_RATE = 48000
        private const val TAG = "AudioCapture"

        /** The microphone for [sink]; its events and [SampleSource.onInterruption] come on the main thread. */
        operator fun invoke(context: Context, sink: (FloatArray) -> Unit): AudioCapture {
            val mainExecutor = context.mainExecutor
            @Suppress("MissingPermission") // checked by the recorder before start()
            val engine = CaptureEngine(
                open = { events -> openMicrophone(mainExecutor, events) },
                sink = sink,
                post = { mainExecutor.execute(it) },
                log = { Log.i(TAG, it) },
            )
            return AudioCapture(context.applicationContext, engine)
        }

        @RequiresPermission(Manifest.permission.RECORD_AUDIO)
        private fun openMicrophone(executor: Executor, events: Microphone.Events): Microphone? {
            val (r, rate) = build(TARGET_RATE) ?: build(FALLBACK_RATE) ?: return null
            return AudioRecordMicrophone(r, rate, executor, events)
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
    }
}

/**
 * One [AudioRecord] for the [CaptureEngine]. Its recording callback reports `isClientSilenced`: a
 * phone call or the Assistant silences our input instead of stopping it (docs/ANDROID.md, section 5).
 */
internal class AudioRecordMicrophone(
    private val record: AudioRecord,
    override val rate: Int,
    executor: Executor,
    private val events: Microphone.Events,
) : Microphone {
    private val callback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
            // Only this AudioRecord's own configuration; it is absent while stopped.
            val mine = configs.firstOrNull { it.clientAudioSessionId == record.audioSessionId } ?: return
            events.silenced(mine.isClientSilenced)
        }
    }

    init {
        record.registerAudioRecordingCallback(executor, callback)
    }

    override fun start(): Boolean = try {
        record.startRecording()
        record.recordingState == AudioRecord.RECORDSTATE_RECORDING
    } catch (e: IllegalStateException) {
        false
    }

    override fun stop() {
        runCatching { record.stop() }
    }

    override val isRecording: Boolean get() = record.recordingState == AudioRecord.RECORDSTATE_RECORDING

    override fun read(buffer: FloatArray, count: Int): Int = record.read(buffer, 0, count, AudioRecord.READ_BLOCKING)

    override fun release() {
        runCatching { record.unregisterAudioRecordingCallback(callback) }
        runCatching { record.release() }
    }
}
