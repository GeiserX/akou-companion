// SPDX-License-Identifier: GPL-3.0-or-later
import AkouClient
import AkouProtocol
import XCTest
@testable import akou

/// The places where the recorder, the intents, the uploader, the Library and the widgets meet.
/// They run inside the app, so `AkouCompanionApp.init` has already connected them.
@MainActor
final class SeamTests: XCTestCase {
    private var savedBook: String?
    private var savedWorkspace: String?
    /// Servers a test made, whose keys go when it ends.
    private var made: [UUID] = []

    override func setUp() async throws {
        savedBook = UserDefaults.standard.string(forKey: ServerBook.defaultsKey)
        savedWorkspace = UserDefaults.standard.string(forKey: ServerSettings.defaultWorkspace)
    }

    override func tearDown() async throws {
        await RecordingController.shared.stop()
        for id in made { KeyStore.delete(server: id) }
        UserDefaults.standard.set(savedBook, forKey: ServerBook.defaultsKey)
        UserDefaults.standard.set(savedWorkspace, forKey: ServerSettings.defaultWorkspace)
    }

    /// Sets the server list to `servers`, the first one "Record to", each with its key when given.
    private func useServers(_ servers: [(ServerEntry, String?)]) {
        for (s, key) in servers {
            made.append(s.id)
            if let key { XCTAssertTrue(KeyStore.save(key, server: s.id), "the Keychain refused the key") } else { KeyStore.delete(server: s.id) }
        }
        ServerSettings.save(ServerBook(servers: servers.map(\.0), recordTo: servers.first?.0.id))
    }

    func testLaunchHandsTheRecorderToTheRecordIntents() {
        XCTAssertTrue(RecordIntentHost.recorder === RecordingController.shared)
    }

    func testTheRecorderUsesRecordToAndItsKeyFromTheKeychain() {
        let home = ServerEntry(name: "home", url: "https://home.example.com")
        let office = ServerEntry(name: "office", url: "https://office.example.com")
        useServers([(home, "ak_wiring_home"), (office, "ak_wiring_office")])
        XCTAssertEqual(RecordingController.shared.recordTarget(), RecordTarget(id: home.id, url: URL(string: "https://home.example.com")!, key: "ak_wiring_home"))
        var book = ServerSettings.book
        book.recordTo = office.id
        ServerSettings.save(book)
        XCTAssertEqual(RecordingController.shared.recordTarget()?.key, "ak_wiring_office", "each server has its own Keychain entry")
        let stored = UserDefaults.standard.dictionaryRepresentation().values.compactMap { $0 as? String }
        XCTAssertFalse(stored.contains { $0.contains("ak_wiring_") }, "a key is never in UserDefaults")
        KeyStore.delete(server: office.id)
        XCTAssertNil(RecordingController.shared.recordTarget(), "no key: no live text")
        XCTAssertEqual(RecordingController.shared.recordToID(), office.id, "but the recording is still for Record to")
    }

    /// The single server of earlier versions, as the Keychain and UserDefaults held it, becomes
    /// the first server on the real Keychain.
    func testTheOldSingleServerMovesIntoTheKeychainUnderItsNewId() throws {
        let defaults = UserDefaults.standard
        defaults.removeObject(forKey: ServerBook.defaultsKey)
        defaults.set("https://old.example.com", forKey: ServerMigration.legacyURLKey)
        XCTAssertTrue(KeyStore.shared.save("ak_wiring_old", account: ServerMigration.legacyAccount))
        let book = ServerSettings.book
        made += book.servers.map(\.id)
        let server = try XCTUnwrap(book.recordToServer)
        XCTAssertEqual(server.name, "old.example.com")
        XCTAssertEqual(KeyStore.key(server.id), "ak_wiring_old")
        XCTAssertNil(KeyStore.shared.load(account: ServerMigration.legacyAccount), "the old entry is gone")
        XCTAssertNil(defaults.object(forKey: ServerMigration.legacyURLKey))
    }

    func testEveryStartAndStopReachesTheRecordControlAndTheUploadQueue() async throws {
        let recorder = RecordingController.shared
        // "Record to" without a key: no live text, and the upload waits in the queue for it.
        let server = ServerEntry(name: "seam", url: "https://seam.example.com")
        useServers([(server, nil)])
        UserDefaults.standard.set("intent-default", forKey: ServerSettings.defaultWorkspace)
        recorder.requestPermission = { true }
        recorder.makeSource = { FileSampleSource(samples: [Float](repeating: 0, count: 16000), sink: $0) }
        RecordingStatus.set(recording: false)
        let title = "seam-\(UUID().uuidString)"

        // The Action button and Control Center path.
        try await RecordIntentHost.start(title: title)
        XCTAssertTrue(RecordingStatus.isRecording)
        XCTAssertEqual(recorder.liveState, .off(reason: "no_server"))
        XCTAssertFalse(recorder.activity.content.liveText, "the Live Activity must say there is no live text")
        try await Task.sleep(for: .milliseconds(400))
        try await RecordIntentHost.stop()
        XCTAssertFalse(RecordingStatus.isRecording)
        let queued = try await waitForQueued(title: title)
        XCTAssertEqual(queued.submission.workspace, "intent-default", "an intent records into the settings' workspace")
        XCTAssertTrue(queued.submission.keepAudio)
        XCTAssertEqual(queued.serverID, server.id, "the recording is queued for the server it was recorded for")

        // The record screen path, straight to the controller, in the workspace picked there.
        try await recorder.start(workspace: "screen", title: title + "-screen")
        XCTAssertTrue(RecordingStatus.isRecording)
        recorder.pause()
        XCTAssertTrue(RecordingStatus.isRecording, "paused is still a recording")
        await recorder.stop()
        XCTAssertFalse(RecordingStatus.isRecording)
        let screen = try await waitForQueued(title: title + "-screen")
        XCTAssertEqual(screen.submission.workspace, "screen", "the upload keeps the workspace the recording was made in")
        XCTAssertEqual(screen.serverID, server.id)
    }

