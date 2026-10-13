// SPDX-License-Identifier: GPL-3.0-or-later
@testable import AkouClient
import AkouProtocol
import Foundation
import XCTest

/// Several servers on one queue: each recording goes to the server it was recorded for, an old
/// one to "Record to", and a removed server's recordings wait, parked, with their file.
final class UploadRoutingTests: XCTestCase {
    var dir: URL!
    var audioDir: URL!
    let clock = TestClock()
    let a = UUID(uuidString: "AAAAAAAA-0000-0000-0000-000000000001")!
    let b = UUID(uuidString: "BBBBBBBB-0000-0000-0000-000000000002")!
    let removed = UUID(uuidString: "CCCCCCCC-0000-0000-0000-000000000003")!

    override func setUpWithError() throws {
        FakeAkouHTTP.reset()
        dir = FileManager.default.temporaryDirectory.appending(path: "routing-\(UUID().uuidString)")
        audioDir = dir.appending(path: "audio")
        try FileManager.default.createDirectory(at: audioDir, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: dir)
    }

    func makeQueue() throws -> UploadQueue {
        let clock = self.clock
        return try UploadQueue(directory: dir.appending(path: "state"), audioDirectory: audioDir, now: { clock.now }, keepLocalCopy: { true })
    }

    @discardableResult
    func record(_ queue: UploadQueue, _ id: String, for server: UUID?) async throws -> URL {
        let file = audioDir.appending(path: "\(id).opus")
        try Data("OggS \(id)".utf8).write(to: file)
        try await queue.enqueue(.init(recordingID: id, title: id), fileName: "\(id).opus", serverID: server)
        return file
    }

    /// Two servers on the fake, told apart by host: a.example.com and b.example.com.
    func clients(_ ids: [UUID]) -> [UUID: JobsClient] {
        var out: [UUID: JobsClient] = [:]
        for id in ids {
            let host = id == a ? "a" : id == b ? "b" : "c"
            out[id] = JobsClient(baseURL: URL(string: "https://\(host).example.com")!, key: FakeAkouHTTP.key)
        }
        return out
    }

    func drain(_ queue: UploadQueue, servers: [UUID], recordTo: UUID?) async throws {
        try await queue.drain(clients: clients(servers), recordTo: recordTo, transport: URLSessionTransport(session: FakeAkouHTTP.session()), scratch: dir.appending(path: "scratch"))
    }

    /// Who was asked what: "a POST /v1/jobs", "b GET /v1/jobs/j2".
    func asked() -> [String] { FakeAkouHTTP.seen.map { "\($0.host?.prefix(1) ?? "?") \($0.method) \($0.path)" } }

    func testEachRecordingGoesToTheServerItWasRecordedFor() async throws {
        let queue = try makeQueue()
        try await record(queue, "r1", for: a)
        try await record(queue, "r2", for: b)
        try await drain(queue, servers: [a, b], recordTo: a)
        XCTAssertEqual(asked(), ["a POST /v1/jobs", "b POST /v1/jobs", "a GET /v1/jobs/j1", "b GET /v1/jobs/j2"])
        let items = await queue.items
        XCTAssertEqual(items.map(\.state), [.done(jobID: "j1", keptOnServer: true), .done(jobID: "j2", keptOnServer: true)])
        XCTAssertEqual(items.map(\.serverID), [a, b])
    }

    /// A queue file written before several servers: its items name no server. They go to "Record
    /// to", which is written into them, so moving "Record to" later never moves them.
    func testAnOldRecordingGoesToRecordToAndStaysWithIt() async throws {
        let stateDir = dir.appending(path: "state")
        try FileManager.default.createDirectory(at: stateDir, withIntermediateDirectories: true)
        try Data("OggS old".utf8).write(to: audioDir.appending(path: "old.opus"))
        // The exact shape the queue wrote before this change: no serverID.
        let old = #"[{"submission":{"recordingID":"old","keepAudio":true},"fileName":"old.opus","state":{"pending":{}},"attempts":0}]"#
        try Data(old.utf8).write(to: stateDir.appending(path: "uploads.json"))
        let queue = try makeQueue()
        let loaded = await queue.items
        XCTAssertEqual(loaded.map(\.recordingID), ["old"])
        XCTAssertNil(loaded.first?.serverID)

        // Uploaded to Record to (b), but not read back yet: the confirm waits for a backoff.
        FakeAkouHTTP.script(.acceptThenDrop)
        try await drain(queue, servers: [a, b], recordTo: b)
        XCTAssertEqual(asked(), ["b POST /v1/jobs"])
        do {
            let it_old = await queue.item("old")
            XCTAssertEqual(it_old?.serverID, b, "Record to is written into the item")
        }

        // Record to moves to a; the retry and the read-back still go to b, where the job is.
        clock.advance(60)
        try await drain(queue, servers: [a, b], recordTo: a)
        XCTAssertEqual(asked(), ["b POST /v1/jobs", "b POST /v1/jobs", "b GET /v1/jobs/j1"])
        do {
            let it_old = await queue.item("old")
            XCTAssertEqual(it_old?.state, .done(jobID: "j1", keptOnServer: true))
        }
    }

