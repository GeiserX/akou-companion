// SPDX-License-Identifier: GPL-3.0-or-later
import AkouClient
import AkouProtocol
import SwiftUI

/// One recording: its player, its final transcript with every timed word a button that seeks the
/// player there, and rename and delete, which go to the server.
struct RecordingDetailView: View {
    let jobId: String

    @State private var job: Job?
    @State private var result: JobResult?
    @State private var status: String?
    @State private var player = RecordingPlayer()
    @State private var renaming = false
    @State private var newTitle = ""
    @State private var confirmingDelete = false
    @State private var actionError: String?
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                PlayerBar(player: player)
                if let result {
                    TranscriptView(result: result, now: player.time) { s in player.seek(to: s) }
                } else if let status {
                    Text(status).foregroundStyle(.secondary)
                } else {
                    ProgressView()
                }
                if let actionError {
                    Text(actionError).font(.footnote).foregroundStyle(.red)
                }
            }
            .padding()
        }
        .navigationTitle(job?.title ?? "Recording")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Menu {
                    Button("Rename", systemImage: "pencil") {
                        newTitle = job?.title ?? ""
                        renaming = true
                    }
                    Button("Delete", systemImage: "trash", role: .destructive) { confirmingDelete = true }
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
                .disabled(job == nil)
            }
        }
        .alert("Rename", isPresented: $renaming) {
            TextField("Title", text: $newTitle)
            Button("Cancel", role: .cancel) {}
            Button("Save") { Task { await rename() } }
        }
        .confirmationDialog("Delete this recording?", isPresented: $confirmingDelete, titleVisibility: .visible) {
            Button("Delete", role: .destructive) { Task { await delete() } }
        } message: {
            Text(deleteMessage)
        }
        .task(id: jobId) { await load() }
        .onDisappear { player.stop() }
    }

    private var deleteMessage: String {
        if job?.keepAudio == true {
            return "This deletes the transcript and the audio the server kept. A copy on this iPhone, if there is one, stays."
        }
        return "This deletes the transcript on the server. A copy on this iPhone, if there is one, stays."
    }

    private func load() async {
        guard let client = LibraryStore.client() else {
            status = "Set the server and the key in Settings."
            return
        }
        do {
            let job: Job
            if let known = LibraryStore.shared.job(jobId) { job = known } else { job = try await client.job(jobId) }
            self.job = job
            player.load(jobId: jobId, keptOnServer: job.keepAudio, local: await LibraryStore.shared.localAudio(for: job), client: client)
            try await loadResult(job, client)
        } catch {
            status = LibraryStore.describe(error)
        }
    }

    /// The transcript, waiting for the job to end first when it has not (409 `not_done`).
    private func loadResult(_ first: Job, _ client: JobsClient) async throws {
        var job = first
        while !Task.isCancelled {
            if job.status == "failed" || job.status == "cancelled" {
                status = "The server could not transcribe this recording (\(job.error ?? job.status))."
                return
            }
            if job.status == "done" {
                do {
                    let result = try await client.result(jobId)
                    self.result = result
                    SnapshotWriter.transcriptLoaded(job: job, result: result)
                    return
                } catch JobsClient.Failure.status(409, _, _) {
                    // `not_done` for a job that read as done: the result is being written. Ask again shortly.
                    try await Task.sleep(for: .seconds(2))
                }
            }
            status = job.status == "queued" ? "Waiting for the server…" : "Transcribing…"
            job = try await client.job(jobId, wait: 30)
            self.job = job
            LibraryStore.shared.update(job)
        }
    }

    private func rename() async {
        let title = newTitle.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !title.isEmpty, let client = LibraryStore.client() else { return }
        do {
            let job = try await client.rename(jobId, title: title)
            self.job = job
            LibraryStore.shared.update(job)
            actionError = nil
        } catch {
            actionError = "Rename failed: \(LibraryStore.describe(error))"
        }
    }

    private func delete() async {
        guard let client = LibraryStore.client() else { return }
        do {
            try await client.delete(jobId)
            player.stop()
            LibraryStore.shared.remove(jobId)
            dismiss()
        } catch {
            actionError = "Delete failed: \(LibraryStore.describe(error))"
        }
    }
}

private struct PlayerBar: View {
    let player: RecordingPlayer

    var body: some View {
        if let problem = player.problem {
            Label(problem, systemImage: "speaker.slash").font(.footnote).foregroundStyle(.secondary)
        } else if player.source != .none {
            HStack(spacing: 12) {
                Button {
                    player.toggle()
                } label: {
                    Image(systemName: player.playing ? "pause.circle.fill" : "play.circle.fill").font(.largeTitle)
                }
                .accessibilityLabel(player.playing ? "Pause" : "Play")
                Text(Self.clock(player.time)).monospacedDigit()
                if let d = player.duration {
                    Text("/ \(Self.clock(d))").monospacedDigit().foregroundStyle(.secondary)
                }
                Spacer()
                Image(systemName: player.source == .server ? "icloud" : "iphone")
                    .foregroundStyle(.secondary)
                    .accessibilityLabel(player.source == .server ? "Playing from the server" : "Playing the copy on this iPhone")
            }
        }
    }

