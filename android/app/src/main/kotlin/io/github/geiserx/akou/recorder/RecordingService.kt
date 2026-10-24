// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou.recorder

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import io.github.geiserx.akou.MainActivity
import io.github.geiserx.akou.R
import io.github.geiserx.akou.opus.LibopusEncoder
import io.github.geiserx.akou.settings.Settings
import io.github.geiserx.akou.upload.Uploads
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * The microphone foreground service. It holds the process's one [Recorder], whose state the Record
 * screen reads as StateFlows, and keeps the process in the foreground while a recording runs, so the
 * file goes on when the screen goes off.
 *
 * When the recording stops it queues the upload with WorkManager and calls `stopSelf()`: the
 * microphone service never stays up for network work.
 *
 * The notification is the minimum Android requires; its chronometer and actions come in A2.
 */
class RecordingService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                // Within seconds of startForegroundService, whatever happens next.
                startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                val r = recorder(this)
                r.onFinished = ::finished
                scope.launch {
                    val settings = Settings(applicationContext)
                    try {
                        r.start(settings.recordTarget(), settings.book().recordTo)
                        _startError.value = null
                    } catch (e: Recorder.Failure.AlreadyRecording) {
                        // A second Start while recording changes nothing.
                    } catch (e: Exception) {
                        Log.w(TAG, "recording did not start: $e")
                        _startError.value = (e as? Recorder.Failure.MicrophoneDenied)?.let { "akou needs the microphone to record." }
                            ?: "Could not start: ${e.message ?: e.javaClass.simpleName}"
                        stopSelf()
                    }
                }
            }
            ACTION_STOP -> scope.launch {
                // A second Stop can land while the first is still finishing the file and live text:
                // only an idle recorder lets this one end the service; otherwise finished() does.
                val r = recorder(this@RecordingService)
                if (r.stop() == null && r.isIdle) stopSelf()
            }
            // Restarted by the system with no intent: nothing is recording in this new process.
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun finished(f: FinishedRecording) {
        // For the measure lane: what the file says against what the microphone delivered.
        Log.i(TAG, "finished ${f.id}: ${f.seconds} s in the file, ${f.deliveredSamples} samples delivered")
        Uploads.get(applicationContext).enqueue(f)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.channel_recording), NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.notification_recording))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        const val ACTION_START = "io.github.geiserx.akou.action.START"
        const val ACTION_STOP = "io.github.geiserx.akou.action.STOP"
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "RecordingService"

        /** Every recorder call runs here, on the main thread, as iOS runs the controller on the main actor. */
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        private val _startError = MutableStateFlow<String?>(null)

        /** Why the last Start failed, for the Record screen. */
        val startError: StateFlow<String?> = _startError.asStateFlow()

        @Volatile private var instance: Recorder? = null

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
                    makeSource = { sink -> AudioCapture(sink) },
                ).also { instance = it }
            }
        }

        fun start(context: Context) {
            _startError.value = null
            context.startForegroundService(Intent(context, RecordingService::class.java).setAction(ACTION_START))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, RecordingService::class.java).setAction(ACTION_STOP))
        }
    }
}
