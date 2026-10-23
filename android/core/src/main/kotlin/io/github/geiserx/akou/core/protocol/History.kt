// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou.core.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// What akou's history routes answer, as far as the phone reads them: the calls, the dictations and
// their audio, reachable by a key with the `read` or `admin` scope on the desktop app (calls and
// dictations exist only in akou's app mode). Every other field is ignored, so a newer akou never
// breaks an older phone. Times akou writes as numbers are epoch milliseconds.
//
// Two levels of leniency, as on iOS: CallLine and CallNote never fail, whatever a field holds; the
// other models fail on a wrong required field, except the fields marked lenient.

/** One row of `GET /v1/calls`. */
@Serializable
data class CallSummary(
    val id: String,
    val title: String = "",
    val workspace: String = "",
    /** Epoch ms of `call.created`. */
    val createdAt: Double = 0.0,
    /** `recording`, `paused`, `ended`, `interrupted`, `failed`...; kept as text so a new state never fails decoding. */
    val state: String = "",
    /** Epoch ms; null while the call is live. */
    val endedAt: Double? = null,
    /** How many parts (audio files) the call has. */
    val parts: Int = 0,
    val updatedAt: Double? = null,
) {
    /** True while akou is still recording it. */
    val isLive: Boolean
        get() = endedAt == null && (state == "recording" || state == "paused" || state == "starting" || state == "restarting")
}

/** `GET /v1/calls`: newest first. */
@Serializable
data class CallList(val calls: List<CallSummary>)

/** `GET /v1/calls/{id}`. */
@Serializable
data class CallDetail(
    val id: String,
    val title: String = "",
    val workspace: String = "",
    val state: String = "",
    val live: Boolean = false,
    val tz: String? = null,
    val createdAt: Double? = null,
    val startedAt: Double? = null,
    val endedAt: Double? = null,
    val parts: List<Part> = emptyList(),
    @Serializable(with = LenientCallFinal::class) val final: Final? = null,
) {
    @Serializable
    data class Part(
        /** From 1; `GET /v1/calls/{id}/audio/{part}` serves its audio. */
        val part: Int,
        /**
         * Epoch ms the part's audio starts at: a transcript line at `w0` is `(w0 - wallStart) / 1000`
         * seconds into this part's file.
         */
        val wallStart: Double? = null,
        val ended: Ended? = null,
    ) {
        @Serializable
        data class Ended(val reason: String? = null, val fileSeconds: Double? = null)
    }

    @Serializable
    data class Final(
        /** The final pass: `none`, `queued`, `running`, `done`, `failed`... */
        val state: String? = null,
    )

    /** Seconds of audio across the parts that ended; null while none says. */
    val seconds: Double?
        get() {
            val known = parts.mapNotNull { it.ended?.fileSeconds }
            return if (known.isEmpty()) null else known.sum()
        }
}

/** `GET /v1/calls/{id}/transcript?format=json`. */
@Serializable
data class CallTranscript(
    val call: String,
    val tz: String? = null,
    /** "Times are local, Europe/Madrid (GMT+2)." */
    val zone: String? = null,
    val lines: List<CallLine>,
)

/** One transcript line: who said what, when. One odd line (a field missing, null or of another type) never costs the whole transcript. */
@Serializable(with = CallLineSerializer::class)
data class CallLine(
    val id: String,
    /** The line's local wall-clock time in the call's zone, `15:41:03`. */
    val time: String,
    /** Epoch ms the line starts and ends. */
    val w0: Double,
    val w1: Double? = null,
    /**
     * Seconds into the line's part file where it starts and ends. They differ from the wall-clock
     * offset once the call paused (the file does not advance during a pause). Absent from an akou
     * older than these fields.
     */
    val a0: Double? = null,
    val a1: Double? = null,
    val part: Int,
    /** `mic` or `call`. */
    val ch: String? = null,
    /** The speaker's id (`you`, `c2`) and the name akou shows for it. */
    val spk: String? = null,
    val speaker: String,
    val text: String,
) {
    /**
     * Where the line starts in its part's audio, in seconds: `a0` when akou sends it, else the
     * wall-clock offset from the part's start, which is right only for a part that never paused.
     * Null when neither is known.
     */
    fun seconds(intoPartStartingAt: Double?): Double? {
        a0?.let { return maxOf(0.0, it) }
        val wallStart = intoPartStartingAt ?: return null
        return maxOf(0.0, (w0 - wallStart) / 1000)
    }
}

