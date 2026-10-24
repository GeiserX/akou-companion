// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou.upload

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.geiserx.akou.core.client.JobsClient
import io.github.geiserx.akou.core.client.UploadQueue
import io.github.geiserx.akou.recorder.FinishedRecording
import io.github.geiserx.akou.recorder.RecordingService
import io.github.geiserx.akou.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.time.Instant
import java.util.UUID

/**
 * The upload queue of this process: its file in `filesDir`, the WorkManager work that drives it,
 * and what the Record screen reads of it.
 *
 * Each recording has its own unique work, `upload-<recording id>`, so two runs for one recording
 * never overlap. The schedule is the queue's ([UploadSchedule]): a run that leaves the item
 * waiting appends the next run with the queue's wait as its initial delay. Every run needs a
 * network (CONNECTED), so a recording made offline goes up by itself when the network returns.
 */
class Uploads private constructor(context: Context) {
    private val app = context.applicationContext

    /**
     * The keep-a-copy setting as the last upload run read it. Until a run reads it, true: the
     * phone's copy is never deleted on a setting nobody has read.
     */
    @Volatile
    var keepLocalCopy: Boolean = true

    val queue = UploadQueue(
        UploadQueue.FileStore(File(app.filesDir, QUEUE_FILE)),
        RecordingService.recordingsDirectory(app),
        keepLocalCopy = { keepLocalCopy },
    )

    init {
        // This process has just opened the queue and runs no upload yet, so an item left
        // `uploading` was claimed by a process that died; it goes back to pending.
        queue.requeueInterrupted()
    }

    private val _items = MutableStateFlow(queue.items)
    val items: StateFlow<List<UploadQueue.Item>> = _items.asStateFlow()

    fun refresh() {
        _items.value = queue.items
    }

    /** Queues [finished] for its server and asks WorkManager to upload it as soon as there is a network. */
    fun enqueue(finished: FinishedRecording) {
        queue.enqueue(
            JobsClient.Submission(recordingId = finished.id.toString(), language = finished.language),
            fileName = finished.file.name,
            serverId = finished.serverId,
        )
        refresh()
        schedule(finished.id.toString())
    }

    /**
     * At start: recordings left by a killed recorder are repaired and queued ([OrphanSweep]), and
     * every item with a request still to make gets its work, unless it already has some.
     * [inUse] is the file the recorder is writing now.
     */
    fun startUp(inUse: () -> File?) {
        for ((name, outcome) in OrphanSweep(queue, inUse).run()) Log.i(TAG, "sweep $name: $outcome")
        refresh()
        scheduleAll()
    }

    /** The settings changed: parked uploads get another try, and waiting ones a server that may now be ready. */
    fun settingsChanged() {
        queue.retryParked()
        refresh()
        scheduleAll()
    }

    fun scheduleAll() {
        for (item in queue.items) schedule(item.recordingId)
    }

    /** Work for [recordingId] from outside a run: kept as it is when the recording already has some. */
    fun schedule(recordingId: String) {
        val item = queue.item(recordingId) ?: return
        val w = UploadSchedule.first(item, Instant.now()) ?: return
        WorkManager.getInstance(app).enqueueUniqueWork(workName(recordingId), ExistingWorkPolicy.KEEP, request(recordingId, w))
    }

    /** From inside a run: the next run, after this one ends, when the queue left the item waiting. */
    internal fun scheduleNext(recordingId: String) {
        val item = queue.item(recordingId) ?: return
        val w = UploadSchedule.next(item, Instant.now()) ?: return
        WorkManager.getInstance(app).enqueueUniqueWork(workName(recordingId), ExistingWorkPolicy.APPEND_OR_REPLACE, request(recordingId, w))
    }

    companion object {
        const val QUEUE_FILE = "upload-queue.json"
        const val TAG = "akou-upload"
        const val KEY_RECORDING = "recording_id"

        fun workName(recordingId: String) = "upload-$recordingId"

        /** The request for one run: CONNECTED, and expedited when due now, else after the queue's wait. */
        fun request(recordingId: String, w: UploadSchedule.When): OneTimeWorkRequest {
            val b = OneTimeWorkRequestBuilder<UploadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(workDataOf(KEY_RECORDING to recordingId))
                .addTag(TAG)
            when (w) {
                UploadSchedule.When.Now -> b.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                is UploadSchedule.When.After -> b.setInitialDelay(w.delay)
            }
            return b.build()
        }

        // Holds only the application context, which lives as long as the process.
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: Uploads? = null

        fun get(context: Context): Uploads = instance ?: synchronized(this) {
            instance ?: Uploads(context).also { instance = it }
        }
    }
}

/** One run for one recording: its upload if due, then its read-back if due, then the next run if it waits. */
class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val uploads = Uploads.get(applicationContext)
        // Work queued by an earlier build names no recording; the start-up pass gives every item its own.
        val id = inputData.getString(Uploads.KEY_RECORDING) ?: return Result.success()
        val settings = Settings(applicationContext)
        uploads.keepLocalCopy = settings.keepLocalCopy()
        val book = settings.book()
        val clients = buildMap<UUID, JobsClient> {
            for (s in book.servers) {
                val url = s.baseUrl ?: continue
                val key = settings.key(s.id)?.takeIf { it.isNotEmpty() } ?: continue
                put(s.id, JobsClient(url, key))
            }
        }
        try {
            UploadPass(uploads.queue).run(book.servers.map { it.id }.toSet(), book.recordTo, clients, only = id)
        } finally {
            uploads.refresh()
        }
        val item = uploads.queue.item(id)
        Log.i(TAG, "upload $id: ${item?.state}${item?.lastError?.let { " ($it)" } ?: ""}")
        uploads.scheduleNext(id)
        return Result.success()
    }

    private companion object {
        const val TAG = "UploadWorker"
    }
}
