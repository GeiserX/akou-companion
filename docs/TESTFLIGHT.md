# Putting a build on TestFlight

The steps to get akou-companion from this repository onto your own iPhone through TestFlight. They need an Apple Developer Program membership and are done by hand, once per account (steps 1 to 4) and then once per build (steps 5 to 9). Replace `<your team id>` with the ten-character Team ID shown under Membership details in your developer account; it never goes into the repository.

## Once per account

1. **Register the two App IDs** at developer.apple.com under Certificates, Identifiers and Profiles, Identifiers, for the team you will publish under (check the team switcher first):
   - `io.github.geiserx.akou-companion` for the app
   - `io.github.geiserx.akou-companion.widgets` for the widget extension

   If you publish under your own identifiers, change `BUNDLE_ID_BASE` in [`App/Config/Base.xcconfig`](../App/Config/Base.xcconfig) to match before anything else; both targets derive their identifiers from it.
2. **Register the App Group** `group.io.github.geiserx.akou-companion` (Identifiers, App Groups) and turn on the App Groups capability with that group on both App IDs. The app and the widget extension share it from M2 for the record control, and in M3 for the recent-recordings snapshot.
3. **Create the app in App Store Connect** (Apps, New App): platform iOS, name of your choice, the app's bundle ID (`io.github.geiserx.akou-companion`, or your `BUNDLE_ID_BASE` if you changed it in step 1), any SKU. TestFlight needs this record even if the app never goes to the App Store.
4. **Set your team locally.** Create `App/Config/Local.xcconfig` (it is ignored by git) with one line:

   ```text
   DEVELOPMENT_TEAM = <your team id>
   ```

## Once per build

5. **Raise the build number.** Every upload needs a new `CURRENT_PROJECT_VERSION` in `App/Config/Base.xcconfig`; `MARKETING_VERSION` changes only for a new version.
6. **Generate and archive.**

   ```bash
   xcodegen generate --spec App/project.yml
   open App/AkouCompanion.xcodeproj
   ```

   In Xcode choose the `AkouCompanion` scheme and the destination Any iOS Device (arm64), then Product, Archive. Automatic signing creates the provisioning profiles for both targets on first use.
7. **Upload.** In the Organizer window select the archive, then Distribute App, App Store Connect, Upload. The app's only encryption is the system's HTTPS, and `ITSAppUsesNonExemptEncryption` is `false` in its Info.plist, so App Store Connect does not ask the export compliance question.
8. **Wait for processing**, usually a few minutes to half an hour; App Store Connect emails when the build is ready.
9. **Install.** In App Store Connect, TestFlight, add yourself to an internal testing group and enable the build for it. Internal testers need no review. Open the TestFlight app on the iPhone, signed in with the same Apple Account, and install akou.

## After the first install

- In akou's settings enter your server's URL and an `ak_` key from the server's Keys page or `akou keys create`, then press Test.
- To record from the Action button (from M2): Settings, Action Button, Controls, then pick akou's record control.
- External testers (people outside your team) need a beta review of the first build added to their group. App Store Connect also asks for a privacy policy URL before external testing or an App Store release.
