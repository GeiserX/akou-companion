// SPDX-License-Identifier: GPL-3.0-or-later
import AkouClient
import AkouProtocol
import XCTest
@testable import akou

/// The demo, as the app runs it: its server inside the app with the bundled samples, live text from
/// the script, recordings that stay in the demo, and the way out.
@MainActor
final class DemoModeTests: XCTestCase {
    private var savedBook: String?
    private var savedWelcomed: Any?

    override func setUp() async throws {
        savedBook = UserDefaults.standard.string(forKey: ServerBook.defaultsKey)
        savedWelcomed = UserDefaults.standard.object(forKey: DemoMode.welcomedKey)
        ServerSettings.save(ServerBook())
    }

    override func tearDown() async throws {
        await RecordingController.shared.stop()
        DemoMode.leave()
        UserDefaults.standard.set(savedBook, forKey: ServerBook.defaultsKey)
        UserDefaults.standard.set(savedWelcomed, forKey: DemoMode.welcomedKey)
    }

    func testTheDemoIsAServerInsideTheAppWithTheSamplesAndBecomesRecordTo() async throws {
        DemoMode.enter()
        let id = try XCTUnwrap(DemoMode.serverID)
        XCTAssertEqual(ServerSettings.book.recordTo, id)
        XCTAssertEqual(KeyStore.key(id), DemoServer.key)
        XCTAssertEqual(RecordingController.shared.recordTarget()?.url, DemoServer.baseURL)
        XCTAssertTrue(UserDefaults.standard.bool(forKey: DemoMode.welcomedKey))

        // The samples ship in the app: three readings, each with its audio file.
        XCTAssertEqual(Set(DemoServer.recordings.map(\.id)), ["demo_gettysburg_1", "demo_gettysburg_2", "demo_declaration"])
        for r in DemoServer.recordings { XCTAssertTrue(FileManager.default.fileExists(atPath: r.audio.path), r.id) }

        // The Library's own client reads them, with timed words.
        let client = try XCTUnwrap(ServerSettings.jobsClient(id))
        let page = try await client.list()
        XCTAssertEqual(page.jobs.count, 3)
        XCTAssertTrue(page.jobs.allSatisfy(LibraryStore.isCompanion))
        let result = try await client.result("demo_gettysburg_1")
        XCTAssertTrue(result.text.hasPrefix("Four score and seven years ago"))
        XCTAssertTrue(result.words.allSatisfy { $0.s != nil })
        let audio = try client.audioRequest("demo_declaration", range: 0...3)
        let (head, _) = try await AkouSession.shared.data(for: audio)
        XCTAssertEqual(head, Data("OggS".utf8), "the sample is an Ogg file")
    }

    func testARecordingInTheDemoHasTheScriptAsLiveTextAndStaysInTheDemo() async throws {
        DemoMode.enter()
        let recorder = RecordingController.shared
        recorder.serverSupportsLive = nil
        recorder.requestPermission = { true }
        var source: FileSampleSource?
        recorder.makeSource = { sink in
            let s = FileSampleSource(samples: [Float](repeating: 0, count: 3 * 16000), sink: sink)
            source = s
            return s
        }
        let title = "demo-\(UUID().uuidString)"
        try await recorder.start(workspace: nil, title: title)
        let deadline = Date().addingTimeInterval(10)
        while source?.finished != true && Date() < deadline { try await Task.sleep(for: .milliseconds(100)) }
        let stopped = await recorder.stop()
        let finished = try XCTUnwrap(stopped)

        let lines = recorder.transcript.items.compactMap { if case let .line(l) = $0 { l.text } else { nil } }
        XCTAssertEqual(lines.first, "This is the akou demo.", "the script came as live text")
        let kept = try XCTUnwrap(DemoServer.recordings.first { $0.title == title }, "the recording became a demo job")
        XCTAssertEqual(kept.id, "demo_\(finished.id.uuidString.lowercased())")
        XCTAssertTrue(FileManager.default.fileExists(atPath: kept.audio.path))
        let result = try JSONDecoder().decode(JobResult.self, from: kept.result)
        XCTAssertEqual(result.words.map(\.w), DemoLiveText.words(upTo: finished.duration).map(\.w))
        XCTAssertFalse(result.words.isEmpty)
        let queued = await BackgroundUploader.shared.items()
        XCTAssertFalse(queued.contains { $0.submission.title == title }, "nothing made in the demo is uploaded")
    }

    func testLeavingTheDemoRemovesItsServerItsKeyAndItsRecordings() throws {
        DemoMode.enter()
        let id = try XCTUnwrap(DemoMode.serverID)
        DemoMode.leave()
        XCTAssertFalse(DemoMode.isOn)
        XCTAssertNil(KeyStore.key(id))
        XCTAssertTrue(DemoServer.recordings.isEmpty)
        XCTAssertNil(ServerSettings.book.recordTo)
        XCTAssertFalse(UserDefaults.standard.bool(forKey: DemoMode.welcomedKey), "with no server left, the first screen offers pairing again")
        XCTAssertFalse(FileManager.default.fileExists(atPath: DemoMode.directory.path))
    }

    func testLeavingTheDemoKeepsTheOtherServersAndTheFirstScreenStaysAnswered() throws {
        let home = ServerEntry(name: "home", url: "https://home.example.com")
        ServerSettings.save(ServerBook(servers: [home], recordTo: home.id))
        DemoMode.enter()
        XCTAssertEqual(ServerSettings.book.recordTo, DemoMode.serverID)
        DemoMode.leave()
        XCTAssertEqual(ServerSettings.book.servers.map(\.id), [home.id])
        XCTAssertEqual(ServerSettings.book.recordTo, home.id)
        XCTAssertTrue(UserDefaults.standard.bool(forKey: DemoMode.welcomedKey))
    }
}
