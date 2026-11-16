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

## Code
- Previews use `org.jetbrains.compose.ui.tooling.preview.Preview`; never add androidx `ui-tooling` to `commonMain`.
- No GPL code. No emojis in UI text.
- Never commit secrets (`local.properties`, keystores, tokens, Telegram sessions, logs with chat data).
- Do not delete or overwrite user data: library rows, Telegram posts, phone files. Deleting posts is irreversible; a post counts as gone only when the owner's account confirms it.
- Bots cannot see posts made by other bots. "Not found" from a bot proves nothing.

## Git
- Short plain commit messages. No tool or AI mentions, no trailers.
- Do not push unless told. Pull with `git pull --rebase --autostash origin main` first. Never bump the version by hand; the release bot does.
- Do not edit files another person or agent is working on; keep them out of your commits.

## Docs
- Only `README.md`, `CHECKLIST.md`, `CHANGELOG.md` and `AGENTS.md` are tracked `.md` files.
- Keep `CHECKLIST.md` current: `[x]` seen working, `[~]` built but not tried, `[ ]` open.
- Local notes (`HANDOVER.md`, `CONTEXT.md`) stay git-ignored.
