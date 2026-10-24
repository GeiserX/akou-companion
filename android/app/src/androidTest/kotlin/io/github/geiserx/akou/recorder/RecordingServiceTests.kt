// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou.recorder

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.os.PowerManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.geiserx.akou.MainActivity
import io.github.geiserx.akou.core.client.ServerBook
import io.github.geiserx.akou.core.ogg.OggPage
import io.github.geiserx.akou.settings.Settings
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The real service and its notification on the device: the chronometer, Pause, Resume and Stop sent
 * as the notification's own PendingIntents, then the app swiped from recents and the screen turned
 * off with the recording still taking samples. No server is set, so nothing leaves the device.
 */
@RunWith(AndroidJUnit4::class)
class RecordingServiceTests {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val nm = context.getSystemService(NotificationManager::class.java)
    private val recorder get() = RecordingService.recorder(context)

    @Before
    fun setUp() {
        val ui = instrumentation.uiAutomation
        ui.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        ui.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        // No Record to server: no live text and no upload target.
        runBlocking { Settings(context).save(ServerBook()) }
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
    }

    @After
    fun tearDown() {
        if (!recorder.isIdle) {
            RecordingService.stop(context)
            await("idle after the test") { recorder.isIdle }
        }
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
    }

    @Test
    fun theNotificationDrivesTheRecordingThroughTheSwipeAndTheLock() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        // Started from a visible activity, as the Record button does.
        RecordingService.start(context)
        await("recording") { recorder.state.value is Recorder.State.Recording }
        val startedAt = (recorder.state.value as Recorder.State.Recording).startedAt

        var n = awaitNotification(listOf("Pause", "Stop"))
        assertTrue(n.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER), "the chronometer runs while recording")
        assertEquals(startedAt, n.`when`, "it counts from the recording's start")
        assertNotNull(n.contentIntent, "a tap opens the Record screen")
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals("On this phone only", n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())

        // Pause from the notification: no samples while paused, and the chronometer stops.
        Thread.sleep(1_000)
        action(n, "Pause").actionIntent.send()
        await("paused") { recorder.state.value is Recorder.State.Paused }
        n = awaitNotification(listOf("Resume", "Stop"))
        assertFalse(n.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER), "the chronometer stops while paused")
        val atPause = recorder.deliveredSamples
        Thread.sleep(1_500)
        assertEquals(atPause, recorder.deliveredSamples, "nothing is captured while paused")

        // Resume: the chronometer's base moves on by the paused time.
        action(n, "Resume").actionIntent.send()
        await("recording again") { recorder.state.value is Recorder.State.Recording }
        val resumedBase = (recorder.state.value as Recorder.State.Recording).startedAt
        assertTrue(resumedBase - startedAt >= 1_400, "the base moved by the pause: ${resumedBase - startedAt} ms")
        n = awaitNotification(listOf("Pause", "Stop"))
        assertEquals(resumedBase, n.`when`)

        // Swiped from recents: the service and the recording go on.
        val removedBefore = RecordingService.tasksRemoved
        scenario.onActivity { it.finishAndRemoveTask() }
        await("onTaskRemoved ran") { RecordingService.tasksRemoved > removedBefore }
        val atSwipe = recorder.deliveredSamples
        Thread.sleep(1_500)
        assertTrue(recorder.state.value is Recorder.State.Recording)
        // Samples keep coming. The emulator's microphone runs slower than real time on a loaded
        // host, so this asks for flow, not for an exact rate.
        assertTrue(recorder.deliveredSamples - atSwipe >= 8_000, "samples after the swipe: ${recorder.deliveredSamples - atSwipe}")
        awaitNotification(listOf("Pause", "Stop"))

        // The screen goes off and locks: still recording, still taking samples.
        val power = context.getSystemService(PowerManager::class.java)
        shell("input keyevent KEYCODE_SLEEP")
        await("screen off") { !power.isInteractive }
        val atLock = recorder.deliveredSamples
        Thread.sleep(3_000)
        val locked = recorder.deliveredSamples - atLock
        assertTrue(locked >= 16_000, "samples with the screen off: $locked in 3 s")
        assertTrue(recorder.state.value is Recorder.State.Recording)

        // Stop from the notification, with the screen still off.
        var finished: FinishedRecording? = null
        val previous = recorder.onFinished
        recorder.onFinished = { f -> finished = f; previous(f) }
        action(awaitNotification(listOf("Pause", "Stop")), "Stop").actionIntent.send()
        await("idle") { recorder.isIdle }
        await("the notification is gone") { nm.activeNotifications.none { it.id == RecordingService.NOTIFICATION_ID } }
        recorder.onFinished = previous
        val f = assertNotNull(finished)
        val pages = OggPage.readAll(f.file.readBytes())
        assertEquals(OggPage.Flags.END_OF_STREAM, pages.last().flags)
        assertEquals((0 until pages.size).map { it.toUInt() }, pages.map { it.sequence })
        // The file holds the time recorded, never the pause.
        val wall = (System.currentTimeMillis() - startedAt) / 1000.0
        assertTrue(f.seconds < wall - 1.4, "file ${f.seconds} s, ${wall} s since the start")
        File(f.file.path).delete()
        scenario.close()
    }

    private fun awaitNotification(actions: List<String>): Notification {
        var last: List<String>? = null
        val until = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < until) {
            val n = nm.activeNotifications.firstOrNull { it.id == RecordingService.NOTIFICATION_ID }?.notification
            last = n?.actions?.map { it.title.toString() }
            if (n != null && last == actions) return n
            Thread.sleep(50)
        }
        fail("no notification with $actions; last had $last")
    }

    private fun action(n: Notification, title: String): Notification.Action =
        n.actions.first { it.title.toString() == title }

    private fun await(what: String, cond: () -> Boolean) {
        val until = System.currentTimeMillis() + 10_000
        while (!cond()) {
            if (System.currentTimeMillis() > until) fail("timed out waiting for $what")
            Thread.sleep(20)
        }
    }

    private fun shell(cmd: String) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(cmd)).use { it.readBytes() }
    }
}
