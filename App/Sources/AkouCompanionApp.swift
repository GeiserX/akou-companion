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
        // The record intents run in this process; they reach the recorder through the host.
        RecordIntentHost.recorder = RecordingController.shared
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
                switch DeepLink(url) {
                case .record: selectedTab = .record
                case .recording: if LibraryRouter.shared.open(url) { selectedTab = .library }
                case nil: break
                }
            }
        }
    }
}
