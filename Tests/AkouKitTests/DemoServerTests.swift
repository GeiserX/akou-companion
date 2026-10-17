// SPDX-License-Identifier: GPL-3.0-or-later
@testable import AkouClient
import AkouProtocol
import Foundation
import XCTest

/// The demo's in-app server, reached by the real clients through the session every request uses.
final class DemoServerTests: XCTestCase {
    var dir: URL!
    var audio: URL!

    override func setUpWithError() throws {
        dir = FileManager.default.temporaryDirectory.appending(path: "demo-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        audio = dir.appending(path: "a.opus")
        try Data((0..<200).map { UInt8($0) }).write(to: audio)
        DemoServer.set([
            .init(id: "demo_old", title: "Old", workspace: "Speeches", createdAt: Date(timeIntervalSince1970: 1_000), audio: audio,
                  result: DemoServer.result(words: [("Four", 0.5, 0.8), ("score.", 0.9, 1.2)])),
            .init(id: "demo_new", title: "New", workspace: nil, createdAt: Date(timeIntervalSince1970: 2_000), audio: audio,
                  result: DemoServer.result(words: [("Hello.", 0.5, 0.9)])),
        ])
    }

    override func tearDownWithError() throws {
        DemoServer.set([])
        try? FileManager.default.removeItem(at: dir)
    }

    private var jobs: JobsClient { JobsClient(baseURL: DemoServer.baseURL, key: DemoServer.key) }

    func testTheProbeSeesAnAkouServerWithLiveTextAndAKeyThatOnlyUploads() async throws {
        let probe = ServerProbe(baseURL: DemoServer.baseURL, key: DemoServer.key)
        let info = try await probe.server()
        XCTAssertTrue(info.supportsLive)
        XCTAssertEqual(info.liveEngines, [DemoServer.engine])
        XCTAssertEqual(info.mode, "server")
        let me = try await probe.keyInfo()
        XCTAssertEqual(me.scopes, ["jobs"])
    }

    func testAnotherKeyIsRefused() async {
        do {
            _ = try await ServerProbe(baseURL: DemoServer.baseURL, key: "ak_other").keyInfo()
            XCTFail("the demo answered a key that is not its own")
        } catch {
            XCTAssertEqual(error as? ServerProbe.Failure, .status(401, code: "unauthorized"))
        }
    }

    func testTheListIsNewestFirstAndEveryJobIsTheCompanionsWithItsTranscript() async throws {
        let page = try await jobs.list()
        XCTAssertEqual(page.jobs.map(\.id), ["demo_new", "demo_old"])
        XCTAssertNil(page.cursor)
        let old = try XCTUnwrap(page.jobs.last)
        XCTAssertEqual(old.status, "done")
        XCTAssertTrue(old.keepAudio)
        XCTAssertEqual(old.metadata, CompanionMetadata(recordingID: "demo_old", workspace: "Speeches"))
        let result = try await jobs.result("demo_old")
        XCTAssertEqual(result.text, "Four score.")
        XCTAssertEqual(result.words.map(\.s), [0.5, 0.9])
        XCTAssertEqual(result.segments.count, 1)
        XCTAssertEqual(result.durationS, 1.2)
    }

    func testRenameAndDeleteChangeWhatTheListShows() async throws {
        let renamed = try await jobs.rename("demo_old", title: "Renamed")
        XCTAssertEqual(renamed.title, "Renamed")
        try await jobs.delete("demo_new")
        let page = try await jobs.list()
        XCTAssertEqual(page.jobs.map(\.title), ["Renamed"])
        do {
            _ = try await jobs.job("demo_new")
            XCTFail("a deleted job still answered")
        } catch let JobsClient.Failure.status(status, _, _) {
            XCTAssertEqual(status, 404)
        }
    }

    func testTheAudioAnswersAByteRangeAsAkouDoes() async throws {
        let req = try jobs.audioRequest("demo_old", range: 10...19)
        let (body, response) = try await AkouSession.shared.data(for: req)
        let http = try XCTUnwrap(response as? HTTPURLResponse)
        XCTAssertEqual(http.statusCode, 206)
        XCTAssertEqual(http.value(forHTTPHeaderField: "Content-Range"), "bytes 10-19/200")
        XCTAssertEqual(http.value(forHTTPHeaderField: "Content-Type"), "audio/ogg")
        XCTAssertEqual(body, Data((10..<20).map { UInt8($0) }))
        XCTAssertEqual(DemoServer.range("bytes=190-", total: 200)?.1, 199)
        XCTAssertEqual(DemoServer.range("bytes=-5", total: 200)?.0, 195)
        XCTAssertNil(DemoServer.range("bytes=300-", total: 200))
    }

    func testOnlyTheDemoHostIsTakenEveryOtherRequestGoesBy() {
        XCTAssertTrue(DemoServer.canInit(with: URLRequest(url: DemoServer.baseURL.appending(path: "v1/server"))))
        XCTAssertFalse(DemoServer.canInit(with: URLRequest(url: URL(string: "https://akou.example.com/v1/server")!)))
        XCTAssertFalse(DemoServer.canInit(with: URLRequest(url: URL(string: "http://192.168.1.2:8476/v1/jobs")!)))
        XCTAssertTrue(AkouSession.shared.configuration.protocolClasses?.first == DemoServer.self)
    }

    func testDemoLiveTextFollowsTheRecordingsTimeAndEndsOnStop() async throws {
        let live = DemoLiveText()
        await live.start()
        await live.send(page: Data(), endsAt: 1.0)
        await live.send(page: Data(), endsAt: 1.0)
        await live.send(page: Data(), endsAt: 2.2)
        await live.stop()
        var events: [LiveSession.Event] = []
        for await e in live.events { events.append(e) }
        XCTAssertEqual(events.first, .state(.live(engine: DemoServer.engine)))
        XCTAssertEqual(events.last, .state(.off(reason: "stopped")))
        let tokens: [LiveToken] = events.flatMap { event -> [LiveToken] in
            if case let .words(w) = event { return w.tokens }
            return []
        }
        // Words start at 0.5 s and every 0.4 s after: 0.5, 0.9 by 1.0 s; 1.3, 1.7, 2.1 by 2.2 s.
        XCTAssertEqual(tokens.map(\.text), [" This", " is", " the", " akou", " demo."])
        for (t, want) in zip(tokens.map(\.t), [0.5, 0.9, 1.3, 1.7, 2.1]) { XCTAssertEqual(t, want, accuracy: 1e-9) }
        var transcript = LiveTranscript()
        for t in tokens { transcript.append(t) }
        transcript.close()
        XCTAssertEqual(transcript.lastClosedLine, "This is the akou demo.")
        XCTAssertEqual(DemoLiveText.words(upTo: 2.1).map(\.w), ["This", "is", "the", "akou"], "a word counts once it has ended")
    }
}
