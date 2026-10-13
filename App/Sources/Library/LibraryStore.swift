// SPDX-License-Identifier: GPL-3.0-or-later
import AkouClient
import AkouProtocol
import Foundation
import Observation

/// A server's jobs, newest first, read from the server so the list survives a reinstall, plus the
/// recordings still waiting in the upload queue. With a key that only uploads, the jobs are the
/// ones this app sent (`metadata.companion == 1`); with one that sees history, every job (Files).
@MainActor
@Observable
final class LibraryStore {
    static let shared = LibraryStore()

    /// The server the list is of.
    private(set) var serverID: UUID?
    /// Every job (Files), or only the ones this app sent.
    private(set) var everyJob = false
    /// The jobs, newest first.
    private(set) var jobs: [Job] = []
    /// Every recording in the upload queue that has no job yet: waiting, uploading or stopped.
    private(set) var waiting: [UploadQueue.Item] = []
    /// The cursor of the next page; nil once the last page is read.
    private(set) var cursor: Int?
    private(set) var loading = false
    private(set) var error: String?
    /// The workspace chip that is on; nil shows every recording.
    var workspace: String?

    /// A page of 50 jobs may hold none of the companion's (another client's jobs in between), so a
    /// refresh reads on until it has a screenful or this many pages, and past them until it has
    /// one row, which `loadMore` then reads on from.
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

    /// Points the list at `server`, emptying it when that changes what it shows.
    func show(server: UUID, everyJob: Bool) {
        guard server != serverID || everyJob != self.everyJob else { return }
        serverID = server
        self.everyJob = everyJob
        jobs = []
        cursor = nil
        error = nil
        workspace = nil
    }

    private func keep(_ job: Job) -> Bool { everyJob || Self.isCompanion(job) }

    /// Reads the list again from the first page.
    func refresh() async {
        await loadWaiting()
        guard !loading, let serverID else { return }
        guard let client = ServerSettings.jobsClient(serverID) else {
            error = "Set this server's URL and key in Settings."
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
                fresh += page.jobs.filter(keep)
                next = page.cursor
                pages += 1
            } while next != nil && (fresh.isEmpty || (fresh.count < Self.screenful && pages < Self.pagesPerFill))
            // The answer of a server the list no longer shows changes nothing.
            guard serverID == self.serverID else { return }
            jobs = fresh
            cursor = next
            error = nil
            if serverID == ServerSettings.book.recordTo { SnapshotWriter.listRefreshed(jobs.filter(Self.isCompanion)) }
        } catch {
            guard serverID == self.serverID else { return }
            self.error = Self.describe(error)
        }
    }

    /// Reads on from the cursor until a page adds a recording or the last page is read. A page
    /// that adds none would leave the last row unchanged, and its `.task` would never ask again.
    func loadMore() async {
        guard !loading, cursor != nil, let serverID, let client = ServerSettings.jobsClient(serverID) else { return }
        loading = true
        defer { loading = false }
        do {
            var added = 0
            while added == 0, let next = cursor {
                let page = try await client.list(cursor: next)
                guard serverID == self.serverID else { return }
                let known = Set(jobs.map(\.id))
                let fresh = page.jobs.filter { keep($0) && !known.contains($0.id) }
                jobs += fresh
                added = fresh.count
                cursor = page.cursor
            }
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

    /// The waiting recordings the Library of `server` shows: its own, those from before several
    /// servers when it is "Record to", and those whose server was removed, which any server's
    /// Library offers to take.
    func waiting(for server: UUID, book: ServerBook) -> [UploadQueue.Item] {
        waiting.filter { item in
            if item.waitsForAServer || book.server(item.serverID) == nil && item.serverID != nil { return true }
            return (item.serverID ?? book.recordTo) == server
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
        if serverID == ServerSettings.book.recordTo { SnapshotWriter.listRefreshed(jobs.filter(Self.isCompanion)) }
    }

    static func isCompanion(_ job: Job) -> Bool { job.metadata?.companion == 1 }

    static func describe(_ error: Error) -> String {
        switch error {
        case JobsClient.Failure.status(401, _, _):
            return "The server refused the key. Check it in Settings."
        case JobsClient.Failure.status(403, _, _):
            return "This key cannot see these recordings."
        case let JobsClient.Failure.status(status, body, _):
            return [("HTTP \(status)"), body?.message ?? body?.error].compactMap { $0 }.joined(separator: ": ")
        case JobsClient.Failure.notAkou, HistoryClient.Failure.notAkou:
            return "That address did not answer as an akou server."
        case HistoryClient.Failure.cannotSeeHistory:
            return "This key cannot see history."
        case let HistoryClient.Failure.status(status, code, message):
            return [("HTTP \(status)"), message ?? code].compactMap { $0 }.joined(separator: ": ")
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

    /// akou writes call and dictation times as epoch milliseconds.
    static func date(ms: Double?) -> Date? {
        guard let ms, ms > 0 else { return nil }
        return Date(timeIntervalSince1970: ms / 1000)
    }
}

/// A screen the Library's stack can push, on the server the Library shows.
enum LibraryRoute: Hashable {
    case job(String)
    case call(String)
    case dictation(String)
}

/// Where the Library's navigation stands, so a link from the widget
/// (`akou-companion://recording/<job id>`) can open a recording's detail from anywhere in the app.
@MainActor
@Observable
final class LibraryRouter {
    static let shared = LibraryRouter()

    /// The screens pushed on the Library's stack.
    var path: [LibraryRoute] = []
    /// The server the Library shows; nil shows "Record to".
    var server: UUID?

    /// Opens a recording link. True when the link was one; the caller then shows the Library tab.
    /// The widget shows "Record to"'s recordings, so the Library switches to it.
    @discardableResult
    func open(_ url: URL) -> Bool {
        guard case let .recording(jobId) = DeepLink(url) else { return false }
        server = ServerSettings.book.recordTo
        path = [.job(jobId)]
        return true
    }
}
