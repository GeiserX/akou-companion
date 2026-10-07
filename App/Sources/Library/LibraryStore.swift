// SPDX-License-Identifier: GPL-3.0-or-later
import AkouClient
import AkouProtocol
import Foundation
import Observation

/// The recordings list: the companion's jobs from the server, newest first, so the list survives a
/// reinstall, plus the recordings still waiting in the upload queue.
@MainActor
@Observable
final class LibraryStore {
    static let shared = LibraryStore()

    /// The server's jobs this app sent (`metadata.companion == 1`), newest first.
    private(set) var jobs: [Job] = []
    /// The recordings not on the server yet: waiting, uploading or stopped on a refusal.
    private(set) var waiting: [UploadQueue.Item] = []
    /// The cursor of the next page; nil once the last page is read.
    private(set) var cursor: Int?
    private(set) var loading = false
    private(set) var error: String?
    /// The workspace chip that is on; nil shows every recording.
    var workspace: String?

    /// A page of 50 jobs may hold none of the companion's (another client's jobs in between), so a
    /// refresh reads on until it has a screenful or this many pages.
    static let pagesPerFill = 5
    static let screenful = 20

    /// Every workspace a recording in the list carries, sorted.
    var workspaces: [String] {
        Set(jobs.compactMap { $0.metadata?.workspace }.filter { !$0.isEmpty }).sorted()
    }

    /// The list under the workspace chip.
    var shown: [Job] {
        guard let workspace else { return jobs }
        return jobs.filter { $0.metadata?.workspace == workspace }
    }

    /// The client for the saved server and key, or nil when either is missing.
    static func client() -> JobsClient? {
        guard let url = ServerSettings.url, let key = KeyStore.load(), !key.isEmpty else { return nil }
        return JobsClient(baseURL: url, key: key)
    }

    /// Reads the list again from the first page.
    func refresh() async {
        await loadWaiting()
        guard !loading else { return }
        guard let client = Self.client() else {
            error = "Set the server and the key in Settings."
            return
        }
        loading = true
        defer { loading = false }
        do {
            var fresh: [Job] = []
            var next: Int?
            var pages = 0
            repeat {
                let page = try await client.list(cursor: next)
                fresh += page.jobs.filter(Self.isCompanion)
                next = page.cursor
                pages += 1
            } while next != nil && fresh.count < Self.screenful && pages < Self.pagesPerFill
            jobs = fresh
            cursor = next
            error = nil
            SnapshotWriter.listRefreshed(jobs)
        } catch {
            self.error = Self.describe(error)
        }
    }

    /// Reads the next page, when there is one.
    func loadMore() async {
        guard !loading, let next = cursor, let client = Self.client() else { return }
        loading = true
        defer { loading = false }
        do {
            let page = try await client.list(cursor: next)
            let known = Set(jobs.map(\.id))
            jobs += page.jobs.filter { Self.isCompanion($0) && !known.contains($0.id) }
            cursor = page.cursor
        } catch {
            self.error = Self.describe(error)
        }
    }

    /// The recordings in the upload queue that have no job yet.
    func loadWaiting() async {
        let items = await BackgroundUploader.shared.items()
        waiting = items.filter {
            switch $0.state {
            case .pending, .uploading, .parked: true
            case .submitted, .done: false
            }
        }
    }

    /// The phone's own copy of a recording, when the upload queue still holds its file.
    func localAudio(for job: Job) async -> URL? {
        guard let recordingID = job.metadata?.recordingID else { return nil }
        let items = await BackgroundUploader.shared.items()
        guard let item = items.first(where: { $0.recordingID == recordingID }) else { return nil }
        return BackgroundUploader.shared.localAudio(item)
    }

    func job(_ id: String) -> Job? { jobs.first { $0.id == id } }

    /// Puts a job read elsewhere (the detail view, after a rename) into the list.
    func update(_ job: Job) {
        if let i = jobs.firstIndex(where: { $0.id == job.id }) { jobs[i] = job }
    }

    /// The job was deleted on the server.
    func remove(_ id: String) {
        jobs.removeAll { $0.id == id }
        SnapshotWriter.listRefreshed(jobs)
    }

    static func isCompanion(_ job: Job) -> Bool { job.metadata?.companion == 1 }

    static func describe(_ error: Error) -> String {
        switch error {
        case JobsClient.Failure.status(401, _, _):
            return "The server refused the key. Check it in Settings."
        case let JobsClient.Failure.status(status, body, _):
            return [("HTTP \(status)"), body?.message ?? body?.error].compactMap { $0 }.joined(separator: ": ")
        case JobsClient.Failure.notAkou:
            return "That address did not answer as an akou server."
        case let f as Endpoint.Failure:
            return "Refused: \(f). Use https, or plain http only to a private address."
        default:
            return error.localizedDescription
        }
    }

    /// akou writes `created_at` as `2026-10-07T08:00:00.000Z`.
    static func date(_ iso: String?) -> Date? {
        guard let iso else { return nil }
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let d = f.date(from: iso) { return d }
        f.formatOptions = [.withInternetDateTime]
        return f.date(from: iso)
    }
}

/// Where the Library's navigation stands, so a link from the widget
/// (`akou-companion://recording/<job id>`) can open a recording's detail from anywhere in the app.
@MainActor
@Observable
final class LibraryRouter {
    static let shared = LibraryRouter()

    /// The job ids pushed on the Library's stack.
    var path: [String] = []

    /// Opens a recording link. True when the link was one; the caller then shows the Library tab.
    @discardableResult
    func open(_ url: URL) -> Bool {
        guard case let .recording(jobId) = DeepLink(url) else { return false }
        path = [jobId]
        return true
    }
}
