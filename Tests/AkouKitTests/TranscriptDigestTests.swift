// SPDX-License-Identifier: GPL-3.0-or-later
import AkouClient
import Foundation
import XCTest

final class TranscriptDigestTests: XCTestCase {
    func testKeepsWholeSentencesUpToTheCap() {
        let text = "We ship on Friday. The phone records the file. Then the server transcribes it."
        // 46 characters fit the first two sentences; the third would pass the cap.
        XCTAssertEqual(TranscriptDigest.opening(text: text, limit: 50), "We ship on Friday. The phone records the file.")
        XCTAssertEqual(TranscriptDigest.opening(text: text, limit: 18), "We ship on Friday.")
        XCTAssertEqual(TranscriptDigest.opening(text: text, limit: 600), text)
    }

    func testCutsAOverlongFirstSentenceAtAWordAndMarksIt() {
        let text = "this sentence has no full stop anywhere and keeps going for a long while"
        let out = TranscriptDigest.opening(text: text, limit: 30)
        XCTAssertEqual(out, "this sentence has no full…")
        XCTAssertLessThanOrEqual(out.count, 30)
    }

    func testNeverPassesTheCap() {
        let text = String(repeating: "Short one. ", count: 200)
        for limit in [1, 5, 11, 12, 100, 600] {
            XCTAssertLessThanOrEqual(TranscriptDigest.opening(text: text, limit: limit).count, limit, "limit \(limit)")
        }
    }

    func testEmptyTranscriptGivesEmptyText() {
        XCTAssertEqual(TranscriptDigest.opening(text: "", limit: 600), "")
        XCTAssertEqual(TranscriptDigest.opening(text: "  \n\t ", limit: 600), "")
        XCTAssertEqual(TranscriptDigest.opening(text: nil, words: [], limit: 600), "")
    }

    func testCollapsesWhitespace() {
        XCTAssertEqual(TranscriptDigest.opening(text: "  Hello\n\nthere.   How   are you? ", limit: 600), "Hello there. How are you?")
    }

    func testWordsWithNullTimesStillCount() throws {
        // A result from an engine that gives no word times (akou's JobWord: s, e and c are null).
        let json = #"[{"w":"Hola","s":null,"e":null,"c":null},{"w":"equipo.","s":null,"e":null,"c":null},{"w":"Empezamos","s":1.5,"e":2.1,"c":0.9}]"#
        let words = try JSONDecoder().decode([TranscriptDigest.Word].self, from: Data(json.utf8))
        XCTAssertNil(words[0].s)
        XCTAssertEqual(TranscriptDigest.opening(text: nil, words: words, limit: 600), "Hola equipo. Empezamos")
        XCTAssertEqual(TranscriptDigest.opening(text: nil, words: words, limit: 15), "Hola equipo.")
    }

    func testTextWinsOverWordsWhenBothArePresent() {
        let words = [TranscriptDigest.Word(w: "ignored")]
        XCTAssertEqual(TranscriptDigest.opening(text: "The text.", words: words, limit: 600), "The text.")
        XCTAssertEqual(TranscriptDigest.opening(text: " ", words: words, limit: 600), "ignored")
    }

    func testSentenceEndsInOtherScripts() {
        XCTAssertEqual(TranscriptDigest.opening(text: "¿Vamos? ¡Sí! Luego seguimos hablando", limit: 14), "¿Vamos? ¡Sí!")
        XCTAssertEqual(TranscriptDigest.opening(text: "今日は。明日も。", limit: 4), "今日は。")
    }
}
