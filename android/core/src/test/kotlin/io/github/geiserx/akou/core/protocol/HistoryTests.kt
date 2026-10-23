// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * akou's history answers with made-up content, the same bodies as HistoryFixtures in
 * Tests/AkouKitTests/HistoryClientTests.swift. Where each shape comes from in the akou
 * repository is noted there.
 */
object HistoryFixtures {
    const val CALLS = """
    {"calls":[
      {"id":"01JCALLB0000000000000000002","workspace":"Work","title":"Weekly sync","createdAt":1760335200000,"state":"ended","endedAt":1760338800000,"parts":2,"updatedAt":1760339000000,"folder":"/Users/someone/akou/Work/2026-10-13 Weekly sync"},
      {"id":"01JCALLA0000000000000000001","workspace":"","title":"","createdAt":1760248800000,"state":"recording","endedAt":null,"parts":1,"updatedAt":1760248900000,"folder":"/Users/someone/akou/Inbox/x"}
    ]}
    """

    const val CALL = """
    {"id":"01JCALLB0000000000000000002","title":"Weekly sync","workspace":"Work","template":null,"user":"Someone","tz":"Europe/Madrid","folder":"/Users/someone/akou/Work/x","state":"ended","live":false,"muted":false,
     "createdAt":1760335200000,"startedAt":1760335201000,"startedLocal":"08:00:01","endedAt":1760338800000,"endedReason":"stop","failure":null,
     "parts":[
       {"part":1,"file":"audio/part-001.opus","wallStart":1760335201000,"mic":"Built-in Microphone","call":"system","capture":"ok","ended":{"reason":"restart","fileSeconds":1200.5},"pauses":0,"gaps":0,"finalDone":true},
       {"part":2,"file":"audio/part-002.opus","wallStart":1760336500000,"mic":"Built-in Microphone","call":"system","capture":"ok","ended":{"reason":"stop","fileSeconds":2299.5},"pauses":1,"gaps":0,"finalDone":true}
     ],
     "roster":[{"spk":"you","label":"You"},{"spk":"c2","label":"Ana","name":"Ana","namedBy":"human"}],
     "health":[],"lag":null,
     "final":{"state":"done","partsDone":2,"warning":null,"error":null,"model":"qwen3-asr","progress":null},
     "cursor":812,"now":1760339100000}
    """

    const val TRANSCRIPT = """
    {"call":"01JCALLB0000000000000000002","state":"ended","live":false,"layer":"best","tz":"Europe/Madrid","zone":"Times are local, Europe/Madrid (GMT+2).","cursor":812,"memoStale":false,"provisional":[],
     "total":3,"omitted":0,"more":0,"offset":0,"nextOffset":null,
     "lines":[
       {"id":"s1","seq":10,"time":"08:00:05","w0":1760335205000,"w1":1760335209000,"part":1,"ch":"mic","spk":"you","speaker":"You","text":"Morning, shall we start with the release?","layer":"final"},
       {"id":"s2","seq":11,"time":"08:00:10","w0":1760335210000,"w1":1760335214000,"part":1,"ch":"call","spk":"c2","speaker":"Ana","text":"Yes, the build went out last night.","heard":"Yes, the bild went out last night.","annotated":"Yes, the build (heard: \"bild\") went out last night.","layer":"final"},
       {"id":"s3","seq":40,"time":"08:21:42","w0":1760336502000,"w1":1760336504500,"part":2,"ch":"call","spk":"c2","speaker":"Ana","text":"Back again after the restart.","layer":"live"}
     ]}
    """

    const val ENHANCED = """
    {"call":"01JCALLB0000000000000000002","enhanced":{"rev":2,"template":"meeting","coversSeq":800,"by":"app","model":"claude","cites":[],"markdown":"## Summary\n- The build went out.\n- **Next:** the release notes."},
     "revisions":[{"rev":1,"template":"meeting","by":"app","model":"claude"},{"rev":2,"template":"meeting","by":"app","model":"claude"}],"reEnhance":null}
    """

    const val NO_ENHANCED = """{"call":"01JCALLA0000000000000000001","enhanced":null,"revisions":[],"reEnhance":null}"""

