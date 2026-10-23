// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.geiserx.akou.core.client.LiveSession
import io.github.geiserx.akou.core.client.LiveTranscript
import io.github.geiserx.akou.core.client.ServerProbe
import io.github.geiserx.akou.core.client.UploadQueue
import io.github.geiserx.akou.recorder.Recorder
import io.github.geiserx.akou.recorder.RecordingService
import io.github.geiserx.akou.settings.Settings
import io.github.geiserx.akou.upload.Uploads
import kotlinx.coroutines.delay

/** Start and stop, the elapsed time, the live words and the last recording's upload. */
@Composable
fun RecordScreen(onSettings: () -> Unit) {
    val context = LocalContext.current
    val recorder = remember { RecordingService.recorder(context) }
    val uploads = remember { Uploads.get(context) }
    val state by recorder.state.collectAsState()
    val transcript by recorder.transcript.collectAsState()
    val live by recorder.liveState.collectAsState()
    val lastError by recorder.lastError.collectAsState()
    val startError by RecordingService.startError.collectAsState()
    val items by uploads.items.collectAsState()
    var serverNote by remember { mutableStateOf<String?>(null) }
    var permissionNote by remember { mutableStateOf<String?>(null) }

    // Whether "Record to" has live text, so the screen can say so before recording (RecordView.swift).
    LaunchedEffect(Unit) {
        val target = Settings(context).recordTarget()
        if (target == null) {
            recorder.serverSupportsLive = null
            serverNote = "No server set: recordings stay on the phone, without live text. Add one in Settings."
            return@LaunchedEffect
        }
        serverNote = try {
            val info = ServerProbe(target.url, target.key).server()
            recorder.serverSupportsLive = info.supportsLive
            if (info.supportsLive) null else "No live text on this server: the app records, and the transcript comes after the upload."
        } catch (_: Exception) {
            recorder.serverSupportsLive = null
            "The server did not answer; live text will try when you record."
        }
    }

    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        val mic = granted[Manifest.permission.RECORD_AUDIO] ?: hasMic(context)
        val notify = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || granted[Manifest.permission.POST_NOTIFICATIONS] ?: true
        permissionNote = when {
            !mic -> "akou records only with the microphone. Allow it in the system settings: Apps, akou, Permissions, Microphone."
            !notify -> "Without notifications the recording still runs, but you will not see it in the notification drawer."
            else -> null
        }
        if (mic) RecordingService.start(context)
    }

    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("akou", style = MaterialTheme.typography.headlineMedium)
            TextButton(onClick = onSettings, enabled = state == Recorder.State.Idle) { Text("Settings") }
        }

        val s = state
        Text(
            when (s) {
                is Recorder.State.Recording -> clock(s.startedAt)
                Recorder.State.Stopping -> "Stopping…"
                Recorder.State.Idle -> "Ready"
            },
            style = MaterialTheme.typography.displayMedium,
        )
        Button(
            onClick = {
                when (state) {
                    Recorder.State.Idle -> {
                        val needed = buildList {
                            if (!hasMic(context)) add(Manifest.permission.RECORD_AUDIO)
                            // A runtime permission from API 33; on 31 and 32 notifications need no grant.
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                            ) {
                                add(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }
                        if (needed.isEmpty()) RecordingService.start(context) else ask.launch(needed.toTypedArray())
                    }
                    is Recorder.State.Recording -> RecordingService.stop(context)
                    Recorder.State.Stopping -> {}
                }
            },
            enabled = state != Recorder.State.Stopping,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (state is Recorder.State.Recording) "Stop" else "Record") }

        listOfNotNull(permissionNote, startError, lastError?.let { "The file reported an error: $it" })
            .forEach { Text(it, color = MaterialTheme.colorScheme.error) }
        serverNote?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }

        if (state != Recorder.State.Idle) Text(liveLine(live), color = MaterialTheme.colorScheme.onSurfaceVariant)
        items.lastOrNull()?.let { Text(uploadLine(it), color = MaterialTheme.colorScheme.onSurfaceVariant) }

        LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(transcript.items) { item ->
                when (item) {
                    is LiveTranscript.Line -> Text(item.text)
                    is LiveTranscript.Gap -> Text(
                        LiveTranscript.gapLabel(item.from, item.to),
                        color = MaterialTheme.colorScheme.tertiary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            transcript.openLine?.let { line -> item { Text(line.text, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        }
        Spacer(Modifier.padding(4.dp))
    }
}

@Composable
private fun clock(startedAt: Long): String {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAt) {
        while (true) {
            now = System.currentTimeMillis()
            delay(250)
        }
    }
    return LiveTranscript.clock((now - startedAt) / 1000.0)
}

private fun hasMic(context: android.content.Context) =
    context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

private fun liveLine(s: LiveSession.State): String = when (s) {
    LiveSession.State.Connecting -> "Live text: connecting"
    is LiveSession.State.Live -> "Live text: ${s.engine}"
    is LiveSession.State.Paused -> "Live text paused (${s.reason}), reconnecting"
    is LiveSession.State.Off -> when (s.reason) {
        "no_server" -> "No live text: no server with a key"
        "stopped", "idle" -> "Live text off"
        else -> "Live text off: ${s.reason}"
    }
}

private fun uploadLine(item: UploadQueue.Item): String = when (val st = item.state) {
    UploadQueue.State.Pending -> "Last recording: waiting to upload" + (item.lastError?.let { " ($it)" } ?: "")
    UploadQueue.State.Uploading -> "Last recording: uploading"
    is UploadQueue.State.Submitted -> "Last recording: uploaded, job ${st.jobId}"
    is UploadQueue.State.Done -> "Last recording: on the server, job ${st.jobId}"
    is UploadQueue.State.Parked -> "Last recording: not uploaded" + (item.lastError?.let { " ($it)" } ?: "")
}