    func testLinksOpenTheirTab() {
        XCTAssertEqual(AkouCompanionApp.screen(for: URL(string: "akou-companion://record")!), .record)
        LibraryRouter.shared.path = []
        let other = ServerEntry(name: "other", url: "https://other.example.com")
        let recordTo = ServerEntry(name: "rec", url: "https://rec.example.com")
        useServers([(recordTo, nil), (other, nil)])
        LibraryRouter.shared.server = other.id
        XCTAssertEqual(AkouCompanionApp.screen(for: URL(string: "akou-companion://recording/job_01ABC")!), .library)
        XCTAssertEqual(LibraryRouter.shared.path, [.job("job_01ABC")])
        XCTAssertEqual(LibraryRouter.shared.server, recordTo.id, "the widget shows Record to, so its link opens Record to")
        XCTAssertNil(AkouCompanionApp.screen(for: URL(string: "akou-companion://recording/")!))
        XCTAssertNil(AkouCompanionApp.screen(for: URL(string: "https://example.com/record")!))
    }

    func testTheLiveActivityStateTellsLiveTextOffFromNoLineYet() throws {
        let off = RecordingAttributes.ContentState(startedAt: .now, paused: false, lastLine: "", liveText: false)
        let json = try JSONEncoder().encode(off)
        XCTAssertEqual(try JSONDecoder().decode(RecordingAttributes.ContentState.self, from: json), off)
        XCTAssertNotEqual(off, RecordingAttributes.ContentState(startedAt: off.startedAt, paused: false, lastLine: "", liveText: true))
    }

    func testTheLibraryKeepsTheNewestFiveWithTheirOpenings() throws {
        let jobs = try (1...7).map(Self.job)
        // Kept in memory: the newest five, each opening cut by TranscriptDigest.
        let long = String(repeating: "A sentence that goes on. ", count: 60)
        let snapshot = RecentSnapshot(recordings: jobs.map {
            RecentRecording(jobId: $0.id, title: $0.title ?? "", workspace: nil, createdAt: LibraryStore.date($0.createdAt)!, duration: nil, status: $0.status, opening: long)
        })
        XCTAssertEqual(snapshot.recordings.map(\.jobId), ["job_7", "job_6", "job_5", "job_4", "job_3"])
        XCTAssertEqual(snapshot.recordings[0].opening, TranscriptDigest.opening(text: long, limit: RecentSnapshot.openingLimit))

        // Written by the Library for the widget, which needs the App Group (a signed build).
        try XCTSkipIf(AppGroup.container == nil, "no App Group container in an unsigned build")
        SnapshotWriter.listRefreshed(jobs.shuffled())
        XCTAssertEqual(RecentSnapshot.read()?.recordings.map(\.jobId), ["job_7", "job_6", "job_5", "job_4", "job_3"])
        let result = try JSONDecoder().decode(JobResult.self, from: Data(#"{"text":"First things first. Then the rest.","words":[],"duration_s":4.5}"#.utf8))
        SnapshotWriter.transcriptLoaded(job: jobs[6], result: result)
        let first = try XCTUnwrap(RecentSnapshot.read()?.recordings.first)
        XCTAssertEqual(first.jobId, "job_7")
        XCTAssertEqual(first.opening, TranscriptDigest.opening(text: result.text, words: [], limit: RecentSnapshot.openingLimit))
        XCTAssertEqual(first.duration, 4.5)
    }

    static func job(_ i: Int) throws -> Job {
        let json = #"{"id":"job_\#(i)","title":"Recording \#(i)","status":"done","created_at":"2026-10-0\#(i)T08:00:00.000Z","keep_audio":true,"metadata":{"companion":1,"recording_id":"r\#(i)","workspace":"w"}}"#
        return try JSONDecoder().decode(Job.self, from: Data(json.utf8))
    }

    private func waitForQueued(title: String) async throws -> UploadQueue.Item {
        let deadline = Date().addingTimeInterval(10)
        while Date() < deadline {
            if let item = await BackgroundUploader.shared.items().first(where: { $0.submission.title == title }) { return item }
            try await Task.sleep(for: .milliseconds(100))
        }
        XCTFail("\(title) never reached the upload queue")
        throw CancellationError()
    }
}
