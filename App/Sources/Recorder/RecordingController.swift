// SPDX-License-Identifier: GPL-3.0-or-later
import AVFoundation
import AkouClient
import AkouProtocol
import Foundation

/// The one recorder. Other parts of the app (the uploader, the library, the record intent) code
/// against this: `start`, `pause`, `resume`, `stop`, and `onFinished` for every file that is done.
///
/// The file is the recording; live text is a bonus that never holds it up. With no server set,
/// no key, or a server without live text, it records just the same.
@MainActor
final class RecordingController: ObservableObject {
    static let shared = RecordingController()

    enum State: Equatable {
        case idle
        /// `startedAt` is shifted by the paused time, so a timer drawn from it shows the audio's length.
        case recording(startedAt: Date)
        case paused
        case stopping
    }

    enum Failure: Error, Equatable {
        case microphoneDenied
        case alreadyRecording
    }

    @Published private(set) var state: State = .idle
    /// Seconds recorded before the current pause, for the paused screen.
    @Published private(set) var elapsedBeforePause: TimeInterval = 0
    @Published private(set) var transcript = LiveTranscript()
    @Published private(set) var liveState: LiveSession.State = .off(reason: "idle")
    /// A write error from the last recording, if any.
    @Published private(set) var lastError: String?
    /// From the record screen's server check: false means the server has no live text, so no
    /// socket is opened. Nil (not checked) still tries.
    @Published var serverSupportsLive: Bool?

    /// Called on the main actor with every recording that stops.
    var onFinished: ((FinishedRecording) -> Void)?
    /// The server key, supplied by the settings, which keep it in the Keychain. Nil records
    /// without live text.
    var serverKey: () -> String? = { nil }

    private struct Current {
        let id: UUID
        let file: RecordingFile
        let capture: AudioCapture
        let live: LiveSession?
        let startedAt: Date
        let workspace: String?
        let title: String?
        let language: String
        let model: String
        var forwarder: Task<Void, Never>
        var listener: Task<Void, Never>?
    }

    private var current: Current?
    private var resumedAt = Date()
    private var pausedBySystem = false
    private let activity = ActivityController()

    private init() {}

    func start(workspace: String?, title: String?) async throws {
        guard state == .idle, current == nil else { throw Failure.alreadyRecording }
        guard await AVAudioApplication.requestRecordPermission() else { throw Failure.microphoneDenied }
        guard state == .idle, current == nil else { throw Failure.alreadyRecording }

        let id = UUID()
        let file = try RecordingFile(url: try Self.recordingsDirectory().appending(path: "\(id.uuidString).opus"))
        let capture = AudioCapture { [file] samples in file.append(samples) }
        do {
            try capture.start()
        } catch {
            _ = await file.finish()
            try? FileManager.default.removeItem(at: file.url)
            throw error
        }

        let defaults = UserDefaults.standard
        let language = defaults.string(forKey: "liveLanguage") ?? "auto"
        let model = defaults.string(forKey: "liveModel") ?? "auto"
        let live = makeLiveSession(file: file, language: language, model: model)
        let forwarder = Task {
            for await page in file.pages { await live?.send(page: page.data, endsAt: page.end) }
        }
        let now = Date()
        current = Current(
            id: id, file: file, capture: capture, live: live, startedAt: now,
            workspace: workspace, title: title, language: language, model: model, forwarder: forwarder
        )
        transcript = LiveTranscript()
        lastError = nil
        elapsedBeforePause = 0
        resumedAt = now
        pausedBySystem = false
        state = .recording(startedAt: now)
        capture.onInterruption = { [weak self] began, shouldResume in
            guard let self else { return }
            if began {
                if case .recording = self.state {
                    self.pause()
                    self.pausedBySystem = true
                }
            } else if self.pausedBySystem, shouldResume {
                self.resume()
            }
        }
        activity.start(title: title ?? workspace ?? "akou", startedAt: now)

        if let live {
            liveState = .connecting
            current?.listener = Task { [weak self] in
                for await event in live.events { self?.handle(event) }
            }
            await live.start()
        } else {
            liveState = .off(reason: "no_server")
        }
    }

    func pause() {
        guard case let .recording(startedAt) = state, let current else { return }
        current.capture.pause()
        elapsedBeforePause = Date().timeIntervalSince(startedAt)
        pausedBySystem = false
        state = .paused
        activity.setPaused(true, startedAt: startedAt)
    }

    func resume() {
        guard state == .paused, let current else { return }
        do {
            try current.capture.resume()
        } catch {
            lastError = "The microphone did not come back: \(error.localizedDescription)"
            return
        }
        pausedBySystem = false
        let startedAt = Date().addingTimeInterval(-elapsedBeforePause)
        state = .recording(startedAt: startedAt)
        activity.setPaused(false, startedAt: startedAt)
    }

    func stop() async -> FinishedRecording? {
        guard let rec = current else { return nil }
        switch state {
        case .recording, .paused: break
        case .idle, .stopping: return nil
        }
        state = .stopping
        rec.capture.stop()
        let (seconds, error) = await rec.file.finish()
        await rec.forwarder.value
        await rec.live?.stop()
        // `events` has finished once stop returns; let the listener take the last words before
        // the transcript closes. Cancelling it would drop them or open a line after close().
        await rec.listener?.value
        transcript.close()
        activity.end()
        current = nil
        lastError = error
        let finished = FinishedRecording(
            id: rec.id, fileURL: rec.file.url, startedAt: rec.startedAt, duration: seconds,
            workspace: rec.workspace, title: rec.title, language: rec.language, model: rec.model
        )
        state = .idle
        onFinished?(finished)
        return finished
    }

    private func makeLiveSession(file: RecordingFile, language: String, model: String) -> LiveSession? {
        guard serverSupportsLive != false,
              let raw = UserDefaults.standard.string(forKey: "serverURL"),
              let base = URL(string: raw.trimmingCharacters(in: .whitespaces)),
              let key = serverKey(), !key.isEmpty else { return nil }
        return LiveSession(
            baseURL: base, key: key, hello: LiveHello(language: language, model: model), headerPages: file.headerPages
        )
    }

    private func handle(_ event: LiveSession.Event) {
        switch event {
        case let .words(words):
            let before = transcript.lastClosedLine
            transcript.append(words)
            if let line = transcript.lastClosedLine, line != before { activity.setLastLine(line) }
        case let .gap(from, to):
            transcript.gap(from: from, to: to)
        case let .state(s):
            liveState = s
        }
    }

    /// `Application Support/Recordings`, readable after the first unlock so a locked phone can write.
    static func recordingsDirectory() throws -> URL {
        let dir = URL.applicationSupportDirectory.appending(path: "Recordings", directoryHint: .isDirectory)
        try FileManager.default.createDirectory(
            at: dir, withIntermediateDirectories: true,
            attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication]
        )
        return dir
    }
}