    const val NOTES = """
    {"call":"01JCALLB0000000000000000002","notes":[
      {"id":"n1","rev":1,"text":"Ask about the notes","w":1760335300000,"afterSeq":11,"by":"human","author":"human","seq":20},
      {"id":"n2","rev":1,"text":"Fixed: bild -> build","w":1760335400000,"afterSeq":12,"by":"agent:fix","author":"agent","client":"fix","from":"fix","seq":21}
    ]}
    """

    const val DICTATIONS = """
    {"items":[
      {"id":"dct_02","at":1760340000000,"state":"inserted","by":"key","app":"com.example.notes","app_name":"Notes","seconds":4.2,"text":"Buy milk and call the plumber.","raw":"buy milk and call the plumber","language":"en",
       "words":[{"w":"buy","s":0.1,"e":0.3,"c":0.98},{"w":"milk","s":0.35,"e":0.6,"c":0.97}],"engine":"fast","model":"parakeet-tdt-0.6b-v3","ms":180,"formatted":true},
      {"id":"dct_01","at":1760330000000,"state":"failed","by":"key","app":null,"app_name":null,"seconds":null,"text":null,"raw":null,"language":null,"words":[],"engine":"best","model":null,"ms":null,"error":"the model is missing"}
    ],"next_cursor":"dct_01"}
    """

    const val DICTATIONS_LAST = """{"items":[],"next_cursor":null}"""

    const val JOBS = """
    {"jobs":[
      {"id":"job_b","title":"Interview.m4a","status":"done","key_id":"k_desktop","created_at":"2026-10-12T09:00:00.000Z","started_at":"2026-10-12T09:00:01.000Z","finished_at":"2026-10-12T09:03:00.000Z","preset":"best","model":"qwen3-asr","model_source":"preset","priority":0,"interactive":false,"keep_audio":true,"language":"auto","languages":[],"diarize":true,"metadata":null,"links":{"self":"/v1/jobs/job_b","result":"/v1/jobs/job_b/result","events":"/v1/events","audio":"/v1/jobs/job_b/audio"}},
      {"id":"job_a","title":"Standup","status":"done","key_id":"k_phone","created_at":"2026-10-11T08:00:00.000Z","started_at":null,"finished_at":null,"preset":"fast","model":"parakeet-tdt-0.6b-v3","model_source":"preset","priority":0,"interactive":false,"keep_audio":true,"language":"auto","languages":[],"diarize":false,"metadata":{"companion":1,"recording_id":"rec-1","workspace":"Work"},"links":{"self":"/v1/jobs/job_a","result":"/v1/jobs/job_a/result","events":"/v1/events"}}
    ],"cursor":null}
    """
}

/** The model-decoding cases of Tests/AkouKitTests/HistoryClientTests.swift (the HTTP cases belong to the client). */
class HistoryTests {
    private inline fun <reified T> decode(json: String): T = AkouJson.decodeFromString(json)

    private fun key(vararg scopes: String) = KeyInfo(id = "k_test", name = "phone", scopes = scopes.toList())

    @Test
    fun testTheKeysScopesDecideWhatThePhoneMayDo() {
        assertTrue(key("jobs").canUpload)
        assertFalse(key("jobs").seesHistory, "a jobs key sees only its own recordings")
        assertFalse(key("read").canUpload, "read writes nothing, so it cannot be Record to")
        assertTrue(key("read").seesHistory)
        assertTrue(key("admin").canUpload)
        assertTrue(key("admin").seesHistory)
        val both = decode<KeyInfo>("""{"id":"k_test","name":"phone","scopes":["jobs","read"],"created_at":1760000000000}""")
        assertEquals(listOf("jobs", "read"), both.scopes)
        assertTrue(both.canUpload && both.seesHistory)
    }

    @Test
    fun testTheCallsListReadsAkousRows() {
        val calls = decode<CallList>(HistoryFixtures.CALLS).calls
        assertEquals(listOf("01JCALLB0000000000000000002", "01JCALLA0000000000000000001"), calls.map { it.id })
        assertEquals("Weekly sync", calls[0].title)
        assertEquals("Work", calls[0].workspace)
        assertEquals(2, calls[0].parts)
        assertEquals(1760338800000.0, calls[0].endedAt)
        assertFalse(calls[0].isLive)
        assertTrue(calls[1].isLive, "a recording call with no end is live")
    }

