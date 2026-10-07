// SPDX-License-Identifier: GPL-3.0-or-later
import SwiftUI
import WidgetKit

/// The latest recordings on the Home Screen and the Lock Screen, from the snapshot the app keeps.
/// Each one opens the app on that recording.
struct RecentRecordingsWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: RecentSnapshot.widgetKind, provider: RecentProvider()) { entry in
            RecentRecordingsView(entry: entry)
                .containerBackground(.fill.tertiary, for: .widget)
        }
        .configurationDisplayName("Recent recordings")
        .description("Your latest akou recordings and how each one starts.")
        .supportedFamilies([.systemSmall, .systemMedium, .accessoryRectangular])
    }
}

struct RecentEntry: TimelineEntry {
    var date: Date
    var recordings: [RecentRecording]
}

struct RecentProvider: TimelineProvider {
    func placeholder(in context: Context) -> RecentEntry {
        RecentEntry(date: .now, recordings: RecentRecording.samples)
    }

    func getSnapshot(in context: Context, completion: @escaping @Sendable (RecentEntry) -> Void) {
        let saved = RecentSnapshot.read()?.recordings ?? []
        completion(RecentEntry(date: .now, recordings: context.isPreview && saved.isEmpty ? RecentRecording.samples : saved))
    }

    /// The app reloads the timeline whenever it writes the snapshot; the hourly refresh only keeps
    /// the relative times honest.
    func getTimeline(in context: Context, completion: @escaping @Sendable (Timeline<RecentEntry>) -> Void) {
        let entry = RecentEntry(date: .now, recordings: RecentSnapshot.read()?.recordings ?? [])
        completion(Timeline(entries: [entry], policy: .after(.now.addingTimeInterval(3600))))
    }
}

struct RecentRecordingsView: View {
    @Environment(\.widgetFamily) private var environmentFamily
    var entry: RecentEntry
    /// Set only to draw the view outside a widget (previews, screenshots).
    var familyOverride: WidgetFamily?

    private var family: WidgetFamily { familyOverride ?? environmentFamily }

    var body: some View {
        if let first = entry.recordings.first {
            switch family {
            case .systemMedium:
                VStack(alignment: .leading, spacing: 6) {
                    ForEach(entry.recordings.prefix(3)) { r in
                        Link(destination: DeepLink.recording(jobId: r.jobId).url) {
                            RecentRow(recording: r, showOpening: entry.recordings.count == 1)
                        }
                    }
                    Spacer(minLength: 0)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            case .accessoryRectangular:
                VStack(alignment: .leading) {
                    Text(first.title).font(.headline).lineLimit(1)
                    Text(first.opening.isEmpty ? when(first) : first.opening).font(.caption).lineLimit(2)
                }
                .widgetURL(DeepLink.recording(jobId: first.jobId).url)
            default:
                VStack(alignment: .leading, spacing: 4) {
                    Text(first.title).font(.headline).lineLimit(2)
                    Text(when(first)).font(.caption2).foregroundStyle(.secondary)
                    Text(first.opening).font(.caption).lineLimit(4)
                    Spacer(minLength: 0)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .widgetURL(DeepLink.recording(jobId: first.jobId).url)
            }
        } else {
            VStack(spacing: 4) {
                Image(systemName: "waveform")
                Text("No recordings yet").font(.caption)
            }
            .foregroundStyle(.secondary)
        }
    }
}

struct RecentRow: View {
    var recording: RecentRecording
    var showOpening: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 1) {
            HStack {
                Text(recording.title).font(.subheadline.weight(.semibold)).lineLimit(1)
                Spacer(minLength: 4)
                Text(when(recording)).font(.caption2).foregroundStyle(.secondary).lineLimit(1)
            }
            if !recording.opening.isEmpty {
                Text(recording.opening).font(.caption).foregroundStyle(.secondary).lineLimit(showOpening ? 4 : 1)
            } else if recording.status != "done" {
                Text("Transcript \(recording.status)").font(.caption).foregroundStyle(.secondary)
            }
        }
        // A Link would tint its label with the accent colour; rows read as text.
        .foregroundStyle(Color.primary)
    }
}

/// "2 hours ago · 34 min"
private func when(_ r: RecentRecording) -> String {
    var s = r.createdAt.formatted(.relative(presentation: .named))
    if let d = r.duration {
        s += " · " + Duration.seconds(d.rounded()).formatted(.units(allowed: [.hours, .minutes, .seconds], width: .abbreviated, maximumUnitCount: 1))
    }
    return s
}

extension RecentRecording {
    /// Placeholder content for the widget gallery.
    static let samples: [RecentRecording] = [
        RecentRecording(jobId: "sample-1", title: "Weekly planning", workspace: nil, createdAt: .now.addingTimeInterval(-2 * 3600), duration: 34 * 60, status: "done", opening: "We start with the release. The phone build goes to TestFlight on Friday."),
        RecentRecording(jobId: "sample-2", title: "Voice note", workspace: nil, createdAt: .now.addingTimeInterval(-26 * 3600), duration: 95, status: "done", opening: "Remember to book the room for Tuesday."),
        RecentRecording(jobId: "sample-3", title: "Interview", workspace: nil, createdAt: .now.addingTimeInterval(-3 * 86400), duration: 52 * 60, status: "done", opening: "Thanks for coming in today."),
    ]
}
