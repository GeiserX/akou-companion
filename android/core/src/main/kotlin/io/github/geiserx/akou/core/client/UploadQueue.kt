// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou.core.client

import io.github.geiserx.akou.core.protocol.AkouErrorBody
import io.github.geiserx.akou.core.protocol.AkouJson
import io.github.geiserx.akou.core.protocol.Job
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID

/**
 * The recordings waiting to reach their server, kept in [store] so they survive an app restart.
 * The port of Sources/AkouClient/UploadQueue.swift.
 *
 * Each recording goes to the server it was recorded for ([Item.serverId]). An item that names no
 * server gets "Record to" written into it by the first pass with a [Routing], whatever its state.
 * From then on each item names its server, so a later change of "Record to" moves none of them,
 * not even one waiting for its read-back. An item whose server was removed is parked with
 * [SERVER_REMOVED_CODE], never dropped, until [reassign] sends it to another server.
 *
 * Each recording moves `pending` -> `uploading` -> `submitted(jobId)` -> `done`. It is keyed by its
 * recording id, which is also the upload's `Idempotency-Key`, so however many times an upload is
 * retried the server makes one job and every retry gets that job back. The queue does no I/O of
 * its own beyond its store and the deletion of a recording it has confirmed on the server: a
 * transport (the foreground [drain], or the app's background worker) runs the requests and reports
 * each answer here.
 *
 * [Item.notBefore] is the earliest time of the next try, not an exact one: a scheduler that wakes
 * later (Doze, WorkManager's minimum delay) still finds the item due.
 *
 * Every method is safe to call from any thread; each transition is saved before it returns.
 */
