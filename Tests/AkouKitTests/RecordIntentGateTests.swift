// SPDX-License-Identifier: GPL-3.0-or-later
import AkouClient
import Foundation
import XCTest

@MainActor
private final class FakeRecorder: RecordIntentRecorder {
    enum State { case idle, recording, paused }
    struct Refused: Error {}

    var state = State.idle
    var refuseStart = false
    var refuseResume = false
    private(set) var calls: [String] = []

    var isRecording: Bool { state != .idle }
    var isPaused: Bool { state == .paused }

    func start(workspace: String?, title: String?) async throws {
        calls.append("start(\(title ?? "nil"))")
        if refuseStart { throw Refused() }
        state = .recording
    }

    func resume() {
        calls.append("resume")
        if refuseResume { return }
        state = .recording
    }

    func stopRecording() async {
        calls.append("stop")
        state = .idle
    }
}

@MainActor
final class RecordIntentGateTests: XCTestCase {
    private var statuses: [Bool] = []
    private func note(_ recording: Bool) { statuses.append(recording) }

    override func setUp() async throws {
        statuses = []
    }

    func testStartsWhenIdle() async throws {
        let r = FakeRecorder()
        try await RecordIntentGate.start(r, title: "Standup", onStatus: note)
        XCTAssertEqual(r.calls, ["start(Standup)"])
        XCTAssertEqual(statuses, [true])
    }

    func testDoesNotStartASecondRecording() async throws {
        let r = FakeRecorder()
        r.state = .recording
        try await RecordIntentGate.start(r, title: nil, onStatus: note)
        XCTAssertEqual(r.calls, [])
        XCTAssertEqual(statuses, [true])
    }

    func testResumesAPausedRecordingInsteadOfStartingOne() async throws {
        let r = FakeRecorder()
        r.state = .paused
        try await RecordIntentGate.start(r, title: nil, onStatus: note)
        XCTAssertEqual(r.calls, ["resume"])
        XCTAssertEqual(statuses, [true])
    }

    func testAFailedResumeThrowsAndStaysPaused() async {
        let r = FakeRecorder()
        r.state = .paused
        r.refuseResume = true
        do {
            try await RecordIntentGate.start(r, title: nil, onStatus: note)
            XCTFail("a resume that left the recording paused must not report success")
        } catch {
            XCTAssertEqual(error as? RecordIntentGateError, .resumeFailed)
        }
        XCTAssertEqual(r.calls, ["resume"])
        XCTAssertTrue(r.isPaused)
        // A paused recording still holds its file, so the control keeps showing it.
        XCTAssertEqual(statuses, [true])
    }

    func testAFailedStartStillReportsNotRecording() async {
        let r = FakeRecorder()
        r.refuseStart = true
        do {
            try await RecordIntentGate.start(r, title: nil, onStatus: note)
            XCTFail("the recorder's error must reach the intent")
        } catch {
            XCTAssertTrue(error is FakeRecorder.Refused)
        }
        XCTAssertEqual(statuses, [false])
    }

    func testStopsARunningRecording() async {
        let r = FakeRecorder()
        r.state = .recording
        await RecordIntentGate.stop(r, onStatus: note)
        XCTAssertEqual(r.calls, ["stop"])
        XCTAssertEqual(statuses, [false])
    }

    func testStopsAPausedRecording() async {
        let r = FakeRecorder()
        r.state = .paused
        await RecordIntentGate.stop(r, onStatus: note)
        XCTAssertEqual(r.calls, ["stop"])
        XCTAssertEqual(statuses, [false])
    }

    func testStopWithNothingRunningDoesNothing() async {
        let r = FakeRecorder()
        await RecordIntentGate.stop(r, onStatus: note)
        XCTAssertEqual(r.calls, [])
        XCTAssertEqual(statuses, [false])
    }
}
