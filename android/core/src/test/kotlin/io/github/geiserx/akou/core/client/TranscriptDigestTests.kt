// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou.core.client

import io.github.geiserx.akou.core.protocol.AkouJson
import io.github.geiserx.akou.core.protocol.JobWord
import java.text.BreakIterator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Tests/AkouKitTests/TranscriptDigestTests.swift, plus the grapheme cases of docs/ANDROID.md section 5. */
class TranscriptDigestTests {
    /** What Swift's `String.count` gives: grapheme clusters. */
    private fun graphemes(s: String): Int {
        val it = BreakIterator.getCharacterInstance()
        it.setText(s)
        var n = 0
        while (it.next() != BreakIterator.DONE) n++
        return n
    }

    private val JVM_MUST_COUNT_GRAPHEMES = "the test JVM must count grapheme clusters (JDK 20 or newer)"

    private fun codePoints(s: String): Int = s.codePointCount(0, s.length)

    @Test
    fun testKeepsWholeSentencesUpToTheCap() {
        val text = "We ship on Friday. The phone records the file. Then the server transcribes it."
        // 46 characters fit the first two sentences; the third would pass the cap.
        assertEquals("We ship on Friday. The phone records the file.", TranscriptDigest.opening(text, limit = 50))
        assertEquals("We ship on Friday.", TranscriptDigest.opening(text, limit = 18))
        assertEquals(text, TranscriptDigest.opening(text, limit = 600))
    }

    @Test
    fun testCutsAOverlongFirstSentenceAtAWordAndMarksIt() {
        val text = "this sentence has no full stop anywhere and keeps going for a long while"
        val out = TranscriptDigest.opening(text, limit = 30)
        assertEquals("this sentence has no full…", out)
        assertTrue(graphemes(out) <= 30)
    }

    @Test
    fun testNeverPassesTheCap() {
        val text = "Short one. ".repeat(200)
        for (limit in listOf(1, 5, 11, 12, 100, 600)) {
            assertTrue(graphemes(TranscriptDigest.opening(text, limit = limit)) <= limit, "limit $limit")
        }
    }

    @Test
    fun testEmptyTranscriptGivesEmptyText() {
        assertEquals("", TranscriptDigest.opening("", limit = 600))
        assertEquals("", TranscriptDigest.opening("  \n\t ", limit = 600))
        assertEquals("", TranscriptDigest.opening(null, emptyList(), limit = 600))
    }

    @Test
    fun testCollapsesWhitespace() {
        assertEquals("Hello there. How are you?", TranscriptDigest.opening("  Hello\n\nthere.   How   are you? ", limit = 600))
    }

    /**
     * Not in the Swift test: whitespace is Unicode's White_Space, as Swift's `isWhitespace`, not the
     * JVM's `Char.isWhitespace`. U+0085 (next line) separates words; U+001C (file separator) does not.
     */
    @Test
    fun testWhitespaceIsUnicodeWhiteSpace() {
        assertEquals("a b", TranscriptDigest.collapse("a\u0085b"))
        assertEquals("a\u001Cb", TranscriptDigest.collapse("a\u001Cb"))
        assertEquals("a b", TranscriptDigest.collapse("\u3000a\u00A0\u2028b\u0085"))
    }

    @Test
    fun testWordsWithNullTimesStillCount() {
        // A result from an engine that gives no word times (akou's JobWord: s, e and c are null).
        val json = """[{"w":"Hola","s":null,"e":null,"c":null},{"w":"equipo.","s":null,"e":null,"c":null},{"w":"Empezamos","s":1.5,"e":2.1,"c":0.9}]"""
        val words = AkouJson.decodeFromString<List<JobWord>>(json)
        assertNull(words[0].s)
        assertEquals("Hola equipo. Empezamos", TranscriptDigest.opening(null, words, limit = 600))
        assertEquals("Hola equipo.", TranscriptDigest.opening(null, words, limit = 15))
    }

    @Test
    fun testTextWinsOverWordsWhenBothArePresent() {
        val words = listOf(JobWord(w = "ignored"))
        assertEquals("The text.", TranscriptDigest.opening("The text.", words, limit = 600))
        assertEquals("ignored", TranscriptDigest.opening(" ", words, limit = 600))
    }

    @Test
    fun testSentenceEndsInOtherScripts() {
        assertEquals("¿Vamos? ¡Sí!", TranscriptDigest.opening("¿Vamos? ¡Sí! Luego seguimos hablando", limit = 14))
        assertEquals("今日は。", TranscriptDigest.opening("今日は。明日も。", limit = 4))
    }

    // Not in the Swift test: Swift's count is grapheme clusters, so an emoji is one character there.
    // Each case is at the limit boundary, and each first proves that its answer is longer than the
    // limit in code points, so an implementation that counted code points (or UTF-16 units) could
    // not return it and the case would fail.

    @Test
    fun testZwjFamilyEmojiCountsAsOneCharacter() {
        val family = "👨‍👩‍👧" // man ZWJ woman ZWJ girl
        val text = "Hi $family."
        assertEquals(5, graphemes(text), JVM_MUST_COUNT_GRAPHEMES)
        assertTrue(codePoints(text) > 5, "control: in code points the whole text passes the limit")
        assertEquals(text, TranscriptDigest.opening(text, limit = 5))
        // One sentence longer, the cap still keeps the family whole.
        assertEquals(text, TranscriptDigest.opening("$text More words follow here.", limit = 5))
    }

    @Test
    fun testFlagCountsAsOneCharacterWhenCut() {
        val spain = "🇪🇸" // regional indicators E and S
        val text = spain.repeat(4)
        // No sentence end and no space: cut at limit - 1 characters and mark it.
        val out = TranscriptDigest.opening(text, limit = 3)
        assertEquals(spain + spain + "…", out)
        assertTrue(codePoints(out) > 3, "control: in code points the answer passes the limit")
        assertEquals(3, graphemes(out))
    }

    @Test
    fun testSkinToneEmojiAtTheSentenceBoundary() {
        val thumb = "👍🏽" // thumbs up, medium skin tone
        val text = "Ok $thumb. Next one here."
        val first = "Ok $thumb."
        assertEquals(5, graphemes(first), JVM_MUST_COUNT_GRAPHEMES)
        assertTrue(codePoints(first) > 5, "control: in code points the first sentence passes the limit")
        assertEquals(first, TranscriptDigest.opening(text, limit = 5))
        // One short of the sentence: cut at the last word that fits, never inside the emoji.
        assertEquals("Ok…", TranscriptDigest.opening(text, limit = 4))
        assertEquals(thumb + thumb + "…", TranscriptDigest.opening(thumb.repeat(4), limit = 3))
    }
}
