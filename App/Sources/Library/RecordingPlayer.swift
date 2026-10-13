// SPDX-License-Identifier: GPL-3.0-or-later
import AVFoundation
import AkouClient
import Foundation
import Observation

/// Plays one recording: the phone's own file when it still has one (the "keep a copy" setting, or
/// not uploaded yet), else audio on a server (a job's kept audio, a call's part, a dictation)
/// through `AuthorizedAudioLoader`, which sends the key as a header and asks for byte ranges, so
/// seeking does not download the whole file.
@MainActor
@Observable
final class RecordingPlayer {
    enum Source: Equatable {
        case local(URL)
        case server
        /// Neither: no file on the phone and the server did not keep the audio.
        case none
    }

    private(set) var source: Source = .none
    private(set) var time: Double = 0
    private(set) var duration: Double?
    private(set) var playing = false
    /// Why the audio cannot play, in words.
    private(set) var problem: String?

    @ObservationIgnored private var player: AVPlayer?
    /// Held for as long as the player: AVFoundation keeps its resource loader's delegate weakly.
    @ObservationIgnored private var loader: AuthorizedAudioLoader?
    @ObservationIgnored private var timeObserver: Any?
    /// A seek asked for before the item could play, made once it can.
    @ObservationIgnored private var pendingSeek: Double?
    @ObservationIgnored private var statusObservation: NSKeyValueObservation?

    /// Sets up the player for a job. `local` is the phone's copy when it has one.
    func load(jobId: String, keptOnServer: Bool, local: URL?, client: JobsClient?) {
        var request: (@Sendable (String) throws -> URLRequest)?
        if let client {
            request = { path in try client.audioRequest(path: path) }
        }
        let missing = keptOnServer
            ? "Set this server's URL and key in Settings."
            : "The server did not keep this recording's audio, and this phone has no copy."
        load(path: keptOnServer ? JobsClient.audioPath(jobId) : nil, local: local, request: request, missing: missing)
    }

    /// Sets up the player for an audio `path` on a server, answered with `request` (the server's
    /// client's `audioRequest(path:)`), or the phone's `local` copy when it has one. `missing` says
    /// why nothing plays when there is neither.
    func load(path: String?, local: URL? = nil, request: (@Sendable (String) throws -> URLRequest)?, missing: String) {
        stop()
        let item: AVPlayerItem
        if let local, FileManager.default.fileExists(atPath: local.path) {
            source = .local(local)
            item = AVPlayerItem(url: local)
        } else if let path, let request {
            source = .server
            let loader = AuthorizedAudioLoader(makeRequest: request)
            loader.onFailure = { _, failure in
                Task { @MainActor [weak self] in self?.problem = Self.describe(failure) }
            }
            self.loader = loader
            item = AVPlayerItem(asset: loader.asset(path: path))
        } else {
            source = .none
            problem = missing
            return
        }
        let player = AVPlayer(playerItem: item)
        self.player = player
        timeObserver = player.addPeriodicTimeObserver(forInterval: CMTime(value: 1, timescale: 4), queue: .main) { [weak self] t in
            MainActor.assumeIsolated {
                guard let self else { return }
                self.time = t.seconds.isFinite ? t.seconds : 0
                self.playing = player.rate != 0
            }
        }
        // A plain KVO observation: the Combine KVO publisher drops a change that arrives while its
        // async sequence has no demand, and readyToPlay often comes that fast, which left the
        // length unknown for good.
        statusObservation = item.observe(\.status, options: [.initial, .new]) { [weak self] item, _ in
            let status = item.status
            let seconds = item.duration.seconds
            let error = item.error?.localizedDescription
            let id = ObjectIdentifier(item)
            Task { @MainActor [weak self] in self?.statusChanged(of: id, status, seconds: seconds, error: error) }
        }
    }

    /// `item` is the observed item: a callback queued before `stop()` or the next `load()` belongs
    /// to a replaced item and changes nothing.
    private func statusChanged(of item: ObjectIdentifier, _ status: AVPlayerItem.Status, seconds: Double, error: String?) {
        guard let current = player?.currentItem, ObjectIdentifier(current) == item else { return }
        switch status {
        case .readyToPlay:
            duration = seconds.isFinite ? seconds : nil
            if let s = pendingSeek {
                pendingSeek = nil
                seek(to: s)
            }
        case .failed:
            if problem == nil { problem = error ?? "The audio could not be played." }
        default:
            break
        }
    }

    func toggle() {
        guard let player else { return }
        if player.rate == 0 {
            activateSession()
            player.play()
            playing = true
        } else {
            player.pause()
            playing = false
        }
    }

    /// Moves to `seconds` into the recording and plays from there.
    func seek(to seconds: Double) {
        guard let player else { return }
        if player.currentItem?.status != .readyToPlay {
            pendingSeek = seconds
            time = seconds
            return
        }
        let t = CMTime(seconds: max(0, seconds), preferredTimescale: 1000)
        player.seek(to: t, toleranceBefore: .zero, toleranceAfter: CMTime(value: 1, timescale: 20))
        time = seconds
        if player.rate == 0 {
            activateSession()
            player.play()
            playing = true
        }
    }

    func stop() {
        player?.pause()
        if let timeObserver { player?.removeTimeObserver(timeObserver) }
        timeObserver = nil
        statusObservation?.invalidate()
        statusObservation = nil
        player = nil
        loader = nil
        playing = false
        time = 0
        duration = nil
        problem = nil
        pendingSeek = nil
    }

    /// Playback, unless the recorder holds the session for a recording in progress.
    private func activateSession() {
        let session = AVAudioSession.sharedInstance()
        guard session.category != .playAndRecord && session.category != .record else { return }
        try? session.setCategory(.playback, mode: .spokenAudio)
        try? session.setActive(true)
    }

    static func describe(_ failure: AuthorizedAudioLoader.Failure) -> String {
        switch failure {
        case .notKept: "The server did not keep this recording's audio (it was uploaded without keep_audio)."
        case .gone: "The server no longer has this recording's audio."
        case .status(401, _): "The server refused the key. Check it in Settings."
        case .status(403, _): "This key cannot reach this audio."
        case .status(404, "no_audio"): "akou kept no audio for this one."
        case .status(404, _): "The server has no such audio."
        case let .unplayable(mime): "This audio is \(mime.isEmpty ? "in a format" : mime) that iOS cannot play."
        case let .status(status, code): "The server answered HTTP \(status)\(code.map { ": \($0)" } ?? "")."
        case .notAudioURL: "The audio could not be played."
        }
    }
}
