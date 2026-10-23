// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou.upload

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.geiserx.akou.core.client.JobsClient
import io.github.geiserx.akou.core.client.UploadQueue
import io.github.geiserx.akou.recorder.FinishedRecording
import io.github.geiserx.akou.recorder.RecordingService
import io.github.geiserx.akou.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID

/** The upload queue of this process: its file in `filesDir`, and what the Record screen reads of it. */
class Uploads private constructor(context: Context) {
    private val app = context.applicationContext

    val queue = UploadQueue(
        UploadQueue.FileStore(File(app.filesDir, QUEUE_FILE)),
        RecordingService.recordingsDirectory(app),
        // A1 never reads back and never deletes: the phone keeps every recording.
        keepLocalCopy = { true },
    )

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
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag(TAG)
            .build()
        WorkManager.getInstance(app).enqueueUniqueWork("upload-${finished.id}", ExistingWorkPolicy.KEEP, request)
    }

    companion object {
        const val QUEUE_FILE = "upload-queue.json"
        const val TAG = "akou-upload"

        // Holds only the application context, which lives as long as the process.
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: Uploads? = null

        fun get(context: Context): Uploads = instance ?: synchronized(this) {
            instance ?: Uploads(context).also { instance = it }
        }
    }
}

/** Uploads what is due, once (A1 has no retries), and reports every answer to the queue. */
class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val uploads = Uploads.get(applicationContext)
        val settings = Settings(applicationContext)
        val book = settings.book()
        val clients = buildMap<UUID, JobsClient> {
            for (s in book.servers) {
                val url = s.baseUrl ?: continue
                val key = settings.key(s.id)?.takeIf { it.isNotEmpty() } ?: continue
                put(s.id, JobsClient(url, key))
            }
        }
        try {
            UploadPass(uploads.queue).run(book.servers.map { it.id }.toSet(), book.recordTo, clients)
        } finally {
            uploads.refresh()
        }
        for (item in uploads.queue.items) Log.i(TAG, "upload ${item.recordingId}: ${item.state}${item.lastError?.let { " ($it)" } ?: ""}")
        return Result.success()
    }

    private companion object {
        const val TAG = "UploadWorker"
    }
}
