// SPDX-License-Identifier: GPL-3.0-or-later
import AkouClient
import AkouProtocol
import SwiftUI

/// One call from an akou's history, as the desktop app shows it: the summary when there is one,
/// the notepad, the transcript line by line with its speakers and times, and the audio of each
/// part. Tapping a line plays the audio from there.
struct CallDetailView: View {
    let serverID: UUID
    let callId: String

    @State private var call: CallDetail?
    @State private var transcript: CallTranscript?
    @State private var summary: CallEnhanced.Notes?
    @State private var notes: [CallNote] = []
    @State private var status: String?
    @State private var part = 1
    /// The part the player holds, so choosing the same part again does not reload it.
    @State private var loadedPart: Int?
    @State private var player = RecordingPlayer()

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                if let call {
                    header(call)
                    if call.parts.count > 1 {
                        Picker("Part", selection: $part) {
                            ForEach(call.parts, id: \.part) { Text("Part \($0.part)").tag($0.part) }
                        }
                        .pickerStyle(.segmented)
                    }
                    PlayerBar(player: player)
                }
                if let summary {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("Summary").font(.headline)
                        MarkdownBlock(markdown: summary.markdown)
                    }
                }
                if !notes.isEmpty {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("Notes").font(.headline)
                        ForEach(notes) { n in
                            Text(Self.bulleted(n.text)).font(.callout)
                        }
                    }
                }
                if let transcript {
                    // Lazy: a two-and-a-half-hour call has thousands of lines.
                    LazyVStack(alignment: .leading, spacing: 10) {
                        Text("Transcript").font(.headline)
                        if let zone = transcript.zone { Text(zone).font(.caption).foregroundStyle(.secondary) }
                        if transcript.lines.isEmpty {
                            Text("No lines yet.").foregroundStyle(.secondary)
                        }
                        ForEach(transcript.lines) { line in
                            LineView(line: line) { seek(to: line) }
                        }
                    }
                    .textSelection(.enabled)
                } else if let status {
                    Text(status).foregroundStyle(.secondary)
                } else {
                    ProgressView()
                }
            }
            .padding()
        }
        .navigationTitle(call.map { $0.title.isEmpty ? "Untitled call" : $0.title } ?? "Call")
        .navigationBarTitleDisplayMode(.inline)
        .task(id: callId) { await load() }
        .onChange(of: part) { _, n in loadAudio(part: n) }
        .onDisappear { player.stop() }
    }

    private func header(_ c: CallDetail) -> some View {
        HStack(spacing: 6) {
            if let d = LibraryStore.date(ms: c.startedAt ?? c.createdAt) {
                Text(d, format: .dateTime.weekday().day().month().hour().minute())
            }
            if let s = c.seconds {
                Text("· \(Duration.seconds(s).formatted(.units(allowed: [.hours, .minutes], width: .narrow)))")
            }
            if !c.workspace.isEmpty { Text("· \(c.workspace)") }
            if c.live { Text("· recording").foregroundStyle(.red) }
        }
        .font(.footnote)
        .foregroundStyle(.secondary)
    }

    private func load() async {
        guard let client = ServerSettings.historyClient(serverID) else {
            status = "Set this server's URL and key in Settings."
            return
        }
        do {
            let detail = try await client.call(callId)
            call = detail
            let first = detail.parts.first?.part ?? 1
            loadAudio(part: first)
            part = first
            // The summary and the notes are extras: a call without them still shows its transcript.
            async let lines = client.transcript(callId)
            async let enhanced = try? client.summary(callId)
            async let pad = try? client.notes(callId)
            transcript = try await lines
            summary = await enhanced ?? nil
            notes = await pad ?? []
        } catch {
            status = LibraryStore.describe(error)
        }
    }

    private func loadAudio(part n: Int) {
        guard n != loadedPart else { return }
        loadedPart = n
        guard let client = ServerSettings.historyClient(serverID), call?.parts.contains(where: { $0.part == n }) == true else {
            player.load(path: nil, request: nil, missing: "This call has no audio.")
            return
        }
        let length = call?.parts.first(where: { $0.part == n })?.ended?.fileSeconds
        player.load(path: HistoryClient.callAudioPath(callId, part: n), length: length, request: { try client.audioRequest(path: $0) }, missing: "")
    }

    /// A note as one bullet. akou's notepad often starts a note with its own list marker, which
    /// takes the bullet's place instead of showing as a second one ("• - text").
    static func bulleted(_ note: String) -> String {
        let t = note.trimmingCharacters(in: .whitespaces)
        for marker in ["- ", "* ", "• "] where t.hasPrefix(marker) {
            return "• " + t.dropFirst(marker.count).trimmingCharacters(in: .whitespaces)
        }
        return "• " + t
    }

    /// Plays from the line: `a0` seconds into its part, or the wall-clock offset from an older akou.
    private func seek(to line: CallLine) {
        let start = call?.parts.first(where: { $0.part == line.part })?.wallStart
        guard let seconds = line.seconds(intoPartStartingAt: start) else { return }
        if line.part != loadedPart {
            loadAudio(part: line.part)
            part = line.part
        }
        player.seek(to: seconds)
    }
}

