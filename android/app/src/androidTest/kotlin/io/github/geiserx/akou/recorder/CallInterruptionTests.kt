// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou.recorder

import android.Manifest
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.geiserx.akou.MainActivity
import io.github.geiserx.akou.core.client.ServerBook
import io.github.geiserx.akou.settings.Settings
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A phone call pauses the recording and its end resumes it. Run by hand, never in the connected
 * run: the host places the call. With `-e akouCallTest true`, it starts recording, logs "recording
 * started", and waits for the host to ring (`adb emu gsm call <n>`). The emulator has no one to
 * pick up, so the test answers the ringing call itself through Telecom (with the shell's permission),
 * then waits for the system pause, for the hang-up (`gsm cancel <n>`) and the resume.
 */
@ByHand
@RunWith(AndroidJUnit4::class)
class CallInterruptionTests {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun aCallPausesTheRecordingAndItsEndResumesIt() {
        assumeTrue("run by hand with -e akouCallTest true", InstrumentationRegistry.getArguments().getString("akouCallTest") == "true")
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        runBlocking { Settings(context).save(ServerBook()) }
        val recorder = RecordingService.recorder(context)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        RecordingService.start(context)
        await("recording", 10_000) { recorder.state.value is Recorder.State.Recording }
        Log.i(TAG, "recording started")
        val answer = Thread {
            val telecom = context.getSystemService(TelecomManager::class.java)
            val phone = context.getSystemService(TelephonyManager::class.java)
            instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.ANSWER_PHONE_CALLS, Manifest.permission.READ_PHONE_STATE)
            try {
                val until = System.currentTimeMillis() + 90_000
                while (System.currentTimeMillis() < until) {
                    @Suppress("DEPRECATION")
                    if (phone.callState == TelephonyManager.CALL_STATE_RINGING) {
                        @Suppress("DEPRECATION")
                        telecom.acceptRingingCall()
                        Log.i(TAG, "answered the ringing call")
                        return@Thread
                    }
                    Thread.sleep(200)
                }
            } catch (_: InterruptedException) {
            } finally {
                instrumentation.uiAutomation.dropShellPermissionIdentity()
            }
        }.apply { start() }
        try {
            await("a system pause", 90_000) { recorder.state.value is Recorder.State.Paused }
            assertTrue(recorder.pausedBySystem, "the call's pause is the system's")
            val atPause = recorder.deliveredSamples
            Log.i(TAG, "paused by the system")
            await("the resume after the call", 90_000) { recorder.state.value is Recorder.State.Recording }
            Log.i(TAG, "resumed after the call; ${(recorder.deliveredSamples - atPause)} samples since the pause")
        } finally {
            answer.interrupt()
            RecordingService.stop(context)
            await("idle", 15_000) { recorder.isIdle }
            scenario.close()
        }
    }

    private fun await(what: String, ms: Long, cond: () -> Boolean) {
        val until = System.currentTimeMillis() + ms
        while (!cond()) {
            if (System.currentTimeMillis() > until) fail("timed out waiting for $what")
            Thread.sleep(50)
        }
    }

    private companion object {
        const val TAG = "CallInterruptionTests"
    }
}