/** `GET /v1/calls/{id}/enhanced`: the summary, when there is one. */
@Serializable
data class CallEnhanced(
    val call: String,
    /** Null when the call has no enhanced notes yet. */
    val enhanced: Notes? = null,
) {
    @Serializable
    data class Notes(val rev: Int, val template: String? = null, val markdown: String)
}

/** `GET /v1/calls/{id}/notes`. */
@Serializable
data class CallNotes(val call: String, val notes: List<CallNote>)

/** One notepad line. Never fails to decode, like [CallLine]. */
@Serializable(with = CallNoteSerializer::class)
data class CallNote(
    val id: String,
    val text: String,
    /** Epoch ms the line was begun. */
    val w: Double,
    /** `human` or `agent`. */
    val author: String? = null,
)

/** One dictation. Strict, except `words` and `error`. */
@Serializable
data class Dictation(
    val id: String,
    /** Epoch ms the dictation started. */
    val at: Double = 0.0,
    /** `inserted`, `drafted`, `done`, `empty`, `failed`... */
    val state: String = "",
    /** The app it went to, by the name people know it, when the OS gave one. */
    @SerialName("app_name") val appName: String? = null,
    val seconds: Double? = null,
    /** What was inserted (after the tidy, if any) and what the engine heard. */
    val text: String? = null,
    val raw: String? = null,
    val language: String? = null,
    @Serializable(with = LenientJobWords::class) val words: List<JobWord> = emptyList(),
    val engine: String? = null,
    val model: String? = null,
    @Serializable(with = LenientString::class) val error: String? = null,
)

/** `GET /v1/dictations`: newest first; `next_cursor` is null on the last page. */
@Serializable
data class DictationPage(
    val items: List<Dictation>,
    @SerialName("next_cursor") val nextCursor: String? = null,
)

// The two models that never fail read their fields one by one from the JSON object.

private fun Decoder.jsonObject(what: String): JsonObject {
    val input = this as? JsonDecoder ?: throw SerializationException("$what is JSON only")
    return input.decodeJsonElement() as? JsonObject ?: throw SerializationException("$what is a JSON object")
}

private fun Encoder.encodeObject(what: String, build: JsonObjectBuilder.() -> Unit) {
    val out = this as? JsonEncoder ?: throw SerializationException("$what is JSON only")
    out.encodeJsonElement(buildJsonObject(build))
}

internal object CallLineSerializer : KSerializer<CallLine> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("io.github.geiserx.akou.core.protocol.CallLine")

    override fun deserialize(decoder: Decoder): CallLine {
        val o = decoder.jsonObject("a transcript line")
        val w0 = o.doubleOrNull("w0") ?: 0.0
        val spk = o.stringOrNull("spk")
        return CallLine(
            id = o.stringOrNull("id") ?: "w${w0.toLong()}",
            time = o.stringOrNull("time") ?: "",
            w0 = w0,
            w1 = o.doubleOrNull("w1"),
            a0 = o.doubleOrNull("a0"),
            a1 = o.doubleOrNull("a1"),
            part = o.intOrNull("part") ?: 1,
            ch = o.stringOrNull("ch"),
            spk = spk,
            speaker = o.stringOrNull("speaker") ?: spk ?: "",
            text = o.stringOrNull("text") ?: "",
        )
    }

    override fun serialize(encoder: Encoder, value: CallLine) = encoder.encodeObject("a transcript line") {
        put("id", value.id)
        put("time", value.time)
        put("w0", value.w0)
        value.w1?.let { put("w1", it) }
        value.a0?.let { put("a0", it) }
        value.a1?.let { put("a1", it) }
        put("part", value.part)
        value.ch?.let { put("ch", it) }
        value.spk?.let { put("spk", it) }
        put("speaker", value.speaker)
        put("text", value.text)
    }
}

internal object CallNoteSerializer : KSerializer<CallNote> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("io.github.geiserx.akou.core.protocol.CallNote")

    override fun deserialize(decoder: Decoder): CallNote {
        val o = decoder.jsonObject("a note")
        val w = o.doubleOrNull("w") ?: 0.0
        return CallNote(
            id = o.stringOrNull("id") ?: "n${w.toLong()}",
            text = o.stringOrNull("text") ?: "",
            w = w,
            author = o.stringOrNull("author"),
        )
    }

    override fun serialize(encoder: Encoder, value: CallNote) = encoder.encodeObject("a note") {
        put("id", value.id)
        put("text", value.text)
        put("w", value.w)
        value.author?.let { put("author", it) }
    }
}
