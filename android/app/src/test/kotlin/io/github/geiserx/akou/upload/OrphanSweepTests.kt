// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou.upload

import io.github.geiserx.akou.core.client.JobsClient
import io.github.geiserx.akou.core.client.UploadQueue
import io.github.geiserx.akou.core.ogg.OggOpusWriter
import io.github.geiserx.akou.core.ogg.OggPage
import io.github.geiserx.akou.core.ogg.OpusEncoder
import io.github.geiserx.akou.recorder.FakeEncoder
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The start-up sweep: what makes a file an orphan, and how a killed recording is made whole and queued. */
class OrphanSweepTests {
    @get:Rule val tmp = TemporaryFolder()

    private val dir get() = File(tmp.root, "Recordings").apply { mkdirs() }

    private fun queue() = UploadQueue(UploadQueue.FileStore(File(tmp.root, "queue.json")), dir, keepLocalCopy = { true })

    /**
     * What a recorder killed mid-write leaves: the header pages and [pages] audio pages of 10
     * frames each, as the writer makes them, and none of them ends the stream.
     */
    private fun killedRecording(name: String, pages: Int): Pair<File, ByteArray> {
        val writer = OggOpusWriter(FakeEncoder())
        val out = ByteArrayOutputStream()
        writer.headerPages().forEach(out::write)
        var written = 0
        while (written < pages) {
            writer.append(FloatArray(OpusEncoder.FRAME_SAMPLES))?.let { out.write(it); written += 1 }
        }
        val f = File(dir, name)
        f.writeBytes(out.toByteArray())
        return f to out.toByteArray()
    }

    @Test
    fun aTornQueueFileLosesNoRecordingTheSweepQueuesItAgainUnderItsOwnId() {
        val id = "33333333-3333-3333-3333-333333333333"
        val (f, _) = killedRecording("$id.opus", pages = 2)
        OggRepair.repair(f)
        queue().enqueue(JobsClient.Submission(recordingId = id, language = "auto"), f.name)
        val qf = File(tmp.root, "queue.json")
        qf.writeBytes(qf.readBytes().copyOf(qf.length().toInt() - 3))

        val q = queue() // the app's next start: no crash, an empty queue
        assertTrue(q.startedFromUnreadableStore)
        val outcome = OrphanSweep(q) { null }.run()[f.name]
        assertEquals(OrphanSweep.Outcome.Queued(OggRepair.Result.Intact), outcome)
        assertEquals(UploadQueue.State.Pending, q.item(id)?.state) // same id, so the same Idempotency-Key
    }

    @Test
    fun aFileCutMidPageIsRepairedToAWholeStreamAndQueued() {
        val (f, whole) = killedRecording("11111111-1111-1111-1111-111111111111.opus", pages = 5)
        val lastWhole = whole.size
        // The process died 20 bytes into the next page.
        f.appendBytes("OggS".toByteArray() + ByteArray(16) { 7 })

        val q = queue()
        val outcome = OrphanSweep(q) { null }.run()[f.name]
        assertEquals(OrphanSweep.Outcome.Queued(OggRepair.Result.Repaired(droppedBytes = 20, endMarked = true)), outcome)
        assertEquals(lastWhole.toLong(), f.length())

        val pages = OggPage.readAll(f.readBytes()) // every CRC checked, the patched one included
        assertEquals(7, pages.size)
        assertTrue(OggPage.Flags.END_OF_STREAM in pages.last().flags)
        assertTrue(pages.dropLast(1).none { OggPage.Flags.END_OF_STREAM in it.flags })
        // The end page carries the pre-skip, as the writer's own last page does.
        assertEquals(312 + 5 * 10 * 960L, pages.last().granulePosition)
        // Only the flag, the granule and the CRC of the last page changed.
        val bytes = f.readBytes()
        val diff = whole.indices.filter { whole[it] != bytes[it] }
        val last = lastPageStart(whole)
        assertTrue(diff.all { it == last + 5 || it in last + 6 until last + 14 || it in last + 22 until last + 26 }, "$diff")

        val item = q.item("11111111-1111-1111-1111-111111111111")!!
        assertEquals(f.name, item.fileName)
        assertEquals(UploadQueue.State.Pending, item.state)
        assertNull(item.serverId) // "Record to" at the first pass
        assertEquals(item.recordingId, item.submission.recordingId) // the Idempotency-Key

        // Read as an intact 1.0 s file reads: 1.0065 s with the pre-skip, 1.0 s without it.
        ffprobeSeconds(f)?.let { s -> assertTrue(abs(s - 1.0) < 0.001, "ffprobe reads $s s for 50 frames") }
    }

    @Test
    fun aFileCutOnAPageBoundaryOnlyGetsItsEnd() {
        val (f, _) = killedRecording("22222222-2222-2222-2222-222222222222.opus", pages = 2)
        val out = OrphanSweep(queue()) { null }.run()[f.name]
        assertEquals(OrphanSweep.Outcome.Queued(OggRepair.Result.Repaired(droppedBytes = 0, endMarked = true)), out)
        assertTrue(OggPage.Flags.END_OF_STREAM in OggPage.readAll(f.readBytes()).last().flags)
    }

