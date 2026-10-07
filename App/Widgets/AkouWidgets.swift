// SPDX-License-Identifier: GPL-3.0-or-later
import ActivityKit
import SwiftUI
import WidgetKit

@main
struct AkouWidgets: WidgetBundle {
    var body: some Widget {
        RecordingLiveActivity()
        RecordControl()
        RecentRecordingsWidget()
    }
}

/// The recording's Live Activity: state, elapsed time and the last line of live text. A tap opens
/// the app on the running recording (`akou-companion://record`).
struct RecordingLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: RecordingAttributes.self) { context in
            RecordingLockScreenView(title: context.attributes.title, state: context.state)
                .widgetURL(DeepLink.record.url)
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    StateLabel(paused: context.state.paused).font(.caption)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    if !context.state.paused { Elapsed(state: context.state).font(.caption) }
                }
                DynamicIslandExpandedRegion(.bottom) {
                    LastLine(state: context.state).font(.footnote).lineLimit(2)
                }
            } compactLeading: {
                Image(systemName: context.state.paused ? "pause.circle.fill" : "record.circle")
                    .foregroundStyle(context.state.paused ? .orange : .red)
            } compactTrailing: {
                Elapsed(state: context.state).frame(maxWidth: 48)
            } minimal: {
                Image(systemName: context.state.paused ? "pause.circle.fill" : "record.circle")
                    .foregroundStyle(context.state.paused ? .orange : .red)
            }
            .widgetURL(DeepLink.record.url)
        }
    }
}

/// The Live Activity on the Lock Screen and in the notification banner.
struct RecordingLockScreenView: View {
    var title: String
    var state: RecordingAttributes.ContentState

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                StateLabel(paused: state.paused)
                Spacer()
                if !state.paused { Elapsed(state: state) }
            }
            .font(.headline)
            Text(title).font(.caption).foregroundStyle(.secondary).lineLimit(1)
            LastLine(state: state)
                .font(.footnote)
                .lineLimit(2)
        }
        .padding()
    }
}

private struct StateLabel: View {
    var paused: Bool

    var body: some View {
        Label(paused ? "Paused" : "Recording", systemImage: paused ? "pause.circle.fill" : "record.circle")
            .foregroundStyle(paused ? .orange : .red)
    }
}

/// A running clock while recording. Paused, the state carries no elapsed time to freeze, so the
/// clock gives way to a pause sign rather than keep counting.
private struct Elapsed: View {
    var state: RecordingAttributes.ContentState

    var body: some View {
        if state.paused {
            Image(systemName: "pause.fill")
        } else {
            Text(timerInterval: state.startedAt...Date.distantFuture, countsDown: false)
                .monospacedDigit()
        }
    }
}

/// The last line of live text, or a word on why there is none: the first line takes a few seconds,
/// live text can be off (a server with no live engine, or no network), and either way the final
/// transcript comes when the recording stops.
private struct LastLine: View {
    var state: RecordingAttributes.ContentState

    var body: some View {
        if !state.lastLine.isEmpty {
            Text(state.lastLine)
        } else if state.paused {
            Text("Tap to continue in akou.").foregroundStyle(.secondary)
        } else if !state.liveText {
            Text("No live text. The full transcript comes when you stop.").foregroundStyle(.secondary)
        } else {
            Text("Waiting for live text. The full transcript comes when you stop.").foregroundStyle(.secondary)
        }
    }
}