    static func clock(_ s: Double) -> String {
        let t = Int(max(0, s))
        return t >= 3600 ? String(format: "%d:%02d:%02d", t / 3600, t / 60 % 60, t % 60) : String(format: "%d:%02d", t / 60, t % 60)
    }
}

/// The final transcript, a paragraph per segment. A word with a time is a button that seeks the
/// player to it; a word with none (`s` null, as from Qwen on `best`) is plain text, and its
/// segment's start is the seek point instead.
private struct TranscriptView: View {
    let result: JobResult
    let now: Double
    let seek: (Double) -> Void

    var body: some View {
        LazyVStack(alignment: .leading, spacing: 12) {
            ForEach(Array(Paragraph.split(result).enumerated()), id: \.offset) { _, p in
                VStack(alignment: .leading, spacing: 4) {
                    if let speaker = p.speaker {
                        Text(speaker).font(.caption).foregroundStyle(.secondary)
                    }
                    if p.words.isEmpty {
                        Text(p.text)
                            .onTapGesture { if let s = p.start { seek(s) } }
                    } else {
                        WordFlow(spacing: 4) {
                            ForEach(Array(p.words.enumerated()), id: \.offset) { _, word in
                                WordView(word: word, current: Self.isCurrent(word, now), seek: seek)
                            }
                        }
                    }
                }
            }
        }
        .textSelection(.enabled)
    }

    static func isCurrent(_ w: JobWord, _ now: Double) -> Bool {
        guard let s = w.s, let e = w.e else { return false }
        return s <= now && now < e
    }
}

private struct WordView: View {
    let word: JobWord
    let current: Bool
    let seek: (Double) -> Void

    var body: some View {
        let text = Text(word.w.trimmingCharacters(in: .whitespaces))
        if let s = word.s {
            text
                .padding(.horizontal, 1)
                .background(current ? Color.accentColor.opacity(0.25) : .clear, in: RoundedRectangle(cornerRadius: 3))
                .contentShape(Rectangle())
                .onTapGesture { seek(s) }
                .accessibilityAddTraits(.isButton)
                .accessibilityHint("Plays from here")
        } else {
            text.foregroundStyle(word.c.map { $0 < 0.5 } == true ? .secondary : .primary)
        }
    }
}

/// A segment and the words inside it. Words are placed by their start time; with no word times at
/// all, a segment shows its own text.
struct Paragraph {
    var speaker: String?
    var start: Double?
    var text: String
    var words: [JobWord]

    static func split(_ r: JobResult) -> [Paragraph] {
        let timed = r.words.contains { $0.s != nil }
        if r.segments.isEmpty {
            // No segments: paragraphs of 60 words.
            return stride(from: 0, to: r.words.count, by: 60).map { i in
                let words = Array(r.words[i..<min(i + 60, r.words.count)])
                return Paragraph(speaker: nil, start: words.first?.s, text: words.map(\.w).joined(separator: " "), words: words)
            }
        }
        guard timed else {
            return r.segments.map { Paragraph(speaker: $0.speaker, start: $0.s, text: $0.text, words: []) }
        }
        var out = r.segments.map { Paragraph(speaker: $0.speaker, start: $0.s, text: $0.text, words: []) }
        var k = 0
        for w in r.words {
            let s = w.s ?? out[k].start ?? 0
            while k + 1 < out.count, let next = out[k + 1].start, s >= next { k += 1 }
            out[k].words.append(w)
        }
        return out
    }
}

/// Lays its children out left to right, wrapping to a new line when the next one does not fit.
struct WordFlow: Layout {
    var spacing: CGFloat = 4

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let rows = arrange(proposal.width ?? .infinity, subviews)
        let height = rows.last.map { $0.y + $0.height } ?? 0
        let width = rows.map(\.width).max() ?? 0
        return CGSize(width: proposal.width ?? width, height: height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        for row in arrange(bounds.width, subviews) {
            for (index, x) in row.items {
                subviews[index].place(at: CGPoint(x: bounds.minX + x, y: bounds.minY + row.y), proposal: .unspecified)
            }
        }
    }

    private struct Row {
        var y: CGFloat
        var height: CGFloat = 0
        var width: CGFloat = 0
        var items: [(Int, CGFloat)] = []
    }

    private func arrange(_ maxWidth: CGFloat, _ subviews: Subviews) -> [Row] {
        var rows: [Row] = []
        var row = Row(y: 0)
        for (i, view) in subviews.enumerated() {
            let size = view.sizeThatFits(.unspecified)
            let x = row.items.isEmpty ? 0 : row.width + spacing
            if !row.items.isEmpty && x + size.width > maxWidth {
                rows.append(row)
                row = Row(y: row.y + row.height + spacing)
                row.items.append((i, 0))
                row.width = size.width
                row.height = size.height
            } else {
                row.items.append((i, x))
                row.width = x + size.width
                row.height = max(row.height, size.height)
            }
        }
        if !row.items.isEmpty { rows.append(row) }
        return rows
    }
}
