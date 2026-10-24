// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.github.geiserx.akou.recorder.RecordingService
import io.github.geiserx.akou.ui.RecordScreen
import io.github.geiserx.akou.ui.SettingsScreen
import io.github.geiserx.akou.upload.Uploads
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** The Record screen and the server settings, dark by default. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        startUpOnce()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                    var settings by rememberSaveable { mutableStateOf(false) }
                    BackHandler(enabled = settings) { settings = false }
                    if (settings) SettingsScreen(onBack = { settings = false }) else RecordScreen(onSettings = { settings = true })
                }
            }
        }
    }

    /**
     * Once per process, off the main thread: recordings a killed recorder left are repaired and
     * queued, and every waiting upload gets its work (opening the queue requeues uploads a dead
     * process had claimed).
     */
    private fun startUpOnce() {
        if (!startedUp.compareAndSet(false, true)) return
        val app = applicationContext
        val recorder = RecordingService.recorder(app)
        thread(name = "akou-start-up") { Uploads.get(app).startUp { recorder.fileInUse } }
    }

    private companion object {
        val startedUp = AtomicBoolean(false)
    }
}
