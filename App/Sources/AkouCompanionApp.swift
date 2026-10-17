// SPDX-License-Identifier: GPL-3.0-or-later
import AkouClient
import SwiftUI

@main
struct AkouCompanionApp: App {
    // Opens the upload queue at launch and takes the system's wake for finished background uploads.
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @State private var selectedTab = Screen.record
    @AppStorage(ServerBook.defaultsKey) private var bookJSON = ""
    @AppStorage(DemoMode.welcomedKey) private var welcomed = false

    enum Screen: Hashable {
        case record
        case library
        case settings
    }

    init() {
        // The single server of earlier versions becomes the first of several, once.
        _ = ServerSettings.book
        let recorder = RecordingController.shared
        // "Record to" and its key from the Keychain; the recorder uses them for live text.
        recorder.recordTarget = { ServerSettings.recordTo }
        recorder.recordToID = { ServerSettings.book.recordTo }
        // Every finished file goes to the upload queue for the server it was recorded for, and
        // shows under the Library's waiting rows. One made in the demo stays in the demo.
        recorder.onFinished = { finished in
            if DemoMode.isDemo(finished.serverID) {
                DemoMode.keep(finished)
                Task { await LibraryStore.shared.refresh() }
                return
            }
            Task {
                await BackgroundUploader.shared.enqueue(
                    recordingID: finished.id.uuidString, file: finished.fileURL, title: finished.title,
                    workspace: finished.workspace, language: finished.language, serverID: finished.serverID
                )
                await LibraryStore.shared.loadWaiting()
            }
        }
        // The record intents run in this process; they reach the recorder through the host.
        RecordIntentHost.recorder = recorder
        // No recording outlives its process, so a "recording" left in the App Group by a killed app
        // is stale: clear it, or the record control would offer Stop for nothing.
        RecordingStatus.set(recording: false)
        DemoMode.start()
    }

    private var book: ServerBook { ServerBook.decode(bookJSON) ?? ServerBook() }

    var body: some Scene {
        WindowGroup {
            if !welcomed && book.servers.isEmpty {
                WelcomeView { choice in
                    switch choice {
                    case .addServer: selectedTab = .settings
                    case .demo:
                        DemoMode.enter()
                        selectedTab = .library
                    case .skip: selectedTab = .record
                    }
                    welcomed = true
                }
            } else {
                tabs
            }
        }
    }

    private var tabs: some View {
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
        .safeAreaInset(edge: .top, spacing: 0) {
            if book.servers.contains(where: DemoMode.isDemo) { DemoBanner() }
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
