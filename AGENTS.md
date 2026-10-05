# akou-companion: agent notes

An iPhone app that records on the phone and gets live text and the final transcript from an akou
server. The design is [docs/DESIGN.md](docs/DESIGN.md); the wire protocol is
[docs/PROTOCOL.md](docs/PROTOCOL.md) and must match akou's `GET /v1/live`.

## Layout

- `Package.swift`, `Sources/`, `Tests/AkouKitTests/`: AkouKit, the Swift package with everything that
  needs no phone (protocol types, libopus encoding and Ogg pages, the live client, the server probe).
- `Sources/COpusShim/`: C wrappers for `opus_encoder_ctl`, which is variadic and cannot be called from Swift.
- `App/`: the iOS app and its widget extension. `App/project.yml` is the XcodeGen spec; the
  `.xcodeproj` is generated and never committed. Settings are in `App/Config/*.xcconfig`.

## Build and test

```bash
swift test                                   # AkouKit, on a Mac
xcodegen generate --spec App/project.yml     # writes App/AkouCompanion.xcodeproj
xcodebuild build -project App/AkouCompanion.xcodeproj -scheme AkouCompanion \
  -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO
```

`Tests/AkouKitTests/Fixtures/ffmpeg-1s-16k.opus` is an Ogg Opus file made by ffmpeg's own muxer:
the Ogg tests read every page of it and must rebuild it byte for byte.

## Rules

- License GPL-3.0-or-later with the section 7 App Store permission in `NOTICE`; every new source file
  starts with `// SPDX-License-Identifier: GPL-3.0-or-later`.
- Dependencies are pinned exactly in `Package.swift`; actions are pinned to a commit SHA.
- No signing team id, key, server address or personal data in the repository. A local team id goes
  in `App/Config/Local.xcconfig`, which is ignored.
- A change to `Sources/AkouProtocol` changes `docs/PROTOCOL.md` in the same pull request.
- Conventional commits. Pull requests are never drafts.

<!-- BEGIN BEADS INTEGRATION v:1 profile:minimal hash:970c3bf2 -->
## Beads Issue Tracker

This project uses **bd (beads)** for issue tracking. Run `bd prime` to see full workflow context and commands.

### Quick Reference

```bash
bd ready              # Find available work
bd show <id>          # View issue details
bd update <id> --claim  # Claim work
bd close <id>         # Complete work
```

### Rules

- Use `bd` for ALL task tracking — do NOT use TodoWrite, TaskCreate, or markdown TODO lists
- Run `bd prime` for detailed command reference and session close protocol
- Use `bd remember` for persistent knowledge — do NOT use MEMORY.md files

**Architecture in one line:** issues live in a local Dolt DB; sync uses `refs/dolt/data` on your git remote; `.beads/issues.jsonl` is a passive export. See https://github.com/gastownhall/beads/blob/main/docs/SYNC_CONCEPTS.md for details and anti-patterns.

## Agent Context Profiles

The managed Beads block is task-tracking guidance, not permission to override repository, user, or orchestrator instructions.

- **Conservative (default)**: Use `bd` for task tracking. Do not run git commits, git pushes, or Dolt remote sync unless explicitly asked. At handoff, report changed files, validation, and suggested next commands.
- **Minimal**: Keep tool instruction files as pointers to `bd prime`; use the same conservative git policy unless active instructions say otherwise.
- **Team-maintainer**: Only when the repository explicitly opts in, agents may close beads, run quality gates, commit, and push as part of session close. A current "do not commit" or "do not push" instruction still wins.

## Session Completion

This protocol applies when ending a Beads implementation workflow. It is subordinate to explicit user, repository, and orchestrator instructions.

1. **File issues for remaining work** - Create beads for anything that needs follow-up
2. **Run quality gates** (if code changed) - Tests, linters, builds
3. **Update issue status** - Close finished work, update in-progress items
4. **Handle git/sync by active profile**:
   ```bash
   # Conservative/minimal/default: report status and proposed commands; wait for approval.
   git status

   # Team-maintainer opt-in only, unless current instructions forbid it:
   git pull --rebase
   bd dolt push
   git push
   git status
   ```
5. **Hand off** - Summarize changes, validation, issue status, and any blocked sync/commit/push step

**Critical rules:**
- Explicit user or orchestrator instructions override this Beads block.
- Do not commit or push without clear authority from the active profile or the current user request.
- If a required sync or push is blocked, stop and report the exact command and error.
<!-- END BEADS INTEGRATION -->
