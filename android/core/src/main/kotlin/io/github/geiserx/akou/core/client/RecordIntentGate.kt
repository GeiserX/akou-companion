// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou.core.client

/**
 * What the record entry points (Quick Settings tile, widget, shortcuts) need from the recorder.
 * The app's recorder implements it. Calls come from one thread, the main one, as on iOS.
 */
interface RecordIntentRecorder {
    /** True from start until the file is finished, paused included: a paused recording still holds its file. */
    val isRecording: Boolean
    val isPaused: Boolean

    /** Starts recording. `workspace` null is the key's default workspace. */
    suspend fun start(workspace: String?, title: String?)

    /**
     * Continues a paused recording in the same file. When the microphone does not come back, the
     * recorder stays paused.
     */
    fun resume()

    /** Finishes the file; the app then uploads it. */
    suspend fun stopRecording()
}

sealed class RecordIntentGateException(message: String) : Exception(message) {
    /** `resume()` left the recording paused: the microphone did not come back. */
    class ResumeFailed : RecordIntentGateException("the recording is still paused: the microphone did not come back")
}

/**
 * The decisions behind the record entry points, kept apart from them so the JVM tests cover them.
 * `onStatus` gets whether a recording is running once the call is over, failed or not, so a
 * control never shows a state the recorder is not in.
 */
object RecordIntentGate {
    /**
     * Resumes a paused recording, starts one in `workspace` when none is running, and does nothing
     * otherwise. Throws [RecordIntentGateException.ResumeFailed] when the recording is still paused
     * after `resume()`.
     */
    suspend fun start(recorder: RecordIntentRecorder, title: String?, workspace: String? = null, onStatus: (Boolean) -> Unit) {
        try {
            if (recorder.isPaused) {
                recorder.resume()
                if (recorder.isPaused) throw RecordIntentGateException.ResumeFailed()
            } else if (!recorder.isRecording) {
                recorder.start(workspace = workspace, title = title)
            }
        } finally {
            onStatus(recorder.isRecording)
        }
    }

    /** Stops the running or paused recording, and does nothing when there is none. */
    suspend fun stop(recorder: RecordIntentRecorder, onStatus: (Boolean) -> Unit) {
        try {
            if (recorder.isRecording) recorder.stopRecording()
        } finally {
            onStatus(recorder.isRecording)
        }
    }
}
