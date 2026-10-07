// SPDX-License-Identifier: GPL-3.0-or-later
import AkouClient
import AkouProtocol
import Foundation
import XCTest

final class LiveTranscriptTests: XCTestCase {
    private func tokens(_ list: [(String, Double)]) -> LiveWords {
        LiveWords(tokens: list.map { LiveToken(text: $0.0, t: $0.1) })
    }

    func testTokensJoinIntoWords() {
        var t = LiveTranscript()
        t.append(tokens([(" Hel", 0.1), ("lo", 0.2), (" wor", 0.4), ("ld", 0.5)]))
        XCTAssertEqual(t.openLine, .init(text: "Hello world", start: 0.1, end: 0.5))
        XCTAssertEqual(t.items, [])
        XCTAssertNil(t.lastClosedLine)
    }

    func testSentencePunctuationEndsTheLineAtTheNextWord() {
        var t = LiveTranscript()
        t.append(tokens([(" Hi", 0.1), (" there.", 0.3)]))
        XCTAssertNil(t.lastClosedLine, "the line closes when the next word starts, not on the full stop itself")
        t.append(tokens([(" How", 0.6), (" are", 0.7), (" you?", 0.9), (" Fine", 1.2)]))
        XCTAssertEqual(t.items, [
            .line(.init(text: "Hi there.", start: 0.1, end: 0.3)),
            .line(.init(text: "How are you?", start: 0.6, end: 0.9)),
        ])
        XCTAssertEqual(t.lastClosedLine, "How are you?")
        XCTAssertEqual(t.openLine?.text, "Fine")
    }

    func testAFullStopInsideAWordDoesNotEndTheLine() {
        var t = LiveTranscript()
        t.append(tokens([(" pi", 0.1), (" is", 0.2), (" 3.", 0.3), ("14", 0.4), (" or", 0.6), (" so", 0.7)]))
        XCTAssertEqual(t.items, [])
        XCTAssertEqual(t.openLine?.text, "pi is 3.14 or so")
    }

    func testASilenceEndsTheLine() {
        var t = LiveTranscript()
        t.append(tokens([(" one", 0.0), (" two", 0.5), (" three", 1.75), (" four", 3.25), (" five", 3.5)]))
        XCTAssertEqual(t.items, [
            .line(.init(text: "one two three", start: 0.0, end: 1.75)),
        ], "1.25 s between two and three keeps the line; 1.5 s between three and four ends it")
        XCTAssertEqual(t.openLine?.text, "four five")
    }

    func testAGapEndsTheLineAndIsKeptInPlace() {
        var t = LiveTranscript()
        t.append(tokens([(" before", 11.0)]))
        t.gap(from: 12.3, to: 41.6)
        t.append(tokens([("after", 41.8), (" it", 42.0)]))
        XCTAssertEqual(t.items, [
            .line(.init(text: "before", start: 11.0, end: 11.0)),
            .gap(from: 12.3, to: 41.6),
        ])
        XCTAssertEqual(t.openLine?.text, "after it", "a session's first token starts a line even without a space")
        XCTAssertEqual(LiveTranscript.gapLabel(from: 12.3, to: 41.6), "live text paused, 0:12 to 0:41, will come with the final transcript")
        t.close()
        XCTAssertEqual(t.lastClosedLine, "after it")
        XCTAssertNil(t.openLine)
    }

    func testClock() {
        XCTAssertEqual(LiveTranscript.clock(0), "0:00")
        XCTAssertEqual(LiveTranscript.clock(59.9), "0:59")
        XCTAssertEqual(LiveTranscript.clock(754), "12:34")
        XCTAssertEqual(LiveTranscript.clock(3725), "1:02:05")
    }
}