    @Test
    fun theFileStillBeingRecordedIsLeftAlone() {
        val (f, bytes) = killedRecording("33333333-3333-3333-3333-333333333333.opus", pages = 3)
        f.appendBytes("OggS".toByteArray())
        val q = queue()
        val out = OrphanSweep(q) { File(dir, f.name) }.run()[f.name]
        assertEquals(OrphanSweep.Outcome.Skipped("being recorded"), out)
        assertContentEquals(bytes + "OggS".toByteArray(), f.readBytes())
        assertTrue(q.items.isEmpty())
    }

    @Test
    fun aFileTheQueueHoldsIsNotLookedAt() {
        val (f, bytes) = killedRecording("44444444-4444-4444-4444-444444444444.opus", pages = 3)
        val q = queue()
        q.enqueue(JobsClient.Submission(recordingId = "44444444-4444-4444-4444-444444444444", language = "es"), f.name)
        assertTrue(OrphanSweep(q) { null }.run().isEmpty())
        assertContentEquals(bytes, f.readBytes())
        assertEquals("es", q.items.single().submission.language)
    }

    @Test
    fun aWholeFileThatMissedTheQueueIsQueuedUnchanged() {
        val writer = OggOpusWriter(FakeEncoder())
        val out = ByteArrayOutputStream()
        writer.headerPages().forEach(out::write)
        repeat(25) { writer.append(FloatArray(OpusEncoder.FRAME_SAMPLES))?.let(out::write) }
        out.write(writer.finish())
        val f = File(dir, "55555555-5555-5555-5555-555555555555.opus").apply { writeBytes(out.toByteArray()) }
        val q = queue()
        assertEquals(OrphanSweep.Outcome.Queued(OggRepair.Result.Intact), OrphanSweep(q) { null }.run()[f.name])
        assertContentEquals(out.toByteArray(), f.readBytes())
        assertEquals(1, q.items.size)
    }

    @Test
    fun aRecordingKilledBeforeItsFirstAudioPageIsDeleted() {
        val headersOnly = File(dir, "66666666-6666-6666-6666-666666666666.opus").apply {
            writeBytes(OggOpusWriter(FakeEncoder()).headerPages().reduce(ByteArray::plus))
        }
        val empty = File(dir, "77777777-7777-7777-7777-777777777777.opus").apply { writeBytes(ByteArray(0)) }
        val q = queue()
        val out = OrphanSweep(q) { null }.run()
        assertEquals(OrphanSweep.Outcome.Deleted, out[headersOnly.name])
        assertEquals(OrphanSweep.Outcome.Deleted, out[empty.name])
        assertFalse(headersOnly.exists())
        assertFalse(empty.exists())
        assertTrue(q.items.isEmpty())
    }

    @Test
    fun aFileThatIsNotOggOpusIsLeftAsItIs() {
        val f = File(dir, "not-ours.opus").apply { writeText("RIFF....WAVE") }
        val other = File(dir, "notes.txt").apply { writeText("x") }
        val q = queue()
        val out = OrphanSweep(q) { null }.run()
        assertIs<OrphanSweep.Outcome.Skipped>(out[f.name])
        assertEquals(null, out[other.name])
        assertEquals("RIFF....WAVE", f.readText())
        assertTrue(q.items.isEmpty())
    }

    @Test
    fun theSweepIsIdempotent() {
        val (f, _) = killedRecording("88888888-8888-8888-8888-888888888888.opus", pages = 3)
        f.appendBytes(ByteArray(9) { 1 })
        val q = queue()
        OrphanSweep(q) { null }.run()
        val after = f.readBytes()
        assertTrue(OrphanSweep(q) { null }.run().isEmpty())
        assertEquals(OggRepair.Result.Intact, OggRepair.repair(f))
        assertContentEquals(after, f.readBytes())
    }

    private fun lastPageStart(data: ByteArray): Int {
        var off = 0
        var last = 0
        while (off < data.size) {
            last = off
            off += OggPage.read(data, off).length
        }
        return last
    }

    /**
     * ffprobe's duration, less the 312-sample pre-skip it counts in; null (skipped) when ffprobe is
     * not installed, but on CI (CI=true) a missing ffprobe fails, as in :core.
     */
    private fun ffprobeSeconds(f: File): Double? {
        val dirs = (System.getenv("PATH") ?: "").split(File.pathSeparator) + listOf("/opt/homebrew/bin", "/usr/local/bin", "/usr/bin")
        val ffprobe = dirs.filter { it.isNotEmpty() }.map { File(it, "ffprobe") }.firstOrNull { it.canExecute() }
        if (ffprobe == null) {
            if (System.getenv("CI") == "true") throw AssertionError("ffprobe not found and CI=true: the repair check must run on CI")
            Assume.assumeTrue("ffprobe not installed", false)
            return null
        }
        val p = ProcessBuilder(ffprobe.path, "-v", "error", "-show_entries", "format=duration", "-of", "default=nw=1:nk=1", f.path)
            .redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().readText()
        assertTrue(p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0, "ffprobe failed: $text")
        val seconds = text.lineSequence().mapNotNull { it.trim().toDoubleOrNull() }.firstOrNull() ?: throw AssertionError("no duration: $text")
        return seconds - 312.0 / 48000.0
    }
}