    func testARemovedServersRecordingWaitsWithItsFileUntilSentElsewhere() async throws {
        let queue = try makeQueue()
        let file = try await record(queue, "r1", for: removed)
        try await drain(queue, servers: [a, b], recordTo: a)
        XCTAssertTrue(asked().isEmpty, "nothing is sent anywhere on a guess")
        let maybeParked = await queue.item("r1")
        let parked = try XCTUnwrap(maybeParked)
        XCTAssertEqual(parked.state, .parked(status: nil, code: UploadQueue.serverRemovedCode))
        XCTAssertTrue(parked.waitsForAServer)
        XCTAssertEqual(parked.lastError, "The server this recording was made for was removed. Send it to another server.")
        XCTAssertTrue(FileManager.default.fileExists(atPath: file.path), "the recording is never dropped")

        // A changed key retries what a server refused, but this one has no server to retry on.
        try await queue.retryParked()
        do {
            let it_r1 = await queue.item("r1")
            XCTAssertEqual(it_r1?.state, .parked(status: nil, code: UploadQueue.serverRemovedCode))
        }

        try await queue.reassign("r1", to: b)
        try await drain(queue, servers: [a, b], recordTo: a)
        XCTAssertEqual(asked(), ["b POST /v1/jobs", "b GET /v1/jobs/j1"])
        do {
            let it_r1 = await queue.item("r1")
            XCTAssertEqual(it_r1?.state, .done(jobID: "j1", keptOnServer: true))
        }
    }

    /// Uploaded, then its server removed before the read-back: parked, file kept, so it can go elsewhere.
    func testAServerRemovedBeforeTheReadBackParksTheRecording() async throws {
        let queue = try makeQueue()
        let file = try await record(queue, "r1", for: a)
        _ = try await queue.claimDue(.init(servers: [a], recordTo: a))
        try await queue.uploadEnded("r1", .http(status: 202, headers: [:], body: Data(#"{"id":"j9","status":"queued","keep_audio":true}"#.utf8)))
        try await drain(queue, servers: [b], recordTo: b)
        XCTAssertTrue(asked().isEmpty)
        do {
            let it_r1 = await queue.item("r1")
            XCTAssertTrue(it_r1?.waitsForAServer ?? false)
        }
        XCTAssertTrue(FileManager.default.fileExists(atPath: file.path))
    }

    /// A server that exists but has no key yet: its recordings wait for it, neither sent elsewhere nor parked.
    func testAServerWithoutItsKeyYetKeepsItsRecordingsWaiting() async throws {
        let queue = try makeQueue()
        try await record(queue, "r1", for: b)
        let claimed = try await queue.claimDue(.init(servers: [a, b], recordTo: a, ready: [a]))
        XCTAssertTrue(claimed.isEmpty)
        do {
            let it = await queue.item("r1")
            XCTAssertEqual(it?.state, .pending)
            XCTAssertEqual(it?.serverID, b)
        }
        try await drain(queue, servers: [a, b], recordTo: a)
        XCTAssertEqual(asked(), ["b POST /v1/jobs", "b GET /v1/jobs/j1"])
    }

    /// No server at all yet (a fresh install): recordings wait, pending, and nothing is parked.
    func testWithNoServerYetRecordingsWait() async throws {
        let queue = try makeQueue()
        try await record(queue, "r1", for: nil)
        try await drain(queue, servers: [], recordTo: nil)
        XCTAssertTrue(asked().isEmpty)
        do {
            let it_r1 = await queue.item("r1")
            XCTAssertEqual(it_r1?.state, .pending)
        }
    }
}
