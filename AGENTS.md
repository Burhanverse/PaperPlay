# PaperPlay Compose: rules for contributors and coding agents

Kotlin Multiplatform Compose app (Android + Windows desktop). Telegram bots and chats hold the music.
Read `README.md` and `CHECKLIST.md` before changing anything.

## Modules
- `core`: database, Telegram client, library, index, integrations
- `player`: playback engines (Media3 on Android, vlcj on desktop)
- `ui`: shared screens; `androidApp`, `desktopApp`: entry points

## Do not guess
- Read the code you change and the code that calls it. Search first, read line ranges.
- Reproduce or prove the cause (log line, test, stack trace) before fixing. A fix with no evidence is a guess.
- One change per commit. Keep diffs small; no drive-by refactors, no new dependencies without a reason.
- Never invent API fields (TDLib, Bot API). Check the schema or a live answer.

## Build and test
- Android: `JAVA_HOME=<JDK 17> ./gradlew :androidApp:assembleDebug --no-daemon -q`, filter output for `^e:` and `FAILED`.
- Tests: `./gradlew :core:jvmTest --no-daemon -q`. They must pass before a commit.
- Desktop run needs JDK 25.
- One Gradle at a time. Do not build in a tree another person or agent is editing; use a separate worktree.
- Run `git status` before a build or release: a build copies every changed file, so another editor's unsaved work would be in it.
- `scripts\release.ps1` builds the Android release (installed on the phone when one is connected) and the Windows installer.
- All core tests pass (`:core:jvmTest`). Run only the tests asked for when told not to build.
- Do not stop Gradle or Java processes you did not start; another agent may be building.
- Before trusting a release script run, check `adb devices`: with no phone connected the script skips the phone install and says so.

## Code
- Previews use `org.jetbrains.compose.ui.tooling.preview.Preview`; never add androidx `ui-tooling` to `commonMain`.
- No GPL code. No emojis in UI text.
- Never commit secrets (`local.properties`, keystores, tokens, Telegram sessions, logs with chat data).
- Do not delete or overwrite user data: library rows, Telegram posts, phone files. Deleting posts is irreversible; a post counts as gone only when the owner's account confirms it.
- Bots cannot see posts made by other bots. "Not found" from a bot proves nothing.

## Audio engine
- Own clean-room code only. Never copy, port or bundle code, binaries or assets from another app's APK; reading one to learn a technique is fine, the code is written from the technique.
- Third-party code only under MIT, Apache-2.0 or BSD. No GPL; LGPL only dynamically linked and only after asking.
- The Rust DSP core lives in `engine/` (tracked). `native/` is gitignored (TDLib), so do not put it there. Built `.so` files are not committed.
- Build with `cargo ndk -t arm64-v8a`, before Gradle and never at the same time as it.
- No allocation, locks or panics in the audio path; no panic across the FFI line. If the library fails to load, fall back to the Media3 pipeline and log it.
- Sound changes are judged by ear on the phone, level matched, against the previous build; mark `[~]` in `CHECKLIST.md` until heard working.
- Plan and status live in the local `ENGINE_PLAN.md` (ignored); keep `CHECKLIST.md` in step.

## UI
- Match the neighbours first: copy the row, header, spacing and icon style of the nearest existing screen or list instead of inventing a new one. Reuse the shared composables and theme tokens (`MaterialTheme.colorScheme`, `AppIcon`, `LocalUiPrefs`); no hard-coded colours.
- Every navigation row, setting and tool has an icon from the Material rounded set, and the same icon means the same thing everywhere.
- A collapsible group is a normal row with an icon and a fold arrow, not a bare title. It must still show and work when the sidebar is narrow.
- Separate groups with a divider or a section label, never by position alone. Settings and About sit apart from content and tools.
- Highlight the open page in every list that can open pages.
- Sidebar and settings headings follow the same groups; a new page names its group.
- Lay out for both phone and desktop widths; nothing may clip, overlap or need a horizontal scroll.
- Buttons and actions say what they do in plain words; no emojis, and a long action is confirmed or can be undone.
- UI changes: update `CHECKLIST.md` in the same change, and mark `[~]` until seen working.

## Behaviour that must hold
- No touch goes through anything: a sheet, bar or overlay takes the touches that land on it (`Modifier.blockTouchesBehind()` in `ui/.../app/BlockTouches.kt`); a list behind the player must never react.
- A song in a credit "A, B & C" is several artists; use `splitArtists` (core `metadata/Text.kt`) wherever artists are listed, linked or matched.
- A cover or album match is never taken from a compilation ("Bravo Hits", "Top 100"): use `isCompilationAlbum` and `pickExternal(..., allowCompilation = false)` for covers.
- Data that two devices both have is merged by one pure rule that gives the same answer on both sides (`remoteTagsWin`, `remoteLyricsWin`, `mergeSongData`); what was made by hand wins over what was found.
- Rewind: one press restarts the song, a second within 2.5 s goes back, the first song only restarts.

## Git
- Short plain commit messages. No tool or AI mentions, no trailers.
- Do not push unless told. Pull with `git pull --rebase --autostash origin main` first. Never bump the version by hand; the release bot does.
- Do not edit files another person or agent is working on; keep them out of your commits.

## Docs
- Only `README.md`, `CHECKLIST.md`, `CHANGELOG.md` and `AGENTS.md` are tracked `.md` files.
- Keep `CHECKLIST.md` current: `[x]` seen working, `[~]` built but not tried, `[ ]` open.
- Local notes (`HANDOVER.md`, `CONTEXT.md`) stay git-ignored.