    /** Not in the Swift test: the whole isLive rule, and the defaults of a sparse row. */
    @Test
    fun testIsLiveAndSparseRows() {
        for (state in listOf("recording", "paused", "starting", "restarting")) {
            assertTrue(decode<CallSummary>("""{"id":"c","state":"$state"}""").isLive, state)
            assertFalse(decode<CallSummary>("""{"id":"c","state":"$state","endedAt":1}""").isLive, "$state with an end")
        }
        for (state in listOf("ended", "interrupted", "failed", "")) {
            assertFalse(decode<CallSummary>("""{"id":"c","state":"$state"}""").isLive, state)
        }
        val sparse = decode<CallSummary>("""{"id":"c","title":null,"parts":null}""")
        assertEquals("", sparse.title)
        assertEquals(0, sparse.parts)
        assertEquals(0.0, sparse.createdAt)
        // A summary is strict about its id.
        assertFailsWith<IllegalArgumentException> { decode<CallSummary>("""{"title":"x"}""") }
    }

    @Test
    fun testACallsDetailHasItsPartsAndTheirTimes() {
        val call = decode<CallDetail>(HistoryFixtures.CALL)
        assertEquals(listOf(1, 2), call.parts.map { it.part })
        assertEquals(1760336500000.0, call.parts[1].wallStart)
        assertEquals(3500.0, call.seconds)
        assertEquals("done", call.final?.state)
        assertEquals("Europe/Madrid", call.tz)
    }

    /** Not in the Swift test: `final` is lenient, the rest of a detail is not; no ended part means no seconds. */
    @Test
    fun testCallDetailLeniency() {
        val odd = decode<CallDetail>("""{"id":"c","final":"done","parts":[{"part":1}]}""")
        assertNull(odd.final)
        assertNull(odd.seconds)
        assertFalse(odd.live)
        assertFailsWith<IllegalArgumentException> { decode<CallDetail>("""{"id":"c","parts":"none"}""") }
    }

    @Test
    fun testTheTranscriptKeepsSpeakersTimesAndParts() {
        val t = decode<CallTranscript>(HistoryFixtures.TRANSCRIPT)
        assertEquals(listOf("You", "Ana", "Ana"), t.lines.map { it.speaker })
        assertEquals(listOf("08:00:05", "08:00:10", "08:21:42"), t.lines.map { it.time })
        assertEquals(listOf(1, 1, 2), t.lines.map { it.part })
        assertEquals("Yes, the build went out last night.", t.lines[1].text, "the corrected text, not the annotated one")
        assertEquals("Times are local, Europe/Madrid (GMT+2).", t.zone)
    }

    /** A line a newer or odder akou writes without a speaker or a time keeps the rest of the transcript. */
    @Test
    fun testAnOddLineNeverCostsTheTranscript() {
        val json = """{"call":"c","lines":[{"id":"s1","time":"08:00:05","w0":1,"part":1,"speaker":"You","text":"Hi"},{"id":"s2","w0":2,"part":1,"spk":"c3","speaker":null,"text":"Unnamed"}]}"""
        val t = decode<CallTranscript>(json)
        assertEquals(listOf("Hi", "Unnamed"), t.lines.map { it.text })
        assertEquals("c3", t.lines[1].speaker, "no name: the speaker's id")
        assertEquals("", t.lines[1].time)
    }

    /** Not in the Swift test: every field of a line wrong or missing, and the fallbacks for id, part, speaker and time. */
    @Test
    fun testALineWithEveryFieldWrongStillDecodes() {
        val json = """{"call":"c","lines":[{"id":7,"time":5,"w0":"x","part":"one","spk":3,"speaker":false,"text":null,"a0":"no"},{"w0":1760335205000.9},{}]}"""
        val lines = decode<CallTranscript>(json).lines
        assertEquals(3, lines.size)
        assertEquals(CallLine(id = "w0", time = "", w0 = 0.0, part = 1, speaker = "", text = ""), lines[0])
        assertEquals("w1760335205000", lines[1].id)
        assertEquals("w0", lines[2].id)
        assertEquals(1, lines[2].part)
    }

