// SPDX-License-Identifier: GPL-3.0-or-later
import SwiftUI

@main
struct AkouCompanionApp: App {
    // Opens the upload queue at launch and takes the system's wake for finished background uploads.
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @State private var selectedTab = Screen.record

    enum Screen: Hashable {
        case record
        case library
        case settings
    }

    init() {
        let recorder = RecordingController.shared
        // The settings keep the key in the Keychain; the recorder reads it there for live text.
        recorder.serverKey = { KeyStore.load() }
        // Every finished file goes to the upload queue, and shows under the Library's waiting rows.
        recorder.onFinished = { finished in
            Task {
                await BackgroundUploader.shared.enqueue(
                    recordingID: finished.id.uuidString, file: finished.fileURL, title: finished.title,
                    workspace: finished.workspace, language: finished.language
                )
                await LibraryStore.shared.loadWaiting()
            }
        }
        // The record intents run in this process; they reach the recorder through the host.
        RecordIntentHost.recorder = recorder
        // No recording outlives its process, so a "recording" left in the App Group by a killed app
        // is stale: clear it, or the record control would offer Stop for nothing.
        RecordingStatus.set(recording: false)
    }

    var body: some Scene {
        WindowGroup {
            TabView(selection: $selectedTab) {
                NavigationStack { RecordView() }
                    .tabItem { Label("Record", systemImage: "record.circle") }
                    .tag(Screen.record)
                // LibraryView holds its own NavigationStack, driven by LibraryRouter.
                LibraryView()
                    .tabItem { Label("Recordings", systemImage: "list.bullet") }
                    .tag(Screen.library)
                NavigationStack { SettingsView() }
                    .tabItem { Label("Settings", systemImage: "gear") }
                    .tag(Screen.settings)
            }
            // The Live Activity opens akou-companion://record, a recent-recordings row
            // akou-companion://recording/<job id>, which the Library opens on that recording.
            .onOpenURL { url in
                if let screen = Self.screen(for: url) { selectedTab = screen }
            }
        }
    }

    /// The tab a link opens; a recording link also puts that recording on the Library's stack.
    static func screen(for url: URL) -> Screen? {
        switch DeepLink(url) {
        case .record: .record
        case .recording: LibraryRouter.shared.open(url) ? .library : nil
        case nil: nil
        }
    }
}
