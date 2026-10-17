// SPDX-License-Identifier: GPL-3.0-or-later
import SwiftUI

/// The first screen of a phone with no server: pair with an akou server, try the demo, or record
/// without a server for now.
struct WelcomeView: View {
    enum Choice {
        case addServer
        case demo
        case skip
    }

    let choose: (Choice) -> Void

    var body: some View {
        VStack(spacing: 24) {
            Spacer()
            Image(systemName: "waveform.circle.fill")
                .font(.system(size: 72))
                .foregroundStyle(.red)
                .accessibilityHidden(true)
            Text("akou").font(.largeTitle.bold())
            Text("Record on your iPhone and read the words while you speak. Your own akou server makes the transcript: nothing goes to anyone else's cloud.")
                .multilineTextAlignment(.center)
                .foregroundStyle(.secondary)
            Spacer()
            VStack(spacing: 12) {
                Button {
                    choose(.addServer)
                } label: {
                    Text("Add my akou server").frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                Button {
                    choose(.demo)
                } label: {
                    Text("Try the demo").frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                Text("The demo has sample recordings and a server that lives inside the app. Nothing is sent anywhere.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                Button("Record without a server for now") { choose(.skip) }
                    .font(.footnote)
            }
            .controlSize(.large)
        }
        .padding(32)
    }
}

/// Shown above every screen while the demo is on, with the way out.
struct DemoBanner: View {
    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: "play.rectangle.on.rectangle").accessibilityHidden(true)
            Text("Demo: sample data on this iPhone")
            Spacer()
            Button("Leave demo") { DemoMode.leave() }
                .buttonStyle(.bordered)
                .controlSize(.small)
        }
        .font(.footnote)
        .padding(.horizontal)
        .padding(.vertical, 6)
        .background(Color.orange.opacity(0.18))
    }
}