    /**
     * After a pause the part's file stops advancing while the clock goes on, so a line's place in the
     * audio is `a0`, not its wall-clock offset; an older akou without `a0` gets the offset.
     */
    @Test
    fun testALineSeeksToA0WhenAkouSendsIt() {
        val json = """{"call":"c","lines":[{"id":"s9","time":"08:30:00","w0":1760337000000,"a0":312.5,"a1":316,"part":1,"speaker":"Ana","text":"After the pause."},{"id":"s1","time":"08:00:05","w0":1760335205000,"part":1,"speaker":"You","text":"Old akou."}]}"""
        val t = decode<CallTranscript>(json)
        val wallStart = 1760335201000.0
        assertEquals(1799.0, (t.lines[0].w0 - wallStart) / 1000, "the wall-clock offset, wrong after a pause")
        assertEquals(312.5, t.lines[0].seconds(intoPartStartingAt = wallStart))
        assertEquals(316.0, t.lines[0].a1)
        assertEquals(4.0, t.lines[1].seconds(intoPartStartingAt = wallStart), "no a0: the wall-clock offset")
        assertNull(t.lines[1].seconds(intoPartStartingAt = null))
    }

    /** Not in the Swift test: the seek never goes below 0, from a0 or from the offset. */
    @Test
    fun testTheSeekIsClampedAtZero() {
        val lines = decode<CallTranscript>("""{"call":"c","lines":[{"w0":1000,"a0":-2},{"w0":1000}]}""").lines
        assertEquals(0.0, lines[0].seconds(intoPartStartingAt = null))
        assertEquals(0.0, lines[1].seconds(intoPartStartingAt = 5000.0))
    }

    @Test
    fun testTheSummaryIsThereOrNil() {
        val s = decode<CallEnhanced>(HistoryFixtures.ENHANCED).enhanced
        assertEquals(2, s?.rev)
        assertTrue(s?.markdown?.startsWith("## Summary") ?: false)
        assertNull(decode<CallEnhanced>(HistoryFixtures.NO_ENHANCED).enhanced)
    }

    @Test
    fun testTheNotepadLines() {
        val notes = decode<CallNotes>(HistoryFixtures.NOTES).notes
        assertEquals(listOf("Ask about the notes", "Fixed: bild -> build"), notes.map { it.text })
        assertEquals(listOf("human", "agent"), notes.map { it.author })
    }

    /** Not in the Swift test: a note never fails either. */
    @Test
    fun testAnOddNoteNeverCostsTheNotes() {
        val notes = decode<CallNotes>("""{"call":"c","notes":[{"w":5,"text":3,"author":1},{}]}""").notes
        assertEquals(CallNote(id = "n5", text = "", w = 5.0, author = null), notes[0])
        assertEquals("n0", notes[1].id)
    }

    @Test
    fun testDictationsPageOnWithTheirCursor() {
        val first = decode<DictationPage>(HistoryFixtures.DICTATIONS)
        assertEquals(listOf("dct_02", "dct_01"), first.items.map { it.id })
        assertEquals("dct_01", first.nextCursor)
        assertEquals("Notes", first.items[0].appName)
        assertEquals("Buy milk and call the plumber.", first.items[0].text)
        assertEquals("buy", first.items[0].words.firstOrNull()?.w)
        assertNull(first.items[1].text)
        assertEquals("the model is missing", first.items[1].error)
        assertNull(decode<DictationPage>(HistoryFixtures.DICTATIONS_LAST).nextCursor)
    }

    /** Not in the Swift test: a dictation is strict except `words` and `error`. */
    @Test
    fun testDictationLeniency() {
        val d = decode<Dictation>("""{"id":"d","words":"none","error":{"code":"x"}}""")
        assertEquals(emptyList(), d.words)
        assertNull(d.error)
        assertEquals(0.0, d.at)
        assertFailsWith<IllegalArgumentException> { decode<Dictation>("""{"id":"d","text":5}""") }
    }

    @Test
    fun testEveryJobWithAnotherKeysToo() {
        val page = decode<JobPage>(HistoryFixtures.JOBS)
        assertEquals(listOf("job_b", "job_a"), page.jobs.map { it.id })
        assertNull(page.jobs[0].metadata, "another client's job carries no companion metadata")
        assertEquals("Work", page.jobs[1].metadata?.workspace)
        assertNull(page.cursor)
    }
}
