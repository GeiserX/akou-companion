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
    private var content = RecordingAttributes.ContentState(startedAt: .now, paused: false, lastLine: "")
    private var lastPush: ContinuousClock.Instant?
    private var pendingLine: Task<Void, Never>?

    func start(title: String, startedAt: Date) {
        end()
        guard ActivityAuthorizationInfo().areActivitiesEnabled else { return }
        content = .init(startedAt: startedAt, paused: false, lastLine: "")
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
