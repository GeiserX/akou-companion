# Privacy policy: akou for iPhone

Last updated: 17 October 2026

akou for iPhone (the app) is a client for an akou server that you run yourself. It records on your iPhone and sends the recording to that server for transcription. The developer runs no server for the app, receives none of your data and cannot see it.

## What the app sends, and where

- **To the akou servers you add in Settings, and nowhere else.** The app sends the audio you record, the title, workspace and language you choose for it, and your server key. It reads back your recordings, their transcripts and their audio. You set the address of each server, which usually sits on your own network or your own tailnet.
- **Nothing to the developer or to anyone else.** The app has no analytics, no advertising, no tracking, no crash reporting service and no account.

## What stays on your iPhone

- Recordings wait on the phone until your server confirms that it kept them. Then they are deleted, unless you turn on "Keep a copy on this phone".
- Each server key is kept in the iOS Keychain, on this device only. It is never synced or backed up to another device.
- The server list, your workspaces and your settings are kept in the app's own storage. The recent-recordings widget reads a short list of your latest recordings from storage that only the app and its widget share.
- The "last recording" summary is made on the phone by Apple's on-device model where your iPhone has one. Otherwise it uses the transcript's opening sentences.
- The demo runs entirely inside the app. Its sample recordings ship with the app, and anything you record in the demo stays on the phone until you leave the demo or the app starts again.

## Permissions

- **Microphone:** to record. Only while you are recording.
- **Local network:** to reach a server on your own network.

## Your akou server

What happens to a recording after it reaches your server is decided by your server, which you control. See the akou project: https://github.com/GeiserX/akou

## Contact

Questions about this policy: https://github.com/GeiserX/akou-companion/issues
