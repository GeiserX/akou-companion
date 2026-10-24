// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou.recorder

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import io.github.geiserx.akou.R
import io.github.geiserx.akou.opus.LibopusEncoder
import io.github.geiserx.akou.settings.Settings
import io.github.geiserx.akou.upload.Uploads
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * The microphone foreground service. It holds the process's one [Recorder], whose state the Record
 * screen reads as StateFlows, and keeps the process in the foreground while a recording runs, so the
 * file goes on when the screen locks and when the app is swiped from recents.
 *
 * Its ongoing notification is the recording's control: a chronometer of the recorded time (stopped
 * while paused), Pause or Resume, Stop, the server it records to, and a tap that opens the Record
 * screen ([RecordingNotification]).
 *
 * When the recording stops it queues the upload with WorkManager and calls `stopSelf()`: the
 * microphone service never stays up for network work.
 */
class RecordingService : Service() {
    private var watch: Job? = null
    private var serverName: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                // Within seconds of startForegroundService, whatever happens next.
                // A second Start while recording keeps the notification as it is.
                startForeground(NOTIFICATION_ID, notificationFor(recorder(this).state.value, serverName), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                commands(this).start()
                watchState()
            }
            ACTION_STOP -> commands(this).stop()
            ACTION_PAUSE -> commands(this).pause()
            ACTION_RESUME -> commands(this).resume()
            // Restarted by the system with no intent: nothing is recording in this new process.
            else -> if (recorder(this).isIdle) stopSelf()
        }
        return START_NOT_STICKY
    }

    /**
     * Swiped from recents: the foreground service keeps the process, and the recording goes on.
     * Nothing here stops it (and the manifest leaves `stopWithTask` false).
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        tasksRemoved += 1
        Log.i(TAG, "task removed; the recording goes on (${recorder(this).state.value})")
    }

    override fun onDestroy() {
        watch?.cancel()
        watch = null
        if (service === this) service = null
        super.onDestroy()
    }

    /** Redraws the notification on every state change, as the iOS Live Activity is (ActivityController.swift:29-33). */
    private fun watchState() {
        if (watch != null) return
        val r = recorder(this)
        val nm = getSystemService(NotificationManager::class.java)
        watch = scope.launch {
            serverName = runCatching { Settings(applicationContext).book().recordToServer?.name }.getOrNull()
            r.state.collect { state ->
                // Idle is the start-up notification or the end, which takes the notification away.
                if (state != Recorder.State.Idle) nm.notify(NOTIFICATION_ID, notificationFor(state, serverName))
            }
        }
    }

    private fun end() {
        watch?.cancel()
        watch = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notificationFor(state: Recorder.State, serverName: String?): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.channel_recording), NotificationManager.IMPORTANCE_LOW),
        )
        return RecordingNotification.build(this, CHANNEL_ID, state, serverName)
    }

    companion object {
        const val ACTION_START = "io.github.geiserx.akou.action.START"
        const val ACTION_STOP = "io.github.geiserx.akou.action.STOP"
        const val ACTION_PAUSE = "io.github.geiserx.akou.action.PAUSE"
        const val ACTION_RESUME = "io.github.geiserx.akou.action.RESUME"
        private const val CHANNEL_ID = "recording"
        const val NOTIFICATION_ID = 1
        private const val TAG = "RecordingService"

        /** Every recorder call runs here, on the main thread, as iOS runs the controller on the main actor. */
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        private val _startError = MutableStateFlow<String?>(null)

        /** Why the last Start failed, for the Record screen. */
        val startError: StateFlow<String?> = _startError.asStateFlow()

        @Volatile private var instance: Recorder? = null
        @Volatile private var commandsInstance: RecordCommands? = null

        /** How often [onTaskRemoved] ran, so the instrumented test knows the swipe reached the service. */
        @Volatile
        internal var tasksRemoved = 0
            private set

        /** The live service, for ending it from a command; null when it is not running. */
        @Volatile private var service: RecordingService? = null

        /** `filesDir/Recordings`: credential-encrypted storage, readable after the first unlock. */
        fun recordingsDirectory(context: Context): File = File(context.filesDir, "Recordings").apply { mkdirs() }

        /** The process's one recorder. */
        fun recorder(context: Context): Recorder = instance ?: synchronized(this) {
            instance ?: run {
                val app = context.applicationContext
                Recorder(
                    scope = scope,
                    directory = recordingsDirectory(app),
                    hasPermission = { app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED },
                    makeOutput = { file -> RecordingFile(file, LibopusEncoder()) },
                    makeSource = { sink -> AudioCapture(app, sink) },
                ).also { instance = it }
            }
        }

        /** Start, Stop, Pause and Resume, one at a time and in order. */
        private fun commands(svc: RecordingService): RecordCommands {
            service = svc
            return commandsInstance ?: synchronized(this) {
                commandsInstance ?: run {
                    val app = svc.applicationContext
                    val r = recorder(app)
                    r.onFinished = { f ->
                        // For the measure lane: what the file says against what the microphone delivered.
                        Log.i(TAG, "finished ${f.id}: ${f.seconds} s in the file, ${f.deliveredSamples} samples delivered")
                        Uploads.get(app).enqueue(f)
                        service?.end()
                    }
                    RecordCommands(
                        recorder = r,
                        scope = scope,
                        resolve = {
                            val settings = Settings(app)
                            settings.recordTarget() to settings.book().recordTo
                        },
                        endService = { service?.end() },
                        startFailed = { e ->
                            Log.w(TAG, "recording did not start: $e")
                            _startError.value = (e as? Recorder.Failure.MicrophoneDenied)?.let { "akou needs the microphone to record." }
                                ?: "Could not start: ${e.message ?: e.javaClass.simpleName}"
                        },
                        started = { _startError.value = null },
                    ).also { commandsInstance = it }
                }
            }
        }

        fun start(context: Context) {
            _startError.value = null
            context.startForegroundService(Intent(context, RecordingService::class.java).setAction(ACTION_START))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, RecordingService::class.java).setAction(ACTION_STOP))
        }

        fun pause(context: Context) {
            context.startService(Intent(context, RecordingService::class.java).setAction(ACTION_PAUSE))
        }

        fun resume(context: Context) {
            context.startService(Intent(context, RecordingService::class.java).setAction(ACTION_RESUME))
        }
    }
}
