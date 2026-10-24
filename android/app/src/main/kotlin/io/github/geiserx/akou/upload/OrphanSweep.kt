// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou.upload

import io.github.geiserx.akou.core.client.JobsClient
import io.github.geiserx.akou.core.client.UploadQueue
import java.io.File

/**
 * The start-up sweep: a recording file with no queue item is one whose process died before the
 * recording was queued. Android 13 and later show a Stop button for every foreground service in the
 * notification shade, and it removes the whole app with no callback; OEMs kill services too. iOS
 * queues only when a recording stops (RecordingController.swift:161-187) and has no such path.
 *
 * A file is an orphan when it is a `.opus` file in the recordings directory, no queue item names
 * it, and it is not the file the recorder is writing now ([inUse]). The recorder names its file
 * before it creates it and lets go of it only after the recording is queued, so a file seen
 * without either is never written again. An orphan is repaired ([OggRepair]) and queued for
 * "Record to" (the server it started for is not in the file), with its file name as its recording
 * id, which is the id the recorder gave it. One with no audio page is deleted: there is nothing in
 * it to send. A file that is not an Ogg Opus stream is left alone.
 */
class OrphanSweep(private val queue: UploadQueue, private val inUse: () -> File?) {
    sealed interface Outcome {
        data class Queued(val repair: OggRepair.Result) : Outcome

        data object Deleted : Outcome

        data class Skipped(val why: String) : Outcome
    }

    /** What happened to each file looked at, by file name. Files the queue already holds are not looked at. */
    fun run(): Map<String, Outcome> {
        val files = queue.audioDirectory.listFiles { f -> f.isFile && f.name.endsWith(SUFFIX) }?.sortedBy { it.name } ?: return emptyMap()
        val out = linkedMapOf<String, Outcome>()
        for (f in files) {
            if (queued(f)) continue
            if (inUse()?.absoluteFile == f.absoluteFile) {
                out[f.name] = Outcome.Skipped("being recorded")
                continue
            }
            // A recording that stopped while the sweep ran is queued by now; its file is whole either way.
            if (queued(f)) continue
            out[f.name] = when (val r = OggRepair.repair(f)) {
                OggRepair.Result.NotARecording -> Outcome.Skipped("not an Ogg Opus recording")
                OggRepair.Result.NoAudio -> {
                    f.delete()
                    Outcome.Deleted
                }
                else -> {
                    queue.enqueue(JobsClient.Submission(recordingId = f.name.removeSuffix(SUFFIX), language = "auto"), f.name, serverId = null)
                    Outcome.Queued(r)
                }
            }
        }
        return out
    }

    private fun queued(f: File): Boolean {
        val id = f.name.removeSuffix(SUFFIX)
        return queue.item(id) != null || queue.items.any { it.fileName == f.name }
    }

    private companion object {
        const val SUFFIX = ".opus"
    }
}
