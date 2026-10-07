// SPDX-License-Identifier: GPL-3.0-or-later
import UIKit

/// The two UIKit entry points SwiftUI has no equivalent for: the launch, which opens the upload
/// queue, and the system waking the app because background uploads ended.
final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        BackgroundUploader.shared.start()
        return true
    }

    func application(_ application: UIApplication, handleEventsForBackgroundURLSession identifier: String, completionHandler: @escaping () -> Void) {
        guard identifier == BackgroundUploader.sessionIdentifier else { return completionHandler() }
        BackgroundUploader.shared.handleEvents(completion: completionHandler)
    }
}
