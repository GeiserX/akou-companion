// SPDX-License-Identifier: GPL-3.0-or-later
@testable import AkouClient
import AkouProtocol
import Foundation
import XCTest

final class JobsClientTests: XCTestCase {
    var dir: URL!

    override func setUpWithError() throws {
        FakeAkouHTTP.reset()
        dir = FileManager.default.temporaryDirectory.appending(path: "jobs-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: dir)
    }

    func client() -> JobsClient {
        JobsClient(baseURL: URL(string: "https://akou.example.com")!, key: FakeAkouHTTP.key, session: FakeAkouHTTP.session())
    }

    func testSubmitSendsTheRecordingIdAsIdempotencyKeyKeepAudioAndTheMetadata() async throws {
        let audio = dir.appending(path: "r1.opus")
        try Data("OggS fake audio".utf8).write(to: audio)
        let s = JobsClient.Submission(recordingID: "rec-1", title: "Standup", language: "es", workspace: "Work")
        let submitted = try await client().submit(s, audio: audio, bodyFile: dir.appending(path: "body"))

        XCTAssertFalse(submitted.existing)
        XCTAssertTrue(submitted.job.keepAudio)
        XCTAssertEqual(submitted.job.metadata, CompanionMetadata(recordingID: "rec-1", workspace: "Work"))
        let req = try XCTUnwrap(FakeAkouHTTP.seen.first)
        XCTAssertEqual(req.method, "POST")
        XCTAssertEqual(req.path, "/v1/jobs")
        XCTAssertEqual(req.headers["idempotency-key"], "rec-1")
        XCTAssertEqual(req.headers["authorization"], "Bearer ak_test")
        XCTAssertEqual(req.fields["keep_audio"], "true")
        XCTAssertEqual(req.fields["title"], "Standup")
        XCTAssertEqual(req.fields["language"], "es")
        XCTAssertNil(req.fields["model"])
        let meta = try JSONSerialization.jsonObject(with: Data(try XCTUnwrap(req.fields["metadata"]).utf8)) as? [String: Any]
        XCTAssertEqual(meta?["companion"] as? Int, 1)
        XCTAssertEqual(meta?["recording_id"] as? String, "rec-1")
        XCTAssertEqual(meta?["workspace"] as? String, "Work")
        XCTAssertEqual(req.fileName, "rec-1.opus")
        XCTAssertEqual(req.fileBytes, Data("OggS fake audio".utf8))
    }

    func testARepeatedSubmitGetsTheFirstJob() async throws {
        let audio = dir.appending(path: "r1.opus")
        try Data("OggS".utf8).write(to: audio)
        let s = JobsClient.Submission(recordingID: "rec-2")
        let first = try await client().submit(s, audio: audio, bodyFile: dir.appending(path: "body"))
        let again = try await client().submit(s, audio: audio, bodyFile: dir.appending(path: "body"))
        XCTAssertEqual(first.job.id, again.job.id)
        XCTAssertTrue(again.existing)
        XCTAssertEqual(FakeAkouHTTP.jobs.count, 1)
    }

    func testAQueueFullAnswerCarriesRetryAfter() async throws {
        let audio = dir.appending(path: "r1.opus")
        try Data("OggS".utf8).write(to: audio)
        FakeAkouHTTP.script(.queueFull(retryAfter: 42))
        do {
            _ = try await client().submit(.init(recordingID: "rec-3"), audio: audio, bodyFile: dir.appending(path: "body"))
            XCTFail("expected 429")
        } catch let JobsClient.Failure.status(status, body, retryAfter) {
            XCTAssertEqual(status, 429)
            XCTAssertEqual(body?.error, "queue_full")
            XCTAssertEqual(retryAfter, 42)
        }
    }

    func testDecodesAJobAResultAndAPage() throws {
        let job = try JSONDecoder().decode(Job.self, from: Data(#"{"id":"j1","title":null,"status":"done","keep_audio":true,"metadata":{"some":"other client"},"progress":{"stage":"decode"},"links":{}}"#.utf8))
        XCTAssertTrue(job.keepAudio)
        XCTAssertTrue(job.ended)
        XCTAssertNil(job.metadata, "another client's metadata is not ours")
        let noKeep = try JSONDecoder().decode(Job.self, from: Data(#"{"id":"j2","status":"queued"}"#.utf8))
        XCTAssertFalse(noKeep.keepAudio, "a job that does not say keep_audio is not kept")

        let result = try JSONDecoder().decode(JobResult.self, from: Data(#"{"text":"hola","segments":[{"s":0,"e":1.2,"text":"hola","speaker":null}],"words":[{"w":"hola","s":0.1,"e":0.5,"c":0.93},{"w":"qué","s":null,"e":null,"c":null}],"confidence":0.9,"language":"es","duration_s":1.2,"model":"x","skipped":[]}"#.utf8))
        XCTAssertEqual(result.words.count, 2)
        XCTAssertEqual(result.words[0].c, 0.93)
        XCTAssertNil(result.words[1].s)

        let page = try JSONDecoder().decode(JobPage.self, from: Data(#"{"jobs":[{"id":"j3","status":"running","keep_audio":false}],"cursor":17}"#.utf8))
        XCTAssertEqual(page.jobs.map(\.id), ["j3"])
        XCTAssertEqual(page.cursor, 17)
    }

    func testDeleteSendsTheJsonContentTypeAkouRequires() async throws {
        let audio = dir.appending(path: "r1.opus")
        try Data("OggS".utf8).write(to: audio)
        let submitted = try await client().submit(.init(recordingID: "rec-del"), audio: audio, bodyFile: dir.appending(path: "body"))
        try await client().delete(submitted.job.id)
        let req = try XCTUnwrap(FakeAkouHTTP.seen.last)
        XCTAssertEqual(req.method, "DELETE")
        XCTAssertEqual(req.path, "/v1/jobs/\(submitted.job.id)")
        XCTAssertEqual(req.headers["content-type"], "application/json")
        XCTAssertTrue(FakeAkouHTTP.jobs.isEmpty)
    }

    func testRequestsForTheOtherRoutes() throws {
        let c = client()
        XCTAssertEqual(try c.jobRequest("j1", wait: 90).url?.absoluteString, "https://akou.example.com/v1/jobs/j1?wait=60")
        XCTAssertEqual(try c.jobRequest("j1").url?.absoluteString, "https://akou.example.com/v1/jobs/j1")
        let audio = try c.audioRequest("j1", range: 100...199)
        XCTAssertEqual(audio.url?.path, "/v1/jobs/j1/audio")
        XCTAssertEqual(audio.value(forHTTPHeaderField: "Range"), "bytes=100-199")
        XCTAssertEqual(audio.value(forHTTPHeaderField: "Authorization"), "Bearer ak_test")
        XCTAssertFalse(audio.url!.absoluteString.contains("ak_test"), "the key travels in the header, never in the URL")
        // A host name over plain http is refused before any request carries the key.
        let cleartext = JobsClient(baseURL: URL(string: "http://akou.example.com")!, key: "ak_test")
        XCTAssertThrowsError(try cleartext.jobRequest("j1"))
    }
}
