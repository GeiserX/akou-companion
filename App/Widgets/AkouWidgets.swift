// SPDX-License-Identifier: GPL-3.0-or-later
import ActivityKit
import SwiftUI
import WidgetKit

@main
struct AkouWidgets: WidgetBundle {
    var body: some Widget {
        RecordingLiveActivity()
    }
}

/// The recording's Live Activity: state, elapsed time and the last line. A stub until milestone M2
/// starts it from the record control.
struct RecordingLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: RecordingAttributes.self) { context in
            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    Image(systemName: context.state.paused ? "pause.circle.fill" : "record.circle")
                    Text(context.state.paused ? "Paused" : "Recording")
                    Spacer()
                    Text(timerInterval: context.state.startedAt...Date.distantFuture, countsDown: false)
                        .monospacedDigit()
                }
                .font(.headline)
                if !context.state.lastLine.isEmpty {
                    Text(context.state.lastLine).font(.footnote).lineLimit(2)
                }
            }
            .padding()
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.center) {
                    Text(context.state.lastLine).font(.footnote).lineLimit(2)
                }
            } compactLeading: {
                Image(systemName: "record.circle")
            } compactTrailing: {
                Text(timerInterval: context.state.startedAt...Date.distantFuture, countsDown: false)
                    .monospacedDigit()
                    .frame(maxWidth: 48)
            } minimal: {
                Image(systemName: "record.circle")
            }
        }
    }
}
