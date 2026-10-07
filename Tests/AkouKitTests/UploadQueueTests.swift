// SPDX-License-Identifier: GPL-3.0-or-later
@testable import AkouClient
import AkouProtocol
import Foundation
import XCTest

/// A clock the test moves by hand.
final class TestClock: @unchecked Sendable {
    private let lock = NSLock()
    private var t = Date(timeIntervalSince1970: 1_800_000_000)
    var now: Date { lock.withLock { t } }
    func advance(_ s: TimeInterval) { lock.withLock { t = t.addingTimeInterval(s) } }
}

final class Flag: @unchecked Sendable {
    private let lock = NSLock()
    private var v: Bool
    init(_ v: Bool) { self.v = v }
    var value: Bool {
        get { lock.withLock { v } }
        set { lock.withLock { v = newValue } }
    }
}

final class UploadQueueTests: XCTestCase {
    var dir: URL!
    var audioDir: URL!
    let clock = TestClock()
    let keepLocalCopy = Flag(false)

    override func setUpWithError() throws {
        FakeAkouHTTP.reset()
        dir = FileManager.default.temporaryDirectory.appending(path: "queue-\(UUID().uuidString)")
        audioDir = dir.appending(path: "audio")
        try FileManager.default.createDirectory(at: audioDir, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: dir)
    }

    func makeQueue() throws -> UploadQueue {
        let clock = self.clock, keep = self.keepLocalCopy
        return try UploadQueue(directory: dir.appending(path: "state"), audioDirectory: audioDir, now: { clock.now }, keepLocalCopy: { keep.value })
    }

    /// A recording on disk, queued.
    @discardableResult
    func record(_ queue: UploadQueue, _ id: String) async throws -> URL {
        let file = audioDir.appending(path: "\(id).opus")
        try Data("OggS \(id)".utf8).write(to: file)
        try await queue.enqueue(.init(recordingID: id, title: "t", workspace: "Work"), fileName: "\(id).opus")
        return file
    }

    func drain(_ queue: UploadQueue) async throws {
        let client = JobsClient(baseURL: URL(string: "https://akou.example.com")!, key: FakeAkouHTTP.key)
        try await queue.drain(client: client, transport: URLSessionTransport(session: FakeAkouHTTP.session()), scratch: dir.appending(path: "scratch"))
    }

    func submits() -> [FakeAkouHTTP.Seen] { FakeAkouHTTP.seen.filter { $0.method == "POST" } }

    // MARK: -

    func testAnUploadWhoseAnswerWasLostIsRetriedWithTheSameKeyAndMakesOneJob() async throws {
        let queue = try makeQueue()
        let file = try await record(queue, "rec-1")
        FakeAkouHTTP.script(.acceptThenDrop)

        try await drain(queue)
        var maybe = await queue.item("rec-1")
        var item = try XCTUnwrap(maybe)
        XCTAssertEqual(item.state, .pending, "the client never heard the answer")
        XCTAssertEqual(item.attempts, 1)
        XCTAssertEqual(FakeAkouHTTP.jobs.count, 1, "but the server made the job")

        // Not before the backoff.
        try await drain(queue)
        XCTAssertEqual(submits().count, 1)

        clock.advance(UploadQueue.firstBackoff)
        try await drain(queue)
        maybe = await queue.item("rec-1")
        item = try XCTUnwrap(maybe)
        XCTAssertEqual(item.state, .done(jobID: "j1", keptOnServer: true))
        XCTAssertEqual(submits().map { $0.headers["idempotency-key"] }, ["rec-1", "rec-1"])
        XCTAssertEqual(FakeAkouHTTP.jobs.count, 1, "the retry got the first job back")
        XCTAssertFalse(FileManager.default.fileExists(atPath: file.path), "kept on the server, so the phone's copy goes")
        // The read-back that confirmed it.
        XCTAssertEqual(FakeAkouHTTP.seen.last?.method, "GET")
        XCTAssertEqual(FakeAkouHTTP.seen.last?.path, "/v1/jobs/j1")
    }

    func testTheLocalFileStaysWhenTheServerDoesNotKeepTheAudio() async throws {
        FakeAkouHTTP.ignoreKeepAudio()
        let queue = try makeQueue()
        let file = try await record(queue, "rec-2")
        try await drain(queue)
        let item = await queue.item("rec-2")
        XCTAssertEqual(item?.state, .done(jobID: "j1", keptOnServer: false))
        XCTAssertTrue(FileManager.default.fileExists(atPath: file.path))
    }

    func testTheLocalFileStaysWhenTheSettingKeepsACopy() async throws {
        keepLocalCopy.value = true
        let queue = try makeQueue()
        let file = try await record(queue, "rec-3")
        try await drain(queue)
        let item = await queue.item("rec-3")
        XCTAssertEqual(item?.state, .done(jobID: "j1", keptOnServer: true))
        XCTAssertTrue(FileManager.default.fileExists(atPath: file.path))
    }

    func testAFullQueueWaitsForRetryAfter() async throws {
        let queue = try makeQueue()
        try await record(queue, "rec-4")
        FakeAkouHTTP.script(.queueFull(retryAfter: 120))
        try await drain(queue)
        let item = await queue.item("rec-4")
        XCTAssertEqual(item?.state, .pending)
        XCTAssertEqual(item?.notBefore, clock.now.addingTimeInterval(120), "Retry-After, not the 5 s backoff")

        clock.advance(119)
        try await drain(queue)
        XCTAssertEqual(submits().count, 1, "still waiting")

        clock.advance(1)
        try await drain(queue)
        XCTAssertEqual(submits().count, 2)
        let done = await queue.item("rec-4")
        XCTAssertEqual(done?.state, .done(jobID: "j1", keptOnServer: true))
    }

