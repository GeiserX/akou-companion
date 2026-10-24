// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.geiserx.akou.core.client.ServerBook
import io.github.geiserx.akou.core.client.ServerProbe
import io.github.geiserx.akou.recorder.ByHand
import io.github.geiserx.akou.settings.ServerForm
import io.github.geiserx.akou.settings.Settings
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Saves one server, its key and the keep-a-copy setting as the Settings screen does, so the A2
 * measurements (docs/ANDROID.md section 4) drive the installed app itself from adb: Record, the
 * notification's Stop, a swipe from recents, the shade's Stop. Run by hand with `-e akouSetupUrl`,
 * `-e akouSetupKeyFile` (a device path holding a bare `ak_` key) and `-e akouSetupKeepCopy`.
 *
 * It fails unless the server answers `/v1/server` and accepts the key with the jobs scope, so a
 * measurement never starts against a server the uploads cannot reach.
 */
@ByHand
@RunWith(AndroidJUnit4::class)
class MeasureSetup {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val args = InstrumentationRegistry.getArguments()

    @Test
    fun saveTheServerTheKeyAndTheKeepCopySetting(): Unit = runBlocking {
        val url = args.getString("akouSetupUrl")
        val keyFile = args.getString("akouSetupKeyFile")
        assumeTrue("set akouSetupUrl and akouSetupKeyFile", url != null && keyFile != null)
        val keep = args.getString("akouSetupKeepCopy") == "true"
        val key = File(keyFile!!).readText().trim()

        ServerProbe(url!!, key).server()
        val info = ServerProbe(url, key).keyInfo()
        assertTrue(info.canUpload, "the key cannot upload recordings, its scopes are ${info.scopes}")

        val settings = Settings(context)
        // One server, as A1 and A2 edit it: a fresh book, so no earlier run's server lingers.
        val id = UUID.randomUUID()
        val saved = ServerForm.save(ServerBook(), id, url, key, null)
        settings.save(saved.book.copy(recordTo = id))
        assertTrue(settings.keys.save(key, id.toString()))
        settings.setKeepLocalCopy(keep)

        assertEquals(id, settings.recordTarget()?.id)
        assertEquals(keep, settings.keepLocalCopy())
    }
}