private struct LineView: View {
    let line: CallLine
    let seek: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack(spacing: 6) {
                Text(line.speaker).font(.caption.weight(.semibold))
                Text(line.time).font(.caption).monospacedDigit().foregroundStyle(.secondary)
            }
            Text(line.text)
        }
        .contentShape(Rectangle())
        .onTapGesture(perform: seek)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isButton)
        .accessibilityHint("Plays from here")
    }
}

/// One dictation: its text, what the engine heard when the tidy changed it, where it went, and its
/// audio when akou kept it.
struct DictationDetailView: View {
    let serverID: UUID
    let dictationId: String

    @State private var dictation: Dictation?
    @State private var status: String?
    @State private var player = RecordingPlayer()

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                if let d = dictation {
                    HStack(spacing: 6) {
                        if let at = LibraryStore.date(ms: d.at) {
                            Text(at, format: .dateTime.weekday().day().month().hour().minute())
                        }
                        if let app = d.appName { Text("· \(app)") }
                        Text("· \(d.state)")
                    }
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    PlayerBar(player: player)
                    Text(d.text.flatMap { $0.isEmpty ? nil : $0 } ?? "No text.")
                        .textSelection(.enabled)
                    if let raw = d.raw, !raw.isEmpty, raw != d.text {
                        VStack(alignment: .leading, spacing: 4) {
                            Text("Heard").font(.caption).foregroundStyle(.secondary)
                            Text(raw).font(.callout).foregroundStyle(.secondary).textSelection(.enabled)
                        }
                    }
                    if let error = d.error {
                        Text(error).font(.footnote).foregroundStyle(.red)
                    }
                    Text([d.language, d.engine, d.model].compactMap { $0 }.joined(separator: " · "))
                        .font(.caption)
                        .foregroundStyle(.tertiary)
                } else if let status {
                    Text(status).foregroundStyle(.secondary)
                } else {
                    ProgressView()
                }
            }
            .padding()
        }
        .navigationTitle("Dictation")
        .navigationBarTitleDisplayMode(.inline)
        .task(id: dictationId) { await load() }
        .onDisappear { player.stop() }
    }

    private func load() async {
        guard let client = ServerSettings.historyClient(serverID) else {
            status = "Set this server's URL and key in Settings."
            return
        }
        do {
            let d = try await client.dictation(dictationId)
            dictation = d
            player.load(path: HistoryClient.dictationAudioPath(dictationId), length: d.seconds, request: { try client.audioRequest(path: $0) }, missing: "")
        } catch {
            status = LibraryStore.describe(error)
        }
    }
}

/// akou's summary is Markdown. SwiftUI's `Text` renders inline Markdown only, so headings and
/// bullets are laid out here, a line at a time.
struct MarkdownBlock: View {
    let markdown: String

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            ForEach(Array(markdown.split(separator: "\n", omittingEmptySubsequences: false).enumerated()), id: \.offset) { _, raw in
                line(String(raw))
            }
        }
        .textSelection(.enabled)
    }

    @ViewBuilder
    private func line(_ raw: String) -> some View {
        let t = raw.trimmingCharacters(in: .whitespaces)
        if t.isEmpty {
            Spacer().frame(height: 2)
        } else if t.hasPrefix("#") {
            Text(Self.inline(t.drop(while: { $0 == "#" }).trimmingCharacters(in: .whitespaces))).font(.subheadline.weight(.semibold))
        } else if t.hasPrefix("- ") || t.hasPrefix("* ") {
            Text(Self.inline("• " + t.dropFirst(2))).font(.callout)
        } else {
            Text(Self.inline(t)).font(.callout)
        }
    }

    static func inline(_ s: String) -> AttributedString {
        (try? AttributedString(markdown: s, options: .init(interpretedSyntax: .inlineOnlyPreservingWhitespace))) ?? AttributedString(s)
    }
}
