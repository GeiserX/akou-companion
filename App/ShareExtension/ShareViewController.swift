// SPDX-License-Identifier: GPL-3.0-or-later
import AkouClient
import UIKit

/// The share sheet's "akou": takes the audio another app shares (a voice memo, a file, a WhatsApp
/// voice note), copies it into the App Group's inbox with its sidecar (`SharedInbox`) and closes.
/// It holds no key and makes no request; the app queues the file the next time it opens, and the
/// upload goes to "Record to" like any recording.
final class ShareViewController: UIViewController {
    /// The App Group the app and its extensions share (`Config/Share.entitlements`).
    static let appGroup = "group.io.github.geiserx.akou-companion"

    private let label = UILabel()

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground
        label.text = "Adding to akou…"
        label.font = .preferredFont(forTextStyle: .headline)
        label.textAlignment = .center
        label.numberOfLines = 0
        label.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(label)
        NSLayoutConstraint.activate([
            label.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            label.centerYAnchor.constraint(equalTo: view.centerYAnchor),
            label.leadingAnchor.constraint(greaterThanOrEqualTo: view.layoutMarginsGuide.leadingAnchor),
            label.trailingAnchor.constraint(lessThanOrEqualTo: view.layoutMarginsGuide.trailingAnchor),
        ])
        Task { await take() }
    }

    private func take() async {
        guard let container = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: Self.appGroup) else {
            return fail("akou cannot reach its shared folder.")
        }
        let inbox = SharedInbox.directory(in: container)
        let providers = (extensionContext?.inputItems as? [NSExtensionItem] ?? []).flatMap { $0.attachments ?? [] }
        var added = 0
        for provider in providers {
            guard let type = SharedInbox.audioType(of: provider.registeredTypeIdentifiers) else { continue }
            if (try? await copy(provider, type: type, to: inbox)) != nil { added += 1 }
        }
        guard added > 0 else { return fail("akou found no audio it can take here.") }
        extensionContext?.completeRequest(returningItems: nil)
    }

    /// The system's copy of the file lives only inside the handler, so it is copied there.
    private func copy(_ provider: NSItemProvider, type: String, to inbox: URL) async throws {
        let name = provider.suggestedName
        try await withCheckedThrowingContinuation { (done: CheckedContinuation<Void, Error>) in
            _ = provider.loadFileRepresentation(forTypeIdentifier: type) { url, error in
                guard let url else { return done.resume(throwing: error ?? CocoaError(.fileReadUnknown)) }
                do {
                    try SharedInbox.add(file: url, typeIdentifier: type, suggestedName: name, to: inbox)
                    done.resume()
                } catch {
                    done.resume(throwing: error)
                }
            }
        }
    }

    private func fail(_ why: String) {
        label.text = why
        extensionContext?.cancelRequest(withError: CocoaError(.fileReadUnknown, userInfo: [NSLocalizedDescriptionKey: why]))
    }
}
