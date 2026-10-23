// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou.core.client

import io.github.geiserx.akou.core.protocol.LiveToken
import io.github.geiserx.akou.core.protocol.LiveWords

/**
 * The live text of one recording, built from the tokens of every live session and the gaps between
 * them. A token whose text starts with whitespace starts a new word; any other token continues the
 * word before it. A line ends after sentence punctuation or at a silence of 1.5 s or more between
 * tokens, and a gap always ends the line before it.
 *
 * It looks only at the first and last code point of a token, so it needs no grapheme counting.
 * Whitespace is Unicode's White_Space property, which is what Swift's `Character.isWhitespace`
 * uses and what `Char.isWhitespace` on the JVM is not.
 */
class LiveTranscript {
    sealed interface Item

    data class Line(
        val text: String,
        /** Seconds into the recording of the line's first and last token. */
        val start: Double,
        val end: Double,
    ) : Item

    /** Live text paused between these two points of the recording; the final transcript fills it. */
    data class Gap(val from: Double, val to: Double) : Item

    /** Closed lines and gaps, oldest first. */
    val items: List<Item> get() = _items.toList()
    private val _items = ArrayList<Item>()

    /** The line still growing. */
    var openLine: Line? = null
        private set

    /** The newest closed line: what the notification shows. */
    var lastClosedLine: String? = null
        private set

    private var closeAtNextWord = false

    fun append(words: LiveWords) {
        for (token in words.tokens) append(token)
    }

    fun append(token: LiveToken) {
        val text = token.text
        if (text.isEmpty()) return
        val startsWord = isWhiteSpace(text.codePointAt(0))
        val line = openLine
        if (line != null && startsWord && (closeAtNextWord || token.t - line.end >= LINE_BREAK_SILENCE)) close()
        val current = openLine
        if (current != null) {
            openLine = current.copy(text = current.text + text, end = token.t)
        } else {
            val trimmed = dropLeadingWhiteSpace(text)
            if (trimmed.isEmpty()) return
            openLine = Line(trimmed, token.t, token.t)
        }
        // A full stop ends the line only once the next word starts, so "3." then "14" stays one word.
        closeAtNextWord = text.codePointBefore(text.length) in SENTENCE_END
    }

    /** Marks live text as paused from [from] to [to] (seconds into the recording). */
    fun gap(from: Double, to: Double) {
        close()
        _items += Gap(from, to)
    }

    /** Ends the open line, for example when the recording stops. */
    fun close() {
        closeAtNextWord = false
        val line = openLine ?: return
        openLine = null
        _items += line
        lastClosedLine = line.text
    }

    companion object {
        /** The silence between two tokens that ends a line, in seconds. */
        const val LINE_BREAK_SILENCE = 1.5

        /** What the view says for a gap. */
        fun gapLabel(from: Double, to: Double): String =
            "live text paused, ${clock(from)} to ${clock(to)}, will come with the final transcript"

        /** `m:ss`, or `h:mm:ss` from one hour. */
        fun clock(seconds: Double): String {
            val s = maxOf(0, seconds.toInt())
            val h = s / 3600
            val m = s / 60 % 60
            val sec = s % 60
            // Padded by hand: String.format would use the device locale's digits.
            fun two(n: Int) = n.toString().padStart(2, '0')
            return if (h > 0) "$h:${two(m)}:${two(sec)}" else "$m:${two(sec)}"
        }

        private val SENTENCE_END = setOf('.'.code, '?'.code, '!'.code, 0x2026, 0x3002, 0xFF1F, 0xFF01)

        /** Unicode's White_Space property (PropList.txt), the set Swift's `isWhitespace` tests. */
        internal fun isWhiteSpace(cp: Int): Boolean =
            cp in 0x09..0x0D || cp == 0x20 || cp == 0x85 || cp == 0xA0 || cp == 0x1680 ||
                cp in 0x2000..0x200A || cp == 0x2028 || cp == 0x2029 || cp == 0x202F || cp == 0x205F || cp == 0x3000

        private fun dropLeadingWhiteSpace(text: String): String {
            var i = 0
            while (i < text.length) {
                val cp = text.codePointAt(i)
                if (!isWhiteSpace(cp)) break
                i += Character.charCount(cp)
            }
            return text.substring(i)
        }
    }
}
