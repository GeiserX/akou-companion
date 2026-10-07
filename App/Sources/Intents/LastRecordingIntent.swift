// SPDX-License-Identifier: GPL-3.0-or-later
import AkouClient
import AppIntents
import Foundation
import SwiftUI
#if canImport(FoundationModels)
import FoundationModels
#endif

/// "What was my last akou recording?": the title, when, how long, the workspace and a short summary.
/// It reads the recent-recordings snapshot the app keeps, so it needs no network. The akou server
/// has no summary route, so the summary is made on this iPhone: by Apple's on-device model where
/// Apple Intelligence is available (iOS 26), else the transcript's opening sentences. Nothing is
/// sent anywhere.
struct LastRecordingIntent: AppIntent {
    static var title: LocalizedStringResource { "Last recording summary" }
    static var description: IntentDescription {
        IntentDescription("Tells you about your latest akou recording: its title, when you made it, how long it is, its workspace and a short summary made on this iPhone.")
    }

    init() {}

    func perform() async throws -> some IntentResult & ProvidesDialog & ShowsSnippetView {
        guard let last = RecentSnapshot.read()?.recordings.first else {
            return .result(dialog: "You have no akou recordings yet.", view: LastRecordingSnippet(recording: nil, summary: nil))
        }
        let summary = await RecordingSummary.make(from: last.opening)
        return .result(
            dialog: IntentDialog(stringLiteral: RecordingSummary.spoken(last, summary: summary?.text)),
            view: LastRecordingSnippet(recording: last, summary: summary)
        )
    }
}

/// A short summary of a transcript's opening, made on the phone.
struct RecordingSummary: Sendable, Equatable {
    var text: String
    /// True when Apple's on-device model wrote it; false when it is the opening sentences.
    var fromModel: Bool

    static func make(from opening: String) async -> RecordingSummary? {
        guard !opening.isEmpty else { return nil }
        #if canImport(FoundationModels)
        if #available(iOS 26.0, *), let text = await onDevice(opening) {
            return RecordingSummary(text: text, fromModel: true)
        }
        #endif
        return RecordingSummary(text: TranscriptDigest.opening(text: opening, limit: 200), fromModel: false)
    }

    #if canImport(FoundationModels)
    @available(iOS 26.0, *)
    private static func onDevice(_ opening: String) async -> String? {
        let model = SystemLanguageModel.default
        guard case .available = model.availability else { return nil }
        let session = LanguageModelSession(
            model: model,
            instructions: "Summarize the start of this transcript of a recording in one or two short sentences, in the transcript's own language. Say only what it says; add nothing."
        )
        guard let answer = try? await session.respond(to: opening) else { return nil }
        let text = answer.content.trimmingCharacters(in: .whitespacesAndNewlines)
        return text.isEmpty ? nil : text
    }
    #endif

    /// What Siri says: "Weekly sync, recorded 2 hours ago, 34 minutes, in Work. <summary>"
    static func spoken(_ r: RecentRecording, summary: String?) -> String {
        var parts = ["\(r.title), recorded \(r.createdAt.formatted(.relative(presentation: .named)))"]
        if let d = r.duration { parts.append(length(d)) }
        if let w = r.workspace, !w.isEmpty { parts.append("in \(w)") }
        var line = parts.joined(separator: ", ") + "."
        if r.status != "done" { line += " Its transcript is \(r.status)." }
        if let summary { line += " " + summary }
        return line
    }

    static func length(_ seconds: TimeInterval) -> String {
        Duration.seconds(seconds.rounded()).formatted(.units(allowed: [.hours, .minutes, .seconds], width: .wide, maximumUnitCount: 2))
    }
}

struct LastRecordingSnippet: View {
    var recording: RecentRecording?
    var summary: RecordingSummary?

    var body: some View {
        if let r = recording {
            VStack(alignment: .leading, spacing: 6) {
                Text(r.title).font(.headline).lineLimit(2)
                Text(([r.createdAt.formatted(.relative(presentation: .named))]
                    + [r.duration.map(RecordingSummary.length), r.workspace].compactMap { $0 }.filter { !$0.isEmpty })
                    .joined(separator: " · "))
                .font(.caption)
                .foregroundStyle(.secondary)
                if let summary {
                    Text(summary.text).font(.callout)
                    Text(summary.fromModel ? "Summary made on this iPhone" : "Opening of the transcript")
                        .font(.caption2)
                        .foregroundStyle(.tertiary)
                } else if r.status != "done" {
                    Text("Transcript \(r.status)").font(.callout).foregroundStyle(.secondary)
                }
            }
            .padding()
        } else {
            Text("No recordings yet").padding()
        }
    }
}
