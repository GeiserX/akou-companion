// SPDX-License-Identifier: GPL-3.0-or-later
import ActivityKit
import Foundation

/// The recording's Live Activity. Started on record, ended on stop. It is updated right away on
/// pause and resume, and for a new closed line at most once every five seconds: the system budgets
/// Live Activity updates, and the elapsed time draws itself from `startedAt`.
@MainActor
final class ActivityController {
    static let lineInterval: Duration = .seconds(5)

    private var activity: Activity<RecordingAttributes>?
    /// Internal so the app tests can read what the Live Activity shows.
    private(set) var content = RecordingAttributes.ContentState(startedAt: .now, paused: false, lastLine: "", liveText: false)
    private var lastPush: ContinuousClock.Instant?
    private var pendingLine: Task<Void, Never>?

    /// `liveText` false: this recording has no live text (no server, no key, or none on the server).
    func start(title: String, startedAt: Date, liveText: Bool) {
        end()
        content = .init(startedAt: startedAt, paused: false, lastLine: "", liveText: liveText)
        guard ActivityAuthorizationInfo().areActivitiesEnabled else { return }
        activity = try? Activity.request(
            attributes: RecordingAttributes(title: title),
            content: .init(state: content, staleDate: nil)
        )
        lastPush = .now
    }

    func setPaused(_ paused: Bool, startedAt: Date) {
        content.paused = paused
        content.startedAt = startedAt
        push()
    }

    func setLastLine(_ line: String) {
        guard line != content.lastLine else { return }
        content.lastLine = line
        guard pendingLine == nil else { return } // the scheduled push takes the newest line
        let wait = lastPush.map { Self.lineInterval - $0.duration(to: .now) } ?? .zero
        if wait <= .zero { return push() }
        pendingLine = Task { [weak self] in
            try? await Task.sleep(for: wait)
            guard !Task.isCancelled else { return }
            self?.push()
        }
    }

    /// Live text ended for this recording (a refusal, or a server without it); the lines so far stay.
    func setLiveText(_ on: Bool) {
        guard on != content.liveText else { return }
        content.liveText = on
        push()
    }

    func end() {
        pendingLine?.cancel()
        pendingLine = nil
        guard let activity else { return }
        self.activity = nil
        let final = ActivityContent(state: content, staleDate: nil)
        // Activity is not Sendable; this controller is its only user and never touches it again.
        nonisolated(unsafe) let ending = activity
        Task { await ending.end(final, dismissalPolicy: .immediate) }
    }

    private func push() {
        pendingLine?.cancel()
        pendingLine = nil
        lastPush = .now
        guard let activity else { return }
        let next = ActivityContent(state: content, staleDate: nil)
        // Activity is not Sendable; updates come only from this main-actor controller.
        nonisolated(unsafe) let target = activity
        Task { await target.update(next) }
    }
}
