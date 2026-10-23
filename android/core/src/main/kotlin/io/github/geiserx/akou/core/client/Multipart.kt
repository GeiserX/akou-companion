// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou.core.client

import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import java.io.File
import java.util.UUID

/**
 * A `multipart/form-data` body with the same bytes iOS sends: the text fields, then the file. The
 * file is streamed from disk on every write, so an hour-long recording is never held in memory and
 * a retried request sends it again from the start.
 *
 * OkHttp's own `MultipartBody` adds a `Content-Length` header to every part; this writes exactly
 * the part headers iOS writes, so the server sees one upload format from both apps.
 */
class Multipart(
    private val fields: List<Pair<String, String>>,
    private val file: FilePart,
    val boundary: String = "akou-${UUID.randomUUID().toString().uppercase()}",
) : RequestBody() {
    data class FilePart(val name: String, val fileName: String, val contentType: String, val file: File)

    private val head: ByteArray = buildString {
        for ((name, value) in fields) {
            append("--$boundary\r\nContent-Disposition: form-data; name=\"${quoted(name)}\"\r\n\r\n$value\r\n")
        }
        append("--$boundary\r\nContent-Disposition: form-data; name=\"${quoted(file.name)}\"; filename=\"${quoted(file.fileName)}\"\r\n")
        append("Content-Type: ${file.contentType}\r\n\r\n")
    }.encodeToByteArray()

    private val tail: ByteArray = "\r\n--$boundary--\r\n".encodeToByteArray()

    override fun contentType(): MediaType = "multipart/form-data; boundary=$boundary".toMediaType()

    override fun contentLength(): Long = head.size + file.file.length() + tail.size

    override fun writeTo(sink: BufferedSink) {
        sink.write(head)
        file.file.source().use { sink.writeAll(it) }
        sink.write(tail)
    }

    companion object {
        /** A header parameter value cannot hold a quote or a line break; the names are ours, so dropping them is enough. */
        internal fun quoted(s: String): String = s.filter { it != '"' && it != '\r' && it != '\n' }
    }
}
