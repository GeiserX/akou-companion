// SPDX-License-Identifier: GPL-3.0-or-later
import AkouClient
import AkouProtocol
import Foundation

/// Keeps the recent-recordings snapshot the widget and the "last recording" intent read
/// (`RecentSnapshot`, in the App Group container) in step with the Library: after each refresh of
/// the list and after each transcript loads. `RecentSnapshot.write()` then reloads the widget's
/// timelines. The extension never asks the server anything; this is all it sees.
@MainActor
enum SnapshotWriter {
    /// The list was read again: the newest recordings, each keeping the opening it already had.
    static func listRefreshed(_ jobs: [Job]) {
        let old = Dictionary((RecentSnapshot.read()?.recordings ?? []).map { ($0.jobId, $0) }, uniquingKeysWith: { a, _ in a })
        try? RecentSnapshot(recordings: jobs.compactMap { recording($0, old: old[$0.id]) }).write()
    }

    /// A transcript loaded: its opening sentences and its length go into the snapshot. A recording
    /// older than the snapshot's newest few falls out again when the snapshot keeps its limit.
    static func transcriptLoaded(job: Job, result: JobResult) {
        var recordings = RecentSnapshot.read()?.recordings ?? []
        let i = recordings.firstIndex(where: { $0.jobId == job.id })
        guard var r = recording(job, old: i.map { recordings[$0] }) else { return }
        let words = result.words.map { TranscriptDigest.Word(w: $0.w, s: $0.s, e: $0.e, c: $0.c) }
        r.opening = TranscriptDigest.opening(text: result.text, words: words, limit: RecentSnapshot.openingLimit)
        r.duration = result.durationS
        if let i { recordings[i] = r } else { recordings.append(r) }
        try? RecentSnapshot(recordings: recordings).write()
    }

    private static func recording(_ job: Job, old: RecentRecording?) -> RecentRecording? {
        guard let created = LibraryStore.date(job.createdAt) else { return nil }
        return RecentRecording(
            jobId: job.id,
            title: job.title ?? "Untitled recording",
            workspace: job.metadata?.workspace,
            createdAt: created,
            duration: old?.duration,
            status: job.status,
            opening: old?.opening ?? "")
    }
}
