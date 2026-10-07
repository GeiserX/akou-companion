// SPDX-License-Identifier: GPL-3.0-or-later
import AkouClient
import AkouProtocol
import SwiftUI

/// The record screen: a workspace for this recording, the big record button, the elapsed time and
/// the live text.
struct RecordView: View {
    @ObservedObject private var recorder = RecordingController.shared
    @AppStorage("workspaces") private var workspacesJSON = "[]"
    @AppStorage("defaultWorkspace") private var defaultWorkspace = ""
    @AppStorage("serverURL") private var serverURL = ""
    @State private var workspace = ""
    @State private var serverNote: String?
    @State private var error: String?

    private var workspaces: [String] {
        (try? JSONDecoder().decode([String].self, from: Data(workspacesJSON.utf8))) ?? []
    }

    var body: some View {
        VStack(spacing: 16) {
            if !workspaces.isEmpty {
                Picker("Workspace", selection: $workspace) {
                    Text("No workspace").tag("")
                    ForEach(workspaces, id: \.self) { Text($0).tag($0) }
                }
                .pickerStyle(.menu)
                .disabled(recorder.state != .idle)
            }

            elapsed
                .font(.system(size: 48, weight: .light, design: .rounded))
                .monospacedDigit()

            HStack(spacing: 32) {
                if recorder.state == .paused || isRecording {
                    Button(recorder.state == .paused ? "Resume" : "Pause", systemImage: recorder.state == .paused ? "play.fill" : "pause.fill") {
                        recorder.state == .paused ? recorder.resume() : recorder.pause()
                    }
                    .labelStyle(.iconOnly)
                    .font(.title)
                }
                Button(action: toggle) {
                    Image(systemName: isActive ? "stop.circle.fill" : "record.circle")
                        .resizable()
                        .frame(width: 96, height: 96)
                        .foregroundStyle(.red)
                }
                .accessibilityLabel(isActive ? "Stop recording" : "Record")
                .disabled(recorder.state == .stopping)
            }

            if let message = error ?? recorder.lastError {
                Text(message).font(.footnote).foregroundStyle(.red)
            }
            if let serverNote, !isActive {
                Text(serverNote).font(.footnote).foregroundStyle(.secondary)
            }

            LiveTextView(transcript: recorder.transcript, state: isActive ? recorder.liveState : .off(reason: "idle"))
        }
        .padding()
        .navigationTitle("Record")
        .onAppear { if workspace.isEmpty, workspaces.contains(defaultWorkspace) { workspace = defaultWorkspace } }
        .task(id: serverURL) { await checkServer() }
    }

    private var isRecording: Bool {
        if case .recording = recorder.state { true } else { false }
    }

    private var isActive: Bool { isRecording || recorder.state == .paused || recorder.state == .stopping }

    @ViewBuilder private var elapsed: some View {
        switch recorder.state {
        case let .recording(startedAt):
            Text(timerInterval: startedAt...Date.distantFuture, countsDown: false)
        case .paused, .stopping:
            Text(Duration.seconds(recorder.elapsedBeforePause).formatted(.time(pattern: .minuteSecond)))
        case .idle:
            Text("0:00")
        }
    }

    private func toggle() {
        error = nil
        Task {
            if isActive {
                _ = await recorder.stop()
            } else {
                do {
                    try await recorder.start(workspace: workspace.isEmpty ? nil : workspace, title: nil)
                } catch RecordingController.Failure.microphoneDenied {
                    error = "akou needs the microphone. Allow it in Settings, Privacy, Microphone."
                } catch {
                    self.error = "Could not start: \(error.localizedDescription)"
                }
            }
        }
    }

    /// Whether this server shows live text, so the screen can say so before recording.
    private func checkServer() async {
        let trimmed = serverURL.trimmingCharacters(in: .whitespaces)
        guard let url = URL(string: trimmed), !trimmed.isEmpty, let key = recorder.serverKey(), !key.isEmpty else {
            recorder.serverSupportsLive = nil
            serverNote = "No server set: recordings stay on the phone, without live text."
            return
        }
        do {
            let info = try await ServerProbe(baseURL: url, key: key).server()
            recorder.serverSupportsLive = info.supportsLive
            serverNote = info.supportsLive ? nil : "No live text on this server: the app records, and the transcript comes after the upload."
        } catch {
            recorder.serverSupportsLive = nil
            serverNote = "The server did not answer; live text will try when you record."
        }
    }
}
