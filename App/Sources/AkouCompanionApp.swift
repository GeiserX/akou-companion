// SPDX-License-Identifier: GPL-3.0-or-later
import SwiftUI

@main
struct AkouCompanionApp: App {
    // Opens the upload queue at launch and takes the system's wake for finished background uploads.
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    enum Tab: Hashable { case library, settings }
    @State private var tab: Tab = .library

    var body: some Scene {
        WindowGroup {
            TabView(selection: $tab) {
                // LibraryView holds its own NavigationStack, driven by LibraryRouter.
                LibraryView()
                    .tabItem { Label("Recordings", systemImage: "list.bullet") }
                    .tag(Tab.library)
                NavigationStack { SettingsView() }
                    .tabItem { Label("Settings", systemImage: "gear") }
                    .tag(Tab.settings)
            }
            // `akou-companion://recording/<job id>` from the widget opens that recording.
            .onOpenURL { url in
                if LibraryRouter.shared.open(url) { tab = .library }
            }
        }
    }
}