    func testAnIdempotencyConflictParksTheItemAndKeepsTheFile() async throws {
        let queue = try makeQueue()
        let file = try await record(queue, "rec-5")
        // The server already holds rec-5 with another file: every retry would answer 422 again.
        let other = dir.appending(path: "other.opus")
        try Data("a different file".utf8).write(to: other)
        _ = try await JobsClient(baseURL: URL(string: "https://akou.example.com")!, key: FakeAkouHTTP.key, session: FakeAkouHTTP.session())
            .submit(.init(recordingID: "rec-5"), audio: other, bodyFile: dir.appending(path: "b"))

        try await drain(queue)
        let item = await queue.item("rec-5")
        XCTAssertEqual(item?.state, .parked(status: 422, code: "idempotency_conflict"))
        XCTAssertTrue(FileManager.default.fileExists(atPath: file.path))
        let wake = await queue.nextWake()
        XCTAssertNil(wake, "a parked item does not wake the queue")

        clock.advance(3600)
        try await drain(queue)
        XCTAssertEqual(submits().count, 2, "no loop: the parked item is not sent again")

        // The way out: retryParked puts it back.
        try await queue.retryParked()
        let retried = await queue.item("rec-5")
        XCTAssertEqual(retried?.state, .pending)
    }

    func testAnotherClientErrorParksToo() async throws {
        let queue = try makeQueue()
        try await record(queue, "rec-6")
        let client = JobsClient(baseURL: URL(string: "https://akou.example.com")!, key: "ak_wrong")
        try await queue.drain(client: client, transport: URLSessionTransport(session: FakeAkouHTTP.session()), scratch: dir.appending(path: "s"))
        let item = await queue.item("rec-6")
        XCTAssertEqual(item?.state, .parked(status: 401, code: "unauthorized"))
    }

    func testAMissingFileParksInsteadOfLooping() async throws {
        let queue = try makeQueue()
        let file = try await record(queue, "rec-7")
        try FileManager.default.removeItem(at: file)
        try await drain(queue)
        let item = await queue.item("rec-7")
        XCTAssertEqual(item?.state, .parked(status: nil, code: "local_file"))
        XCTAssertTrue(submits().isEmpty)
    }

    func testTheQueueReloadsFromDiskAndRequeuesAnInterruptedUpload() async throws {
        var queue: UploadQueue? = try makeQueue()
        try await record(queue!, "rec-8")
        try await record(queue!, "rec-9")
        let claimed = try await queue!.claimDue()
        XCTAssertEqual(claimed.map(\.recordingID), ["rec-8", "rec-9"])
        try await queue!.uploadEnded("rec-9", .unreachable("offline"))
        queue = nil // the app is killed mid-upload

        let reloaded = try makeQueue()
        let items = await reloaded.items
        XCTAssertEqual(items.map(\.recordingID), ["rec-8", "rec-9"])
        XCTAssertEqual(items.map(\.state), [.uploading, .pending])
        XCTAssertEqual(items[1].attempts, 1)
        XCTAssertEqual(items[0].submission.workspace, "Work")

        // rec-8's transfer did not survive: it goes back to pending, and the next pass sends it.
        try await reloaded.requeueInterrupted(except: [])
        clock.advance(UploadQueue.firstBackoff)
        try await drain(reloaded)
        let states = await reloaded.items.map(\.state)
        XCTAssertEqual(states, [.done(jobID: "j1", keptOnServer: true), .done(jobID: "j2", keptOnServer: true)])
    }

    func testALiveBackgroundTransferIsLeftAlone() async throws {
        let queue = try makeQueue()
        try await record(queue, "rec-10")
        _ = try await queue.claimDue()
        try await queue.requeueInterrupted(except: ["rec-10"])
        let item = await queue.item("rec-10")
        XCTAssertEqual(item?.state, .uploading)
    }

    func testALateAnswerForARequeuedUploadStillCounts() async throws {
        let queue = try makeQueue()
        try await record(queue, "rec-13")
        _ = try await queue.claimDue()
        try await queue.requeueInterrupted(except: [])
        let job = #"{"id":"j9","status":"queued","keep_audio":true}"#
        try await queue.uploadEnded("rec-13", .http(status: 202, headers: [:], body: Data(job.utf8)))
        var item = await queue.item("rec-13")
        XCTAssertEqual(item?.state, .submitted(jobID: "j9"))
        // A late failure for an item already moved on changes nothing.
        try await queue.uploadEnded("rec-13", .unreachable("offline"))
        item = await queue.item("rec-13")
        XCTAssertEqual(item?.state, .submitted(jobID: "j9"))
        XCTAssertEqual(item?.attempts, 0)
    }

    func testEnqueueingTheSameRecordingTwiceKeepsOneItem() async throws {
        let queue = try makeQueue()
        try await record(queue, "rec-11")
        try await queue.enqueue(.init(recordingID: "rec-11", title: "other"), fileName: "x.opus")
        let items = await queue.items
        XCTAssertEqual(items.count, 1)
        XCTAssertEqual(items[0].submission.title, "t")
    }

    func testBackoffDoublesAndStopsAtTheCap() async throws {
        let queue = try makeQueue()
        try await record(queue, "rec-12")
        var waits: [TimeInterval] = []
        for _ in 0..<10 {
            _ = try await queue.claimDue()
            try await queue.uploadEnded("rec-12", .unreachable("offline"))
            let item = await queue.item("rec-12")
            let wait = try XCTUnwrap(item?.notBefore).timeIntervalSince(clock.now)
            waits.append(wait)
            clock.advance(wait)
        }
        XCTAssertEqual(waits, [5, 10, 20, 40, 80, 160, 320, 640, 900, 900])
    }
}
