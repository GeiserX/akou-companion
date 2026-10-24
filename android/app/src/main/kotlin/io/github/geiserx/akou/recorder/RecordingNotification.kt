// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou.recorder

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import io.github.geiserx.akou.MainActivity
import io.github.geiserx.akou.core.client.LiveTranscript

/**
 * What the recording's notification shows for a recorder state, apart from Android so the JVM tests
 * check it. The counterpart of the iOS Live Activity's content (ActivityController.swift).
 */
data class NotificationContent(
    val title: String,
    val text: String,
    /**
     * The wall-clock time the system's chronometer counts from: the recording's `startedAt`, already
     * shifted by the paused time, so it shows the recorded length. Null while paused: the
     * chronometer stops and [title] holds the time recorded so far.
     */
    val chronometerBase: Long?,
    val actions: List<Action>,
) {
    enum class Action { PAUSE, RESUME, STOP }

    companion object {
        fun of(state: Recorder.State, serverName: String?): NotificationContent {
            val text = serverName?.let { "To $it" } ?: "On this phone only"
            return when (state) {
                is Recorder.State.Recording -> NotificationContent("Recording", text, state.startedAt, listOf(Action.PAUSE, Action.STOP))
                is Recorder.State.Paused ->
                    NotificationContent("Paused at ${LiveTranscript.clock(state.elapsed / 1000.0)}", text, null, listOf(Action.RESUME, Action.STOP))
                Recorder.State.Stopping -> NotificationContent("Stopping…", text, null, emptyList())
                Recorder.State.Idle -> NotificationContent("Starting…", text, null, emptyList())
            }
        }
    }
}

/** The ongoing notification of [RecordingService]. */
object RecordingNotification {
    fun build(context: Context, channel: String, state: Recorder.State, serverName: String?): Notification {
        val c = NotificationContent.of(state, serverName)
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = Notification.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(c.title)
            .setContentText(c.text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .setContentIntent(open)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        if (c.chronometerBase != null) {
            b.setUsesChronometer(true).setShowWhen(true).setWhen(c.chronometerBase)
        } else {
            b.setUsesChronometer(false).setShowWhen(false)
        }
        for (a in c.actions) b.addAction(action(context, a))
        return b.build()
    }

    private fun action(context: Context, a: NotificationContent.Action): Notification.Action {
        val (label, act, icon) = when (a) {
            NotificationContent.Action.PAUSE -> Triple("Pause", RecordingService.ACTION_PAUSE, android.R.drawable.ic_media_pause)
            NotificationContent.Action.RESUME -> Triple("Resume", RecordingService.ACTION_RESUME, android.R.drawable.ic_media_play)
            NotificationContent.Action.STOP -> Triple("Stop", RecordingService.ACTION_STOP, android.R.drawable.ic_menu_close_clear_cancel)
        }
        val pi = PendingIntent.getService(
            context, a.ordinal + 1, Intent(context, RecordingService::class.java).setAction(act),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Action.Builder(Icon.createWithResource(context, icon), label, pi).build()
    }
}
