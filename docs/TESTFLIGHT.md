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

## Device check: recording from a locked phone

This answers the M2 question in [DESIGN.md](DESIGN.md#starting-cold-from-a-locked-phone): can a recording start on a locked phone, with akou not running, without the app coming forward? Only a real phone can prove it; the Simulator has no Action button and no lock.

Before you start:

- A TestFlight build is installed, a server and key are set and Test passes.
- You have made one recording from the app, so the microphone permission is granted.
- Live Activities are on for akou (Settings, akou, Live Activities).
- The record control is on the Action button (Settings, Action Button, Controls) and in Control Center, and the phone has been unlocked at least once since it booted.

For each entry point (the Action button, the Control Center control on the Lock Screen, and "Start recording in akou" through Siri or the Shortcuts action):

1. Unlock, swipe akou away in the app switcher so its process is gone, then lock the phone and wait 30 seconds.
2. Without unlocking, trigger the entry point. Speak for about 20 seconds.
3. Trigger it again (or "Stop recording in akou") to stop, still without unlocking.
4. Unlock and open akou.

It passes when all of these hold:

- No Face ID or passcode prompt appeared before recording started, and akou did not come to the screen.
- The recording indicator showed and akou's Live Activity appeared on the Lock Screen with the elapsed time running (and the last line, if the server has live text).
- After the stop, the Live Activity went away, and the recording is in the Library with about 20 seconds of your voice that plays back.

It fails if the phone asked to unlock first, if akou opened, if no Live Activity appeared, if a message said "Recording starts in the akou app" (the press ran in the widget extension) or "akou cannot record right now", or if the recording is missing, silent or shorter than you spoke.

Run it once more with Live Activities turned off for akou, and note what happens; Apple says the recording stops without one.

Write the result under "Device result" in DESIGN.md's M2 section: the date, the phone model, the iOS version, the build number, and pass or fail for each entry point with what you saw on a fail.
