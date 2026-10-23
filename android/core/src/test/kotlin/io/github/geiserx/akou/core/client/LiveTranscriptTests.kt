// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou.core.client

import io.github.geiserx.akou.core.protocol.LiveToken
import io.github.geiserx.akou.core.protocol.LiveWords
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Port of Tests/AkouKitTests/LiveTranscriptTests.swift, plus the code-point rules of the port. */
class LiveTranscriptTests {
    private fun tokens(vararg list: Pair<String, Double>) = LiveWords(list.map { LiveToken(it.first, it.second) })

    @Test
    fun testTokensJoinIntoWords() {
        val t = LiveTranscript()
        t.append(tokens(" Hel" to 0.1, "lo" to 0.2, " wor" to 0.4, "ld" to 0.5))
        assertEquals(LiveTranscript.Line("Hello world", 0.1, 0.5), t.openLine)
        assertEquals(emptyList(), t.items)
        assertNull(t.lastClosedLine)
    }

    @Test
    fun testSentencePunctuationEndsTheLineAtTheNextWord() {
        val t = LiveTranscript()
        t.append(tokens(" Hi" to 0.1, " there." to 0.3))
        assertNull(t.lastClosedLine, "the line closes when the next word starts, not on the full stop itself")
        t.append(tokens(" How" to 0.6, " are" to 0.7, " you?" to 0.9, " Fine" to 1.2))
        assertEquals(
            listOf(LiveTranscript.Line("Hi there.", 0.1, 0.3), LiveTranscript.Line("How are you?", 0.6, 0.9)),
            t.items,
        )
        assertEquals("How are you?", t.lastClosedLine)
        assertEquals("Fine", t.openLine?.text)
    }

    @Test
    fun testAFullStopInsideAWordDoesNotEndTheLine() {
        val t = LiveTranscript()
        t.append(tokens(" pi" to 0.1, " is" to 0.2, " 3." to 0.3, "14" to 0.4, " or" to 0.6, " so" to 0.7))
        assertEquals(emptyList(), t.items)
        assertEquals("pi is 3.14 or so", t.openLine?.text)
    }

    @Test
    fun testASilenceEndsTheLine() {
        val t = LiveTranscript()
        t.append(tokens(" one" to 0.0, " two" to 0.5, " three" to 1.75, " four" to 3.25, " five" to 3.5))
        assertEquals(
            listOf(LiveTranscript.Line("one two three", 0.0, 1.75)),
            t.items,
            "1.25 s between two and three keeps the line; 1.5 s between three and four ends it",
        )
        assertEquals("four five", t.openLine?.text)
    }

    @Test
    fun testAGapEndsTheLineAndIsKeptInPlace() {
        val t = LiveTranscript()
        t.append(tokens(" before" to 11.0))
        t.gap(12.3, 41.6)
        t.append(tokens("after" to 41.8, " it" to 42.0))
        assertEquals(listOf(LiveTranscript.Line("before", 11.0, 11.0), LiveTranscript.Gap(12.3, 41.6)), t.items)
        assertEquals("after it", t.openLine?.text, "a session's first token starts a line even without a space")
        assertEquals(
            "live text paused, 0:12 to 0:41, will come with the final transcript",
            LiveTranscript.gapLabel(12.3, 41.6),
        )
        t.close()
        assertEquals("after it", t.lastClosedLine)
        assertNull(t.openLine)
    }

    @Test
    fun testClock() {
        assertEquals("0:00", LiveTranscript.clock(0.0))
        assertEquals("0:59", LiveTranscript.clock(59.9))
        assertEquals("12:34", LiveTranscript.clock(754.0))
        assertEquals("1:02:05", LiveTranscript.clock(3725.0))
    }

    /**
     * Swift's `Character.isWhitespace` is Unicode's White_Space property. Java's
     * `Character.isWhitespace` is not: it says no to a no-break space and yes to U+001C. The port
     * must agree with Swift, so a no-break space and an ideographic space start a word.
     */
    @Test
    fun testUnicodeWhiteSpaceStartsAWordAsInSwift() {
        val t = LiveTranscript()
        t.append(tokens(" Hola." to 0.1, " Que" to 0.3, "　tal" to 0.5))
        assertEquals(listOf(LiveTranscript.Line("Hola.", 0.1, 0.1)), t.items, "the no-break space started a word, so the full stop closed the line")
        assertEquals("Que　tal", t.openLine?.text, "the leading no-break space is dropped, the ideographic space joins two words")
    }

    /** The control for the test above: U+001C is whitespace to Java but not to Unicode or Swift, so it continues the word. */
    @Test
    fun testAFileSeparatorIsNotWhiteSpace() {
        val t = LiveTranscript()
        t.append(tokens(" end." to 0.1, "\u001Cmore" to 0.3))
        assertEquals(emptyList(), t.items)
        assertEquals("end.\u001Cmore", t.openLine?.text)
    }

    /** Fullwidth and ideographic sentence ends close the line, and a token ending in an emoji (two UTF-16 units) does not. */
    @Test
    fun testTheLastCodePointDecidesTheSentenceEnd() {
        val t = LiveTranscript()
        t.append(tokens(" 你好。" to 0.1, " 再见！" to 0.2, " ok😀" to 0.3, " next" to 0.4))
        assertEquals(listOf(LiveTranscript.Line("你好。", 0.1, 0.1), LiveTranscript.Line("再见！", 0.2, 0.2)), t.items)
        assertEquals("ok😀 next", t.openLine?.text)
    }
}
