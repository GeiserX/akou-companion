// SPDX-License-Identifier: GPL-3.0-or-later
import ActivityKit
import Foundation

/// The recording's Live Activity. Started on record, ended on stop. It is updated right away on
/// pause and resume, and for a new closed line at most once every five seconds: the system budgets
/// Live Activity updates, and the elapsed time draws itself from `startedAt`.
@MainActor
final class ActivityController {
    private var activity: Activity<RecordingAttributes>?
    /// Internal so the app tests can read what the Live Activity shows.
    private(set) var content = RecordingAttributes.ContentState(startedAt: .now, paused: false, lastLine: "", liveText: false)
    private var throttle = LineThrottle()
    private var pendingLine: Task<Void, Never>?

    /// `liveText` false: this recording has no live text (no server, no key, or none on the server).
    func start(title: String, startedAt: Date, liveText: Bool) {
        end()
        content = .init(startedAt: startedAt, paused: false, lastLine: "", liveText: liveText)
        throttle = LineThrottle()
        guard ActivityAuthorizationInfo().areActivitiesEnabled else { return }
        activity = try? Activity.request(
            attributes: RecordingAttributes(title: title),
            content: .init(state: content, staleDate: nil)
        )
        throttle.push(at: .now)
    }

    func setPaused(_ paused: Bool, startedAt: Date) {
        content.paused = paused
        content.startedAt = startedAt
        push()
    }

    func setLastLine(_ line: String) {
        guard line != content.lastLine else { return }
        content.lastLine = line
        if throttle.offer(at: .now) { return push() }
        guard pendingLine == nil, let due = throttle.due else { return } // the scheduled push takes the newest line
        pendingLine = Task { [weak self] in
            try? await Task.sleep(until: due)
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
        throttle = LineThrottle()
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
        throttle.push(at: .now)
        guard let activity else { return }
        let next = ActivityContent(state: content, staleDate: nil)
        // Activity is not Sendable; updates come only from this main-actor controller.
        nonisolated(unsafe) let target = activity
        Task { await target.update(next) }
    }
}

/// When a new closed line may reach the Live Activity: at most one every `interval`. A line that
/// comes too soon waits for `due`; the controller has already put the newest line in its content,
/// so the push at `due` shows that one. Every push (a state change goes at once) takes the waiting
/// line with it and starts the interval again. Pure, so the tests drive it with their own instants.
struct LineThrottle {
    static let interval: Duration = .seconds(5)

    private(set) var lastPush: ContinuousClock.Instant?
    /// A line came too soon and waits for `due`.
    private(set) var lineWaiting = false

    /// A new line at `now`: true when it may go now (the caller pushes), false while it waits.
    mutating func offer(at now: ContinuousClock.Instant) -> Bool {
        if !lineWaiting, lastPush.map({ $0.duration(to: now) >= Self.interval }) ?? true {
            lastPush = now
            return true
        }
        lineWaiting = true
        return false
    }

    /// When the waiting line goes, or nil with nothing waiting.
    var due: ContinuousClock.Instant? {
        guard lineWaiting, let lastPush else { return nil }
        return lastPush + Self.interval
    }

    /// A push went out at `now`, for a state change or for the waiting line.
    mutating func push(at now: ContinuousClock.Instant) {
        lastPush = now
        lineWaiting = false
    }
}
