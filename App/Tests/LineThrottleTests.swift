// SPDX-License-Identifier: GPL-3.0-or-later
import XCTest
@testable import akou

/// The Live Activity's budget: a new line at most once every five seconds, a state change at once.
final class LineThrottleTests: XCTestCase {
    private let t0 = ContinuousClock.now

    private func at(_ seconds: Double) -> ContinuousClock.Instant { t0 + .milliseconds(Int(seconds * 1000)) }

    func testTheFirstLineGoesAtOnce() {
        var t = LineThrottle()
        XCTAssertTrue(t.offer(at: at(0)))
        XCTAssertFalse(t.lineWaiting)
    }

    func testALineWithinFiveSecondsWaitsForItsTurn() {
        var t = LineThrottle()
        t.push(at: at(0)) // the activity started
        XCTAssertFalse(t.offer(at: at(4.9)))
        XCTAssertTrue(t.lineWaiting)
        XCTAssertEqual(t.due, at(5))
    }

    func testALineAfterFiveSecondsGoes() {
        var t = LineThrottle()
        t.push(at: at(0))
        XCTAssertTrue(t.offer(at: at(5)))
        XCTAssertNil(t.due)
        XCTAssertFalse(t.offer(at: at(9)), "the interval starts again at each push")
    }

    func testAWaitingLineKeepsItsSlot() {
        var t = LineThrottle()
        t.push(at: at(0))
        XCTAssertFalse(t.offer(at: at(1)))
        XCTAssertFalse(t.offer(at: at(2)))
        XCTAssertFalse(t.offer(at: at(6)), "a line waiting since earlier keeps the slot at 5 s")
        XCTAssertEqual(t.due, at(5))
        t.push(at: at(5))
        XCTAssertFalse(t.lineWaiting)
    }

    func testAStateChangeGoesAtOnceAndTakesTheWaitingLine() {
        var t = LineThrottle()
        t.push(at: at(0))
        XCTAssertFalse(t.offer(at: at(1)))
        // Pause or resume pushes right away, whatever the interval says.
        t.push(at: at(2))
        XCTAssertNil(t.due)
        XCTAssertFalse(t.offer(at: at(3)), "the state push restarted the interval")
        XCTAssertEqual(t.due, at(7))
    }

    /// The push at `due` sends the controller's content, so that content must already hold the
    /// newest line, even one that came too soon to go at once.
    @MainActor
    func testTheControllerShowsTheNewestLineEvenWhileItWaits() {
        let c = ActivityController()
        c.start(title: "t", startedAt: .now, liveText: true)
        defer { c.end() }
        c.setLastLine("one")
        c.setLastLine("two")
        c.setLastLine("three")
        XCTAssertEqual(c.content.lastLine, "three")
        c.setPaused(true, startedAt: .now)
        XCTAssertEqual(c.content.lastLine, "three", "the pause push carries the waiting line")
    }
}