class UploadQueue(
    private val store: Store,
    /** Where the recordings are; an item holds a file name inside it, never a path. */
    val audioDirectory: File,
    private val now: () -> Instant = Instant::now,
    /** Read each time a recording is confirmed, so a change in the settings applies to the next one. */
    private val keepLocalCopy: () -> Boolean,
) {
    /** Where the queue's state lives: one blob, read once at start and written whole on each change. */
    interface Store {
        /** The last blob written, or null when there is none yet. */
        fun read(): ByteArray?

        /** Replaces the blob; a crash mid-write leaves the old one or the new one, never a mix. */
        fun write(bytes: ByteArray)

        /** The blob could not be read as a queue: keep it out of the way (for a look later), so the next write starts clean. */
        fun setAside() {}
    }

    /**
     * A [Store] in one file, replaced atomically through a temporary file beside it. The temporary
     * file is synced before the rename, so a power loss leaves the old file or the new one, never
     * an empty or a short one.
     */
    class FileStore(private val file: File) : Store {
        override fun read(): ByteArray? = if (file.isFile) file.readBytes() else null

        override fun write(bytes: ByteArray) {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.fd.sync()
            }
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }

        /** Renames the file to `<name>.bad`, replacing an older one. */
        override fun setAside() {
            if (!file.isFile) return
            Files.move(file.toPath(), File(file.parentFile, "${file.name}.bad").toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    @Serializable
    data class Item(
        val submission: JobsClient.Submission,
        /** The recording's file name inside [audioDirectory]. A name, not a path: app storage can move. */
        val fileName: String,
        val state: State,
        /** Failed attempts in a row, for the backoff. */
        val attempts: Int = 0,
        /** Not tried again before this time (a backoff or a server's `Retry-After`). */
        @Serializable(with = InstantAsMillis::class) val notBefore: Instant? = null,
        /** The last failure, for the recordings list. */
        val lastError: String? = null,
        /** The server this recording goes to; null until a [Routing] pass gives it "Record to". */
        @Serializable(with = UuidAsString::class) val serverId: UUID? = null,
    ) {
        val recordingId: String get() = submission.recordingId

        /** Parked because its server was removed: only [reassign] moves it on. */
        val waitsForAServer: Boolean get() = (state as? State.Parked)?.code == SERVER_REMOVED_CODE
    }

    /** Which servers exist, which of them can be sent to now, and which one takes the items that name none. */
    data class Routing(
        /** Every server the settings hold. A recording for any other one is parked. */
        val servers: Set<UUID>,
        val recordTo: UUID?,
        /** The servers with a URL and a key now. A recording for one that is not ready waits, unclaimed. */
        val ready: Set<UUID> = servers,
    ) {
        /** Where an item goes: its own server, else "Record to"; null when there is neither. */
        fun target(item: Item): UUID? = item.serverId ?: recordTo
    }

    @Serializable
    sealed interface State {
        @Serializable @SerialName("pending")
        data object Pending : State

        @Serializable @SerialName("uploading")
        data object Uploading : State

        /** The server answered the upload with this job. */
        @Serializable @SerialName("submitted")
        data class Submitted(val jobId: String) : State

        /** The server's job was read back; [keptOnServer] is its `keep_audio`. */
        @Serializable @SerialName("done")
        data class Done(val jobId: String, val keptOnServer: Boolean) : State

        /**
         * Stopped until [retryParked]: an answer that a retry would only repeat (a 4xx other than
         * 408 and 429, `idempotency_conflict`, a missing local file).
         */
        @Serializable @SerialName("parked")
        data class Parked(val status: Int? = null, val code: String? = null) : State
    }

    /** How a request ended, as a transport reports it. */
    sealed interface Answer {
        class Http(val status: Int, val headers: Headers, val body: ByteArray) : Answer

        /** No answer: no network, a dropped connection, a timeout, a worker stopped mid-upload. */
        data class Unreachable(val why: String) : Answer
    }

    /** One submitted item due for its read-back, on the server it was uploaded to. */
    data class Confirm(val recordingId: String, val jobId: String, val serverId: UUID?)

    private val lock = Any()
    private val byId = LinkedHashMap<String, Item>()

    /** True when the store held a blob that was not a queue: it was set aside and the queue started empty. */
    val startedFromUnreadableStore: Boolean

    init {
        var unreadable = false
        store.read()?.let { bytes ->
            // A torn or corrupt file must not stop the app at every start. The recordings stay on
            // disk, so the start-up sweep queues them again under the same ids, and a recording that
            // comes back after it was sent goes up with the same Idempotency-Key.
            val decoded = try {
                AkouJson.decodeFromString(ITEMS, bytes.decodeToString())
            } catch (_: IllegalArgumentException) { // SerializationException is one
                null
            }
            if (decoded == null) {
                unreadable = true
                // Kept for a look later; if it cannot be moved, the next save replaces it anyway.
                try {
                    store.setAside()
                } catch (_: IOException) {
                }
            } else {
                for (item in decoded) byId.putIfAbsent(item.recordingId, item)
            }
        }
        startedFromUnreadableStore = unreadable
    }

    // Reading

    val items: List<Item> get() = synchronized(lock) { byId.values.toList() }

    fun item(recordingId: String): Item? = synchronized(lock) { byId[recordingId] }

    fun audioFile(item: Item): File = File(audioDirectory, item.fileName)

    /** When the earliest waiting item is due, for a timer; null when nothing waits on a time. */
    fun nextWake(): Instant? = synchronized(lock) {
        byId.values.filter { it.state == State.Pending || it.state is State.Submitted }.mapNotNull { it.notBefore }.minOrNull()
    }

    // Transitions

    /** Adds a finished recording for [serverId], the server it was recorded for. Adding the same recording again changes nothing. */
    fun enqueue(submission: JobsClient.Submission, fileName: String, serverId: UUID? = null): Item = synchronized(lock) {
        byId[submission.recordingId]?.let { return it }
        val item = Item(submission, fileName, State.Pending, serverId = serverId)
        byId[item.recordingId] = item
        save()
        item
    }

    /**
     * The pending items whose time has come, now marked `uploading`. The caller uploads each one
     * to its [Item.serverId] and reports with [uploadEnded].
     *
     * With [routing], each item gets its server first: its own, else "Record to", written into the
     * item. An item with no server to go to waits; one whose server is gone is parked. Without
     * [routing] (one server) every due item is claimed as it is. With [only], that one recording
     * alone is looked at, for a transport that runs one piece of work per recording.
     */
    fun claimDue(routing: Routing? = null, only: String? = null): List<Item> = synchronized(lock) {
        val t = now()
        val claimed = mutableListOf<Item>()
        var changed = routing?.let(::stampLegacy) ?: false
        for ((id, item) in byId.entries.toList()) {
            if (only != null && id != only) continue
            if (item.state != State.Pending || !isDue(item, t)) continue
            var next = item
            if (routing != null) {
                val target = routing.target(item) ?: continue
                if (target !in routing.servers) {
                    byId[id] = parkedForRemovedServer(item)
                    changed = true
                    continue
                }
                if (target !in routing.ready) continue
                next = next.copy(serverId = target)
            }
            next = next.copy(state = State.Uploading)
            byId[id] = next
            claimed += next
            changed = true
        }
        if (changed) save()
        claimed
    }

    /** Sends a parked item to [serverId] and makes it due now. The recording id stays the `Idempotency-Key`. */
    fun reassign(recordingId: String, serverId: UUID): Unit = synchronized(lock) {
        val item = byId[recordingId] ?: return
        if (item.state !is State.Parked) return
        byId[recordingId] = item.copy(serverId = serverId, state = State.Pending, attempts = 0, notBefore = null, lastError = null)
        save()
    }

    /** The upload of [recordingId] ended with [answer]. */
    fun uploadEnded(recordingId: String, answer: Answer): Unit = synchronized(lock) {
        var item = byId[recordingId] ?: return
        if (item.state == State.Pending && answer is Answer.Http && answer.status in 200..299) {
            // A transfer given up on after a restart answered after all: its job is the truth.
            item = item.copy(state = State.Uploading)
        }
        if (item.state != State.Uploading) return
        byId[recordingId] = when (answer) {
            is Answer.Http if answer.status in 200..299 -> {
                val submitted = runCatching { JobsClient.submitted(answer.status, answer.headers, answer.body) }.getOrNull()
                if (submitted != null) {
                    item.copy(state = State.Submitted(submitted.job.id), attempts = 0, notBefore = null, lastError = null)
                } else {
                    // A 2xx that is not akou's job (a captive portal, a proxy page): try again later.
                    retryLater(item, null, "HTTP ${answer.status} without a job").copy(state = State.Pending)
                }
            }
            is Answer.Http -> {
                val f = failure(answer)
                if (isRetryable(answer.status)) {
                    retryLater(item, f.retryAfter, f.text).copy(state = State.Pending)
                } else {
                    item.copy(state = State.Parked(answer.status, f.code), lastError = f.text, notBefore = null)
                }
            }
            is Answer.Unreachable -> retryLater(item, null, answer.why).copy(state = State.Pending)
        }
        save()
    }

    /** The upload could not start: the recording's file is gone, or no request could be made for it. */
    fun uploadCouldNotStart(recordingId: String, why: String): Unit = synchronized(lock) {
        val item = byId[recordingId] ?: return
        if (item.state != State.Uploading) return
        byId[recordingId] = item.copy(state = State.Parked(null, LOCAL_FILE_CODE), lastError = why)
        save()
    }

    /**
     * The submitted items whose time has come: the caller reads each job back with
     * `GET /v1/jobs/{id}` on [Confirm.serverId], the server it was uploaded to, and reports with
     * [confirmEnded]. With [routing], an item whose server was removed is parked instead (its file
     * is still on the phone, so [reassign] can send it elsewhere). With [only], that one recording alone.
     */
    fun dueForConfirm(routing: Routing? = null, only: String? = null): List<Confirm> = synchronized(lock) {
        val t = now()
        val out = mutableListOf<Confirm>()
        var changed = routing?.let(::stampLegacy) ?: false
        for ((id, item) in byId.entries.toList()) {
            if (only != null && id != only) continue
            val state = item.state as? State.Submitted ?: continue
            if (!isDue(item, t)) continue
            // The server it was uploaded to, never "Record to" as it is now.
            val target = item.serverId
            if (routing != null) {
                // No server named and no "Record to" to stamp: it waits, never parked on a guess.
                if (target == null) continue
                if (target !in routing.servers) {
                    byId[id] = parkedForRemovedServer(item)
                    changed = true
                    continue
                }
                if (target !in routing.ready) continue
            }
            out += Confirm(item.recordingId, state.jobId, target)
        }
        if (changed) save()
        out
    }

    /**
     * The read-back of a submitted job ended with [answer]. On the same job, the item is done, and
     * the local file is deleted when the server says it keeps the audio and the settings do not ask
     * for a copy on the phone. Anything else keeps the file.
     */
    fun confirmEnded(recordingId: String, answer: Answer): Unit = synchronized(lock) {
        val item = byId[recordingId] ?: return
        val jobId = (item.state as? State.Submitted)?.jobId ?: return
        byId[recordingId] = when (answer) {
            is Answer.Http if answer.status in 200..299 -> {
                val job = runCatching { AkouJson.decodeFromString(Job.serializer(), answer.body.decodeToString()) }.getOrNull()
                if (job == null || job.id != jobId) {
                    retryLater(item, null, "HTTP ${answer.status} without the job")
                } else {
                    if (job.keepAudio && !keepLocalCopy()) audioFile(item).delete()
                    item.copy(state = State.Done(jobId, job.keepAudio), attempts = 0, notBefore = null, lastError = null)
                }
            }
            is Answer.Http -> {
                val f = failure(answer)
                if (isRetryable(answer.status)) {
                    retryLater(item, f.retryAfter, f.text)
                } else {
                    // 404 or 410: the job went before it was read back (deleted elsewhere, or expired).
                    // The phone's file may be the only copy left, so it stays, and a person decides.
                    item.copy(state = State.Parked(answer.status, f.code), lastError = f.text, notBefore = null)
                }
            }
            is Answer.Unreachable -> retryLater(item, null, answer.why)
        }
        save()
    }

    /** Forgets [recordingId]'s item; its file, if any, stays where it is. */
    fun remove(recordingId: String): Unit = synchronized(lock) {
        if (byId.remove(recordingId) != null) save()
    }

    /**
     * After a restart: items left `uploading` that no live transfer carries go back to `pending`.
     * The background driver passes the recordings its running work still carries.
     */
    fun requeueInterrupted(except: Set<String> = emptySet()): Unit = synchronized(lock) {
        var changed = false
        for ((id, item) in byId.entries.toList()) {
            if (item.state == State.Uploading && id !in except) {
                byId[id] = item.copy(state = State.Pending)
                changed = true
            }
        }
        if (changed) save()
    }

    /**
     * Parked items go back to `pending`, for example after the key or the URL changed. A retry sends
     * the same `Idempotency-Key`, so a recording the server already has gets its first job. An item
     * whose server was removed stays parked: only [reassign] moves it.
     */
    fun retryParked(): Unit = synchronized(lock) {
        var changed = false
        for ((id, item) in byId.entries.toList()) {
            if (item.state !is State.Parked || item.waitsForAServer) continue
            byId[id] = item.copy(state = State.Pending, attempts = 0, notBefore = null)
            changed = true
        }
        if (changed) save()
    }

    // Driving it in the foreground

    /** Runs one request; never throws for a network failure, which is [Answer.Unreachable]. */
    fun interface Transport {
        suspend fun send(request: Request): Answer
    }

    /** A [Transport] over OkHttp, the akou client rules included. */
    class OkHttpTransport(private val client: OkHttpClient = AkouHttp.shared) : Transport {
        override suspend fun send(request: Request): Answer = try {
            val a = client.fetch(request)
            Answer.Http(a.status, a.headers, a.body)
        } catch (e: IOException) {
            Answer.Unreachable(e.message ?: e.javaClass.simpleName)
        }
    }

    /** One pass over the queue with one server: every item goes to [client]. */
    suspend fun drain(client: JobsClient, transport: Transport = OkHttpTransport()) =
        drain(null, { client }, transport)

    /** One pass with several servers: each item goes to its own server's client, an item with none to [recordTo]'s. */
    suspend fun drain(clients: Map<UUID, JobsClient>, recordTo: UUID?, transport: Transport = OkHttpTransport()) =
        drain(Routing(clients.keys, recordTo), { id -> id?.let(clients::get) }, transport)

    /** Uploads every due item, then reads back every submitted one. */
    private suspend fun drain(routing: Routing?, client: (UUID?) -> JobsClient?, transport: Transport) {
        for (item in claimDue(routing)) {
            val audio = audioFile(item)
            val c = client(item.serverId)
            if (c == null || !audio.isFile) {
                uploadCouldNotStart(item.recordingId, if (c == null) "no server for this recording" else "the recording's file is missing")
                continue
            }
            uploadEnded(item.recordingId, transport.send(c.submitRequest(item.submission, audio)))
        }
        for (due in dueForConfirm(routing)) {
            val c = client(due.serverId) ?: continue
            confirmEnded(due.recordingId, transport.send(c.jobRequest(due.jobId)))
        }
    }

    // Helpers

    private fun isDue(item: Item, t: Instant) = item.notBefore?.let { !it.isAfter(t) } ?: true

    /** Writes "Record to" into every item that names no server, in any state. True when one changed; the caller saves. */
    private fun stampLegacy(routing: Routing): Boolean {
        val recordTo = routing.recordTo ?: return false
        var changed = false
        for ((id, item) in byId.entries.toList()) {
            if (item.serverId == null) {
                byId[id] = item.copy(serverId = recordTo)
                changed = true
            }
        }
        return changed
    }

    private fun parkedForRemovedServer(item: Item) =
        item.copy(state = State.Parked(null, SERVER_REMOVED_CODE), lastError = SERVER_REMOVED_TEXT, notBefore = null)

    private fun retryLater(item: Item, retryAfter: Double?, error: String): Item {
        val attempts = item.attempts + 1
        val waitMs = retryAfter?.let { (it * 1000).toLong() } ?: (backoffSeconds(attempts) * 1000)
        return item.copy(attempts = attempts, notBefore = now().plusMillis(waitMs), lastError = error)
    }

    private class FailureInfo(val code: String?, val retryAfter: Double?, val text: String)

    private fun failure(a: Answer.Http): FailureInfo {
        val parsed = runCatching { AkouJson.decodeFromString(AkouErrorBody.serializer(), a.body.decodeToString()) }.getOrNull()
        val retryAfter = JobsClient.retryAfter(a.headers) ?: parsed?.retryAfterS?.toDouble()
        val text = listOfNotNull("HTTP ${a.status}", parsed?.error, parsed?.message).joinToString(": ")
        return FailureInfo(parsed?.error, retryAfter, text)
    }

    private fun save() {
        store.write(AkouJson.encodeToString(ITEMS, byId.values.toList()).encodeToByteArray())
    }

    companion object {
        /** The first retry waits this long, doubling per failure up to [MAX_BACKOFF_S]. */
        const val FIRST_BACKOFF_S = 5L
        const val MAX_BACKOFF_S = 15 * 60L

        /** The `code` of an item parked because its server was removed. */
        const val SERVER_REMOVED_CODE = "server_removed"
        internal const val SERVER_REMOVED_TEXT = "The server this recording was made for was removed. Send it to another server."

        /** The `code` of an item parked because its recording could not be sent from the phone. */
        const val LOCAL_FILE_CODE = "local_file"

        private val ITEMS = ListSerializer(Item.serializer())

        /** The wait after [attempts] failures in a row: 5, 10, 20 ... seconds, at most 15 minutes. */
        fun backoffSeconds(attempts: Int): Long {
            var s = FIRST_BACKOFF_S
            repeat((attempts - 1).coerceIn(0, 30)) { s = minOf(MAX_BACKOFF_S, s * 2) }
            return s
        }

        /** 408, 429 and 5xx (and no failure status at all) are worth repeating; any other 4xx is not. */
        fun isRetryable(status: Int): Boolean = status == 408 || status == 429 || status >= 500 || status < 400
    }
}

/** An instant as epoch milliseconds in the queue's file. */
internal object InstantAsMillis : KSerializer<Instant> {
    override val descriptor = PrimitiveSerialDescriptor("java.time.Instant", PrimitiveKind.LONG)

    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeLong(value.toEpochMilli())

    override fun deserialize(decoder: Decoder): Instant = Instant.ofEpochMilli(decoder.decodeLong())
}
