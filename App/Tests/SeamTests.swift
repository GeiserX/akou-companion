// SPDX-License-Identifier: GPL-3.0-or-later
import AkouClient
import AkouProtocol
import XCTest
@testable import akou

/// The places where the recorder, the intents, the uploader, the Library and the widgets meet.
/// They run inside the app, so `AkouCompanionApp.init` has already connected them.
@MainActor
final class SeamTests: XCTestCase {
    private var savedKey: String?
    private var savedURL: String?
    private var savedWorkspace: String?

    override func setUp() async throws {
        savedKey = KeyStore.load()
        savedURL = UserDefaults.standard.string(forKey: ServerSettings.serverURL)
        savedWorkspace = UserDefaults.standard.string(forKey: ServerSettings.defaultWorkspace)
    }

    override func tearDown() async throws {
        await RecordingController.shared.stop()
        if let savedKey { KeyStore.save(savedKey) } else { KeyStore.delete() }
        UserDefaults.standard.set(savedURL, forKey: ServerSettings.serverURL)
        UserDefaults.standard.set(savedWorkspace, forKey: ServerSettings.defaultWorkspace)
    }

    func testLaunchHandsTheRecorderToTheRecordIntents() {
        XCTAssertTrue(RecordIntentHost.recorder === RecordingController.shared)
    }

    func testTheRecorderReadsTheKeyTheSettingsKeepInTheKeychain() {
        XCTAssertTrue(KeyStore.save("ak_wiring_test"), "the Keychain refused the key")
        XCTAssertEqual(RecordingController.shared.serverKey(), "ak_wiring_test")
        let stored = UserDefaults.standard.dictionaryRepresentation().values.compactMap { $0 as? String }
        XCTAssertFalse(stored.contains { $0.contains("ak_wiring_test") }, "the key is never in UserDefaults")
        KeyStore.delete()
        XCTAssertNil(RecordingController.shared.serverKey())
    }

    func testEveryStartAndStopReachesTheRecordControlAndTheUploadQueue() async throws {
        let recorder = RecordingController.shared
        KeyStore.delete() // no key: no live text, and the upload waits in the queue
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

        // The record screen path, straight to the controller, in the workspace picked there.
        try await recorder.start(workspace: "screen", title: title + "-screen")
        XCTAssertTrue(RecordingStatus.isRecording)
        recorder.pause()
        XCTAssertTrue(RecordingStatus.isRecording, "paused is still a recording")
        await recorder.stop()
        XCTAssertFalse(RecordingStatus.isRecording)
        let screen = try await waitForQueued(title: title + "-screen")
        XCTAssertEqual(screen.submission.workspace, "screen", "the upload keeps the workspace the recording was made in")
    }

    func testLinksOpenTheirTab() {
        XCTAssertEqual(AkouCompanionApp.screen(for: URL(string: "akou-companion://record")!), .record)
        LibraryRouter.shared.path = []
        XCTAssertEqual(AkouCompanionApp.screen(for: URL(string: "akou-companion://recording/job_01ABC")!), .library)
        XCTAssertEqual(LibraryRouter.shared.path, ["job_01ABC"])
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
