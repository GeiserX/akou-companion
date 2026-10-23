// SPDX-License-Identifier: GPL-3.0-or-later
import XCTest
@testable import akou

/// The Live Activity's budget: a new line at most once every five seconds, a state change at once.
final class LineThrottleTests: XCTestCase {
    private let t0 = ContinuousClock.now

    private func at(_ seconds: Double) -> ContinuousClock.Instant { t0 + .milliseconds(Int(seconds * 1000)) }

    func testTheFirstLineGoesAtOnce() {
        var t = LineThrottle()
        XCTAssertEqual(t.offer("one", at: at(0)), "one")
        XCTAssertNil(t.held)
    }

    func testALineWithinFiveSecondsIsHeldUntilItsTurn() {
        var t = LineThrottle()
        t.push(at: at(0)) // the activity started
        XCTAssertNil(t.offer("early", at: at(4.9)))
        XCTAssertEqual(t.held, "early")
        XCTAssertEqual(t.due, at(5))
    }

    func testALineAfterFiveSecondsGoes() {
        var t = LineThrottle()
        t.push(at: at(0))
        XCTAssertEqual(t.offer("late", at: at(5)), "late")
        XCTAssertNil(t.due)
        XCTAssertNil(t.offer("too soon", at: at(9)), "the interval starts again at each push")
    }

    func testTheHeldLineThatGoesIsTheNewest() {
        var t = LineThrottle()
        t.push(at: at(0))
        XCTAssertNil(t.offer("a", at: at(1)))
        XCTAssertNil(t.offer("b", at: at(2)))
        XCTAssertNil(t.offer("c", at: at(6)), "a line held earlier keeps the slot; the newest replaces it")
        XCTAssertEqual(t.due, at(5))
        XCTAssertEqual(t.push(at: at(5)), "c")
        XCTAssertNil(t.held)
    }

    func testAStateChangeGoesAtOnceAndCarriesTheHeldLine() {
        var t = LineThrottle()
        t.push(at: at(0))
        XCTAssertNil(t.offer("held", at: at(1)))
        // Pause or resume pushes right away, whatever the interval says.
        XCTAssertEqual(t.push(at: at(2)), "held")
        XCTAssertNil(t.due)
        XCTAssertNil(t.offer("next", at: at(3)), "the state push restarted the interval")
        XCTAssertEqual(t.due, at(7))
    }
}
