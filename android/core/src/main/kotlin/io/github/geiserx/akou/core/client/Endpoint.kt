// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou.core.client

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Where the phone may send audio and the key. `https` always passes. Plain `http` passes only for
 * an IP address that is loopback, RFC 1918, unique-local (fc00::/7) or shared (100.64.0.0/10,
 * which Tailscale uses), the same rule akou's own remote dictation applies. A host name over plain
 * `http` is refused, `localhost` included: the phone cannot pin what the name resolves to.
 *
 * The address parsers read literals only. They never call `InetAddress.getByName`, which would
 * resolve a name.
 */
object Endpoint {
    sealed class Failure(message: String) : Exception(message) {
        data class UnsupportedScheme(val scheme: String?) : Failure("unsupported scheme: $scheme")

        /** No host, or a URL OkHttp cannot read at all. */
        data object MissingHost : Failure("the server URL has no host")

        data class CleartextToPublicHost(val host: String) : Failure("plain http to $host is refused")
    }

    /** The URL of an API path under the server's base URL, for example `/v1/server`. */
    fun api(base: String, path: String): HttpUrl {
        val url = check(base)
        return join(url, path)
    }

    /** The live WebSocket URL: `wss://` for an `https` base, `ws://` for an allowed `http` base. */
    fun live(base: String): String {
        val url = api(base, "/v1/live").toString()
        return if (url.startsWith("https://")) "wss://" + url.removePrefix("https://") else "ws://" + url.removePrefix("http://")
    }

    /**
     * A value, such as a job or call id, as exactly one percent-encoded path segment. Everything but
     * ASCII letters, digits and `-._~` is escaped, so a `/`, `?`, `#`, `%` or space in an id can
     * never add a segment, start a query or a fragment. An empty id, `.` or `..` becomes `_`, so it
     * never names the parent route either.
     */
    fun segment(value: String): String {
        if (value.isEmpty() || value == "." || value == "..") return "_"
        return percentEncode(value) { it in UNRESERVED }
    }

    internal fun check(base: String): HttpUrl {
        val scheme = SCHEME.find(base)?.groupValues?.get(1)
        val lower = scheme?.lowercase()
        if (lower != "https" && lower != "http") throw Failure.UnsupportedScheme(scheme)
        val url = base.toHttpUrlOrNull() ?: throw Failure.MissingHost
        if (url.host.isEmpty()) throw Failure.MissingHost
        if (lower == "http" && !cleartextAllowed(url.host)) throw Failure.CleartextToPublicHost(url.host)
        return url
    }

    /** The base URL with a percent-encoded API path appended; query and fragment dropped. */
    internal fun join(base: HttpUrl, path: String): HttpUrl {
        val basePath = base.encodedPath
        val prefix = if (basePath.endsWith("/")) basePath.dropLast(1) else basePath
        val p = encodedPath(path).let { if (it.startsWith("/")) it else "/$it" }
        return base.newBuilder().encodedPath(prefix + p).query(null).fragment(null).build()
    }

    /**
     * A path as it goes on the wire. Paths built from literals and [segment] pass as they are.
     * Anything else (a stray space, a `%` that starts no escape) is taken as plain text and encoded.
     */
    internal fun encodedPath(path: String): String {
        val valid = path.all { it in PATH_ALLOWED || it == '%' } && validEscapes(path)
        return if (valid) path else percentEncode(path) { it in PATH_ALLOWED }
    }

    /** True for an IP literal in the ranges above. */
    fun cleartextAllowed(host: String): Boolean {
        val h = if (host.length > 1 && host.startsWith('[') && host.endsWith(']')) host.substring(1, host.length - 1) else host
        ipv4(h)?.let { v4 ->
            val a = v4[0]
            val b = v4[1]
            return a == 127 || a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168) ||
                (a == 100 && b in 64..127)
        }
        ipv6(h)?.let { v6 ->
            if ((0 until 15).all { v6[it] == 0 } && v6[15] == 1) return true // ::1
            return v6[0] and 0xFE == 0xFC // fc00::/7
        }
        return false
    }

    /** Four decimal parts, each 0 to 255, no leading zeros; null for anything else. */
    internal fun ipv4(s: String): IntArray? {
        val parts = s.split('.')
        if (parts.size != 4) return null
        val out = IntArray(4)
        for ((i, p) in parts.withIndex()) {
            if (p.isEmpty() || p.length > 3 || !p.all { it in '0'..'9' }) return null
            if (p.length > 1 && p[0] == '0') return null
            val n = p.toInt()
            if (n > 255) return null
            out[i] = n
        }
        return out
    }

    /** The 16 bytes of an IPv6 literal (with `::` and an optional dotted IPv4 tail); null otherwise. */
    internal fun ipv6(s: String): IntArray? {
        if (s.isEmpty()) return null
        val halves = s.split("::")
        if (halves.size > 2) return null
        val head = groupsOf(halves[0]) ?: return null
        val tail = if (halves.size == 2) groupsOf(halves[1]) ?: return null else emptyList()
        // A dotted IPv4 part may only be the last group of the address.
        val headBytes = bytesOf(head, v4Last = halves.size == 1) ?: return null
        val tailBytes = bytesOf(tail, v4Last = true) ?: return null
        if (halves.size == 1) return headBytes.takeIf { it.size == 16 }?.toIntArray()
        val n = headBytes.size + tailBytes.size
        if (n > 14) return null // "::" stands for at least one zero group
        return (headBytes + List(16 - n) { 0 } + tailBytes).toIntArray()
    }

    private fun groupsOf(part: String): List<String>? =
        if (part.isEmpty()) emptyList() else part.split(':').takeIf { g -> g.none { it.isEmpty() } }

    private fun bytesOf(groups: List<String>, v4Last: Boolean): List<Int>? {
        val out = ArrayList<Int>()
        for ((i, g) in groups.withIndex()) {
            if ('.' in g) {
                if (!v4Last || i != groups.lastIndex) return null
                out += ipv4(g)?.toList() ?: return null
            } else {
                if (g.length > 4 || !g.all(::isHex)) return null
                val n = g.toInt(16)
                out += n shr 8
                out += n and 0xFF
            }
        }
        return out
    }

    private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")
    private const val ALNUM = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    private const val UNRESERVED = "$ALNUM-._~"

    /** Foundation's `urlPathAllowed`: what a path may hold unescaped. */
    private const val PATH_ALLOWED = "$UNRESERVED!$&'()*+,;=:@/"

    private fun validEscapes(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            if (s[i] == '%') {
                if (i + 2 > s.lastIndex) return false
                if (!isHex(s[i + 1]) || !isHex(s[i + 2])) return false
                i += 3
            } else {
                i++
            }
        }
        return true
    }

    private fun isHex(c: Char) = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    private inline fun percentEncode(s: String, keep: (Char) -> Boolean): String {
        val out = StringBuilder()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = (b.toInt() and 0xFF).toChar()
            if (c.code < 0x80 && keep(c)) out.append(c) else out.append('%').append(HEX[c.code shr 4]).append(HEX[c.code and 0xF])
        }
        return out.toString()
    }

    private const val HEX = "0123456789ABCDEF"
}
