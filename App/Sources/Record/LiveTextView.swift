// SPDX-License-Identifier: GPL-3.0-or-later
import AkouClient
import SwiftUI

/// The live transcript: closed lines, the growing line in grey, and a marker for every stretch the
/// live text missed, which the final transcript fills.
struct LiveTextView: View {
    let transcript: LiveTranscript
    let state: LiveSession.State

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let status { Text(status).font(.footnote).foregroundStyle(.secondary) }
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 6) {
                        ForEach(Array(transcript.items.enumerated()), id: \.offset) { _, item in
                            switch item {
                            case let .line(line):
                                Text(line.text)
                            case let .gap(from, to):
                                Label(LiveTranscript.gapLabel(from: from, to: to), systemImage: "waveform.slash")
                                    .font(.footnote.italic())
                                    .foregroundStyle(.secondary)
                            }
                        }
                        if let open = transcript.openLine {
                            Text(open.text).foregroundStyle(.secondary)
                        }
                        Color.clear.frame(height: 1).id("end")
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .onChange(of: transcript) { proxy.scrollTo("end", anchor: .bottom) }
            }
        }
    }

    private var status: String? {
        switch state {
        case .connecting: "Connecting for live text…"
        case .live: nil
        case .paused: "Live text paused, reconnecting. The recording goes on."
        case let .off(reason):
            switch reason {
            case "idle", "stopped", "cancelled": nil
            case "no_server": "No live text: set the server and key in Settings. The recording goes on."
            case "no_live_engine", "no_live_route": "No live text on this server. The transcript comes after the upload."
            case "engine_busy": "Live text is busy with another engine on the server. The transcript comes after the upload."
            default: "No live text (\(reason)). The transcript comes after the upload."
            }
        }
    }
}
