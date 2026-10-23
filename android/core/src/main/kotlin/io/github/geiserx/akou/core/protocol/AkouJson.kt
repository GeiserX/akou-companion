// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou.core.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * The one JSON configuration for everything the phone reads from or sends to akou.
 *
 * - `ignoreUnknownKeys`: a newer server's extra fields never break an older app.
 * - `encodeDefaults`: a hello built from defaults still carries `v`, `codec`, `language` and
 *   `model`; without them the server refuses it.
 * - `coerceInputValues`: an explicit null where the model has a default reads as the default, as
 *   Swift's `decodeIfPresent(...) ?? default` does (`keep_audio: null` is not kept).
 * - `explicitNulls = false`: a nil field is left out on encode, as Swift's synthesized `Encodable` does.
 */
val AkouJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    coerceInputValues = true
    explicitNulls = false
}

/** Decodes with [inner], and answers [fallback] instead of failing: Swift's `try?` on one field. */
internal open class OrFallback<T>(private val inner: KSerializer<T>, private val fallback: T) : KSerializer<T> {
    override val descriptor: SerialDescriptor = inner.descriptor

    override fun deserialize(decoder: Decoder): T {
        val input = decoder as? JsonDecoder ?: return inner.deserialize(decoder)
        val element = input.decodeJsonElement()
        return try {
            input.json.decodeFromJsonElement(inner, element)
        } catch (_: IllegalArgumentException) {
            fallback
        }
    }

    override fun serialize(encoder: Encoder, value: T) = inner.serialize(encoder, value)
}

internal object LenientString : OrFallback<String?>(String.serializer().nullable, null)
internal object LenientCompanionMetadata : OrFallback<CompanionMetadata?>(CompanionMetadata.serializer().nullable, null)
internal object LenientCallFinal : OrFallback<CallDetail.Final?>(CallDetail.Final.serializer().nullable, null)
internal object LenientJobWords : OrFallback<List<JobWord>>(ListSerializer(JobWord.serializer()), emptyList())

// Field readers for the models that never fail: a field of the wrong type reads as absent.

internal fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.doubleOrNull(key: String): Double? =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

internal fun JsonObject.intOrNull(key: String): Int? =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.let { p ->
        p.intOrNull ?: p.doubleOrNull?.takeIf { it % 1.0 == 0.0 && it >= Int.MIN_VALUE && it <= Int.MAX_VALUE }?.toInt()
    }
