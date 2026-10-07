// SPDX-License-Identifier: GPL-3.0-or-later
import AVFoundation
import AkouClient
import AkouProtocol
import XCTest
@testable import akou

/// One recording through the whole app against a real akou server: the record intent starts it,
/// live words arrive, it stops, the background upload sends it with `keep_audio`, the Library
/// lists it, the player plays the server's audio, and delete removes it.
///
/// Runs only when given a server, through xcodebuild's `TEST_RUNNER_` prefix:
///   TEST_RUNNER_AKOU_E2E_URL       the server, e.g. http://127.0.0.1:18477
///   TEST_RUNNER_AKOU_E2E_KEY_FILE  a file holding the `ak_` key (read here, never printed)
///   TEST_RUNNER_AKOU_E2E_PCM       speech as raw f32le 16 kHz mono
/// Mute the Mac's output first: the player plays for a second.
@MainActor
final class EndToEndTests: XCTestCase {
    func testARecordingGoesFromTheIntentToTheLibraryAndOut() async throws {
        let env = ProcessInfo.processInfo.environment
        guard let raw = env["AKOU_E2E_URL"], let keyFile = env["AKOU_E2E_KEY_FILE"], let pcm = env["AKOU_E2E_PCM"] else {
            throw XCTSkip("no AKOU_E2E_URL, AKOU_E2E_KEY_FILE, AKOU_E2E_PCM")
        }
        let text = try String(contentsOfFile: keyFile, encoding: .utf8)
        let key = try XCTUnwrap(text.split(whereSeparator: { $0.isWhitespace }).first { $0.hasPrefix("ak_") }.map(String.init))
        let samples = try FileSampleSource.samples(contentsOf: URL(fileURLWithPath: pcm))
        XCTAssertGreaterThan(samples.count, 16000 * 3, "the speech fixture is too short")

        // The settings screen's two stores: the URL in UserDefaults, the key in the Keychain.
        let defaults = UserDefaults.standard
        let saved = [ServerSettings.serverURL, ServerSettings.defaultWorkspace, ServerSettings.keepLocalCopy].map { ($0, defaults.object(forKey: $0)) }
        let savedKey = KeyStore.load()
        addTeardownBlock { @MainActor in
            for (k, v) in saved { defaults.set(v, forKey: k) }
            if let savedKey { KeyStore.save(savedKey) } else { KeyStore.delete() }
        }
        defaults.set(raw, forKey: ServerSettings.serverURL)
        defaults.set("e2e", forKey: ServerSettings.defaultWorkspace)
        defaults.set(false, forKey: ServerSettings.keepLocalCopy)
        XCTAssertTrue(KeyStore.save(key))
        await BackgroundUploader.shared.settingsChanged()

        let recorder = RecordingController.shared
        var source: FileSampleSource?
        recorder.requestPermission = { true }
        recorder.makeSource = { sink in
            let s = FileSampleSource(samples: samples, sink: sink)
            source = s
            return s
        }
        recorder.serverSupportsLive = nil
        let title = "integration e2e \(UUID().uuidString.prefix(8))"

        // 1. Start from the record intent, as the Action button does.
        try await RecordIntentHost.start(title: title)
        XCTAssertTrue(RecordingStatus.isRecording)
        XCTAssertTrue(recorder.activity.content.liveText)

        // 2. Live words while the fixture plays.
        try await waitUntil(seconds: Double(samples.count) / 16000 + 60) { source?.finished == true }
        try await waitUntil(seconds: 20) { !recorder.transcript.items.isEmpty || recorder.transcript.openLine != nil }
        let live = recorder.transcript.items.compactMap { if case let .line(l) = $0 { l.text } else { nil } } + [recorder.transcript.openLine?.text].compactMap { $0 }
        print("E2E live state \(recorder.liveState), live text: \(live.joined(separator: " | "))")
        XCTAssertFalse(live.joined().trimmingCharacters(in: .whitespaces).isEmpty, "no live words")

        // 3. Stop from the intent: the file finishes and goes to the upload queue.
        try await RecordIntentHost.stop()
        XCTAssertFalse(RecordingStatus.isRecording)

        // 4. The background upload lands; the Library lists the job, kept, in its workspace.
        let client = try XCTUnwrap(LibraryStore.client())
        var job: Job?
        try await waitUntil(seconds: 180) {
            await LibraryStore.shared.refresh()
            job = LibraryStore.shared.jobs.first { $0.title == title }
            return job != nil
        }
        var listed = try XCTUnwrap(job)
        print("E2E listed job \(listed.id) keep_audio=\(listed.keepAudio) workspace=\(listed.metadata?.workspace ?? "nil")")
        XCTAssertTrue(listed.keepAudio, "uploaded without keep_audio")
        XCTAssertEqual(listed.metadata?.workspace, "e2e")
        while !listed.ended { listed = try await client.job(listed.id, wait: 30) }
        XCTAssertEqual(listed.status, "done", listed.error ?? "")
        let result = try await client.result(listed.id)
        print("E2E final transcript: \(result.text)")
        XCTAssertFalse(result.text.isEmpty)
        if AppGroup.container != nil {
            XCTAssertEqual(RecentSnapshot.read()?.recordings.first?.jobId, listed.id)
            SnapshotWriter.transcriptLoaded(job: listed, result: result)
            XCTAssertFalse(RecentSnapshot.read()?.recordings.first?.opening.isEmpty ?? true)
        }

        // 5. The queue reads back keep_audio and drops the phone's copy; the player then plays the
        // server's audio, as the detail screen does.
        let recordingID = try XCTUnwrap(listed.metadata?.recordingID)
        try await waitUntil(seconds: 60) {
            await BackgroundUploader.shared.pump()
            let item = await BackgroundUploader.shared.items().first { $0.recordingID == recordingID }
            if case .done(_, true) = item?.state { return true }
            return false
        }
        let player = RecordingPlayer()
        player.load(jobId: listed.id, keptOnServer: listed.keepAudio, local: await LibraryStore.shared.localAudio(for: listed), client: client)
        XCTAssertEqual(player.source, .server, "the phone kept no copy, so the audio comes from the server")
        try await waitUntil(seconds: 30) { player.duration != nil || player.problem != nil }
        XCTAssertNil(player.problem)
        print("E2E player duration \(player.duration ?? -1)")
        player.toggle()
        try await waitUntil(seconds: 15) { player.time > 1 }
        player.stop()

        // 6. Delete, as the detail screen does; the list and the audio are gone.
        try await client.delete(listed.id)
        LibraryStore.shared.remove(listed.id)
        await LibraryStore.shared.refresh()
        XCTAssertNil(LibraryStore.shared.jobs.first { $0.id == listed.id })
        do {
            _ = try await client.job(listed.id)
            XCTFail("the deleted job still answers")
        } catch let JobsClient.Failure.status(status, _, _) {
            XCTAssertEqual(status, 410)
        }
        print("E2E deleted \(listed.id)")
    }

    private func waitUntil(seconds: Double, _ done: () async throws -> Bool) async throws {
        let deadline = Date().addingTimeInterval(seconds)
        while Date() < deadline {
            if try await done() { return }
            try await Task.sleep(for: .milliseconds(250))
        }
        XCTFail("timed out after \(Int(seconds)) s")
        throw CancellationError()
    }
}
