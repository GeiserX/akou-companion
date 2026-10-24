// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou.recorder

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * The service's commands to the recorder, run one at a time in the order they came. A Stop that
 * arrives while a Start is still reading the settings or waiting for the microphone permission
 * waits for that Start, then stops the recording it made: it never ends the service under a start
 * that is still going, which would leave a microphone open with no foreground service.
 *
 * Kept apart from the service so the JVM tests drive it.
 */
class RecordCommands(
    private val recorder: Recorder,
    private val scope: CoroutineScope,
    /** Where a Start records to: the target, or the server id alone when its URL or key is missing. */
    private val resolve: suspend () -> Pair<RecordTarget?, UUID?>,
    /** The service should end now: nothing is recording and nothing is stopping. */
    private val endService: () -> Unit,
    private val startFailed: (Exception) -> Unit = {},
    private val started: () -> Unit = {},
) {
    private val mutex = Mutex()

    fun start(): Job = scope.launch {
        mutex.withLock {
            try {
                val (target, serverId) = resolve()
                recorder.start(target, serverId)
                started()
            } catch (_: Recorder.Failure.AlreadyRecording) {
                // A second Start while recording changes nothing.
            } catch (e: Exception) {
                startFailed(e)
                if (recorder.isIdle) endService()
            }
        }
    }

    fun stop(): Job = scope.launch {
        mutex.withLock {
            val finished = try {
                recorder.stop()
            } catch (e: Exception) {
                // The recorder is idle again and onFinished will not come: end the service here.
                endService()
                return@withLock
            }
            // A Stop with nothing to stop. A stop that finished ended the service through onFinished.
            if (finished == null && recorder.isIdle) endService()
        }
    }

    /** A Pause or Resume from a stale notification, with nothing recording, ends the service it started. */
    fun pause(): Job = scope.launch {
        mutex.withLock {
            recorder.pause()
            if (recorder.isIdle) endService()
        }
    }

    fun resume(): Job = scope.launch {
        mutex.withLock {
            recorder.resume()
            if (recorder.isIdle) endService()
        }
    }
}
