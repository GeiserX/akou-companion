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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

// The control messages of akou's `GET /v1/live` WebSocket (docs/PROTOCOL.md). Text frames carry
// one JSON object with a `type`; binary frames carry audio and are not modelled here.

/** How the binary frames are encoded. */
@Serializable
enum class LiveCodec {
    /** One Ogg Opus page per binary frame, OpusHead and OpusTags pages first. What the phone sends. */
    @SerialName("ogg-opus")
    OGG_OPUS,

    /** Raw 16 kHz 16-bit little-endian mono samples. For test clients and measurements. */
    @SerialName("pcm16")
    PCM16,
}

/** The first message on a new socket. The server refuses any other field, and `v` other than 1. */
@Serializable
data class LiveHello(
    val v: Int = 1,
    val codec: LiveCodec = LiveCodec.OGG_OPUS,
    /** `auto` or a BCP 47 code; bounds which live engine `auto` picks. */
    val language: String = "auto",
    /** `auto` or a live engine id the server reported in `GET /v1/server`. */
    val model: String = "auto",
)

/** Messages the phone sends as text frames. */
@Serializable(with = LiveClientMessageSerializer::class)
sealed class LiveClientMessage {
    data class Hello(val hello: LiveHello) : LiveClientMessage()

    /** The recording stopped: the server sends the last words, then `closed`, and closes. */
    data object Stop : LiveClientMessage()

    /** The text frame for this message. */
    fun json(): String = AkouJson.encodeToString(serializer(), this)
}

/** The server's answer once the live stream is open. */
@Serializable
data class LiveReady(
    /** The live engine that serves this session, for example `nemotron-3.5-560`. */
    val engine: String,
    val lang: String? = null,
    /** The engine's chunk in milliseconds: how far behind the audio a word can come. */
    @SerialName("tier_ms") val tierMs: Int? = null,
    /** How long the engine took to load for this session, in milliseconds. */
    @SerialName("load_ms") val loadMs: Int? = null,
)

/** One recognised token. A token whose text starts with a space starts a new word. */
@Serializable
data class LiveToken(
    val text: String,
    /** Seconds into the recording (the file's timeline), computed by the server from the pages' granules. */
    val t: Double,
    val conf: Double? = null,
)

/** Append-only tokens: the server never takes one back. The end of a session is `closed`, not a flag on the last `words`. */
@Serializable
data class LiveWords(val tokens: List<LiveToken>)

/** An error the server reports before it closes the socket with the matching close code. */
@Serializable
data class LiveError(val code: String, val message: String? = null)

/** Messages the server sends as text frames. */
@Serializable(with = LiveServerMessageSerializer::class)
sealed class LiveServerMessage {
    data class Ready(val ready: LiveReady) : LiveServerMessage()
    data class Words(val words: LiveWords) : LiveServerMessage()

    /** The session ended after `stop`; the server closes the socket next. */
    data object Closed : LiveServerMessage()
    data class Error(val error: LiveError) : LiveServerMessage()

    /** A message type this client does not know yet; ignored rather than treated as an error. */
    data class Unknown(val type: String) : LiveServerMessage()

    companion object {
        /** Parses one text frame from the server. Throws only for text that is not an object with a string `type`. */
        fun parse(text: String): LiveServerMessage = AkouJson.decodeFromString(serializer(), text)
    }
}

/** WebSocket close codes the live route uses besides the standard ones. */
enum class LiveCloseCode(val code: Int) {
    /**
     * A message or frame the server refused: `bad_message`, `bad_page`, `too_fast`,
     * `unknown_model` or `unsupported_language`. The `error` frame says which.
     */
    BAD_PAGE(4400),

    /** The key was revoked while the socket was open. */
    KEY_REVOKED(4401),

    /** Another open session uses a different live engine; the server loads one at a time. */
    ENGINE_BUSY(4409),

    /** The live engine stopped while the session ran. */
    STREAM_LOST(4500),

    /** No live engine is on disk, or live text is off on this server. */
    NO_LIVE_ENGINE(4503),
    ;

    companion object {
        fun of(code: Int): LiveCloseCode? = entries.firstOrNull { it.code == code }
    }
}

// JSON: one object per frame, `type` beside the payload's own fields.

private fun JsonDecoder.typedObject(): Pair<String, JsonObject> {
    val obj = decodeJsonElement() as? JsonObject ?: throw SerializationException("a live message is a JSON object")
    val type = obj.stringOrNull("type") ?: throw SerializationException("a live message has a string type")
    return type to obj
}

private fun <T> JsonEncoder.encodeTyped(type: String, payload: KSerializer<T>? = null, value: T? = null) {
    encodeJsonElement(
        buildJsonObject {
            put("type", JsonPrimitive(type))
            if (payload != null && value != null) {
                AkouJson.encodeToJsonElement(payload, value).jsonObject.forEach { (k, v) -> put(k, v) }
            }
        },
    )
}

private fun Decoder.asJson(): JsonDecoder = this as? JsonDecoder ?: throw SerializationException("live messages are JSON only")
private fun Encoder.asJson(): JsonEncoder = this as? JsonEncoder ?: throw SerializationException("live messages are JSON only")

internal object LiveClientMessageSerializer : KSerializer<LiveClientMessage> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("io.github.geiserx.akou.core.protocol.LiveClientMessage")

    override fun deserialize(decoder: Decoder): LiveClientMessage {
        val (type, obj) = decoder.asJson().typedObject()
        return when (type) {
            "hello" -> LiveClientMessage.Hello(AkouJson.decodeFromJsonElement(LiveHello.serializer(), obj))
            "stop" -> LiveClientMessage.Stop
            else -> throw SerializationException("unknown client message type $type")
        }
    }

    override fun serialize(encoder: Encoder, value: LiveClientMessage) {
        val out = encoder.asJson()
        when (value) {
            is LiveClientMessage.Hello -> out.encodeTyped("hello", LiveHello.serializer(), value.hello)
            LiveClientMessage.Stop -> out.encodeTyped<Unit>("stop")
        }
    }
}

internal object LiveServerMessageSerializer : KSerializer<LiveServerMessage> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("io.github.geiserx.akou.core.protocol.LiveServerMessage")

    override fun deserialize(decoder: Decoder): LiveServerMessage {
        val (type, obj) = decoder.asJson().typedObject()
        return when (type) {
            "ready" -> LiveServerMessage.Ready(AkouJson.decodeFromJsonElement(LiveReady.serializer(), obj))
            "words" -> LiveServerMessage.Words(AkouJson.decodeFromJsonElement(LiveWords.serializer(), obj))
            "closed" -> LiveServerMessage.Closed
            "error" -> LiveServerMessage.Error(AkouJson.decodeFromJsonElement(LiveError.serializer(), obj))
            else -> LiveServerMessage.Unknown(type)
        }
    }

    override fun serialize(encoder: Encoder, value: LiveServerMessage) {
        val out = encoder.asJson()
        when (value) {
            is LiveServerMessage.Ready -> out.encodeTyped("ready", LiveReady.serializer(), value.ready)
            is LiveServerMessage.Words -> out.encodeTyped("words", LiveWords.serializer(), value.words)
            LiveServerMessage.Closed -> out.encodeTyped<Unit>("closed")
            is LiveServerMessage.Error -> out.encodeTyped("error", LiveError.serializer(), value.error)
            is LiveServerMessage.Unknown -> out.encodeTyped<Unit>(value.type)
        }
    }
}
