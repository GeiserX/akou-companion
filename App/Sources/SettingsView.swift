// SPDX-License-Identifier: GPL-3.0-or-later
import AkouClient
import AkouProtocol
import SwiftUI

/// The server settings and their test: `GET /v1/server` and `GET /v1/keys/me` on the URL given.
/// Skeleton: the key is held in memory only; milestone M1 stores it in the Keychain.
struct SettingsView: View {
    @AppStorage("serverURL") private var serverURL = ""
    @State private var key = ""
    @State private var result: String?
    @State private var testing = false

    var body: some View {
        Form {
            Section("akou server") {
                TextField("https://akou.example.com", text: $serverURL)
                    .textContentType(.URL)
                    .keyboardType(.URL)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                SecureField("ak_ key", text: $key)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
            }
            Section {
                Button(testing ? "Testing…" : "Test") { Task { await test() } }
                    .disabled(testing || serverURL.isEmpty || key.isEmpty)
                if let result {
                    Text(result).font(.footnote)
                }
            }
        }
        .navigationTitle("akou")
    }

    private func test() async {
        testing = true
        defer { testing = false }
        guard let url = URL(string: serverURL.trimmingCharacters(in: .whitespaces)) else {
            result = "That is not a URL."
            return
        }
        let probe = ServerProbe(baseURL: url, key: key)
        do {
            let info = try await probe.server()
            let me = try await probe.keyInfo()
            let live = info.supportsLive ? "live text available" : "no live text on this server"
            result = "akou \(info.version ?? "?") (\(info.mode ?? "?")), key \(me.name ?? me.id), \(live)"
        } catch let f as Endpoint.Failure {
            result = "Refused: \(f). Use https, or plain http only to a private address."
        } catch {
            result = "Failed: \(error)"
        }
    }
}
