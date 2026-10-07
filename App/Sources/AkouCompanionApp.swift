// SPDX-License-Identifier: GPL-3.0-or-later
import SwiftUI

@main
struct AkouCompanionApp: App {
    var body: some Scene {
        WindowGroup {
            TabView {
                NavigationStack { RecordView() }
                    .tabItem { Label("Record", systemImage: "record.circle") }
                NavigationStack { SettingsView() }
                    .tabItem { Label("Settings", systemImage: "gear") }
            }
        }
    }
}
