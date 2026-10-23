// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou.core.client

import io.github.geiserx.akou.core.protocol.JobWord
import java.text.BreakIterator

/**
 * The opening of a transcript in a few whole sentences, for the recent-recordings widget, the
 * "last recording" shortcut's fallback summary, and the snapshot the app keeps for both. Pure: no
 * key, no network, no model.
 *
 * Every length here is in grapheme clusters, what Swift's `String.count` gives, so a family emoji,
 * a flag or a skin-tone emoji is one character and is never cut in half. `String.length` counts
 * UTF-16 units and would cut those where iOS does not.
 */
object TranscriptDigest {
    /** The result's `text` when it has any, else its words joined by spaces, cut by [opening]. */
    fun opening(text: String?, words: List<JobWord>, limit: Int = 600): String {
        if (text != null && collapse(text).isNotEmpty()) return opening(text, limit)
        return opening(words.joinToString(" ") { it.w }, limit)
    }

    /**
     * The longest run of whole opening sentences that fits in `limit` characters, whitespace
     * collapsed. When even the first sentence is longer, it is cut at the last word that fits and
     * ends in "…". The answer is never longer than `limit`.
     */
    fun opening(text: String, limit: Int = 600): String {
        val flat = collapse(text)
        if (limit <= 0 || flat.isEmpty()) return ""
        val chars = graphemes(flat)
        if (chars.size <= limit) return flat

        var best: Int? = null
        for (end in sentenceEnds(chars)) {
            if (end > limit) break
            best = end
        }
        if (best != null) return chars.subList(0, best).joinToString("")

        // No whole sentence fits: cut at a word boundary, leaving room for the ellipsis.
        val room = limit - 1
        val space = chars.subList(0, minOf(room + 1, chars.size)).lastIndexOf(" ")
        if (space in 0..room) {
            val words = chars.subList(0, space).joinToString("").trim()
            if (words.isNotEmpty()) return "$words…"
        }
        return chars.subList(0, room).joinToString("") + "…"
    }

    /**
     * Runs of whitespace become one space; the ends are trimmed. Whitespace is Unicode's
     * White_Space, as Swift's `isWhitespace`, not the JVM's `Char.isWhitespace`.
     */
    internal fun collapse(text: String): String = buildString {
        var gap = false
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (LiveTranscript.isWhiteSpace(cp)) {
                gap = isNotEmpty()
            } else {
                if (gap) append(' ')
                gap = false
                appendCodePoint(cp)
            }
        }
    }

    /** `text` split into grapheme clusters, the unit Swift's `Character` is. */
    internal fun graphemes(text: String): List<String> {
        val it = BreakIterator.getCharacterInstance()
        it.setText(text)
        val out = ArrayList<String>(text.length)
        var start = it.first()
        var end = it.next()
        while (end != BreakIterator.DONE) {
            out.add(text.substring(start, end))
            start = end
            end = it.next()
        }
        return out
    }

    private val latinEnds = setOf(".", "!", "?", "…")
    private val wideEnds = setOf("。", "！", "？")
    private val closers = setOf("\"", "'", "”", "’", ")", "]", "»")

    /**
     * The index just past each sentence end, counted in [graphemes]: a run of `.`, `!`, `?` or `…`
     * (with any closing quote or bracket) followed by a space or the end, or a full-width `。！？`
     * anywhere.
     */
    internal fun sentenceEnds(chars: List<String>): List<Int> {
        val ends = mutableListOf<Int>()
        var i = 0
        while (i < chars.size) {
            val c = chars[i]
            val isLatin = c in latinEnds
            if (!isLatin && c !in wideEnds) {
                i++
                continue
            }
            var j = i + 1
            while (j < chars.size && (chars[j] in latinEnds || chars[j] in wideEnds || chars[j] in closers)) j++
            if (!isLatin || j == chars.size || chars[j] == " ") ends.add(j)
            i = j
        }
        if (ends.lastOrNull() != chars.size) ends.add(chars.size)
        return ends
    }
}

