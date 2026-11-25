# PaperPlay

A music player for Android and desktop that keeps your music in **your own Telegram bot and chats**. You bring a Telegram
API id, API hash and bot token; PaperPlay scans your chats, builds a library and plays the songs. Nothing is sent to any
other service.

Built with Kotlin and Compose Multiplatform.

> **Status:** everything compiles and the apps build in CI. Playing, scanning, indexing and deleting duplicates have been tried on a
> phone; many other features have not been tried on a real device yet.
> [`CHECKLIST.md`](CHECKLIST.md) lists what is done, half done and not started.

## What it does

**Your library**
- Reads songs from the chats and channels you choose, including forum topics. One **Scan library** page (Profile > Library)
  reads the main chat with each source you switch on (the main bot, extra bots, your own account) and shows the result.
  The topics of the main chat also appear as lists under Playlists, on Home and in the desktop sidebar, apart from your playlists.
- Your own account can be added (phone, code, password or QR) to index chats, copy songs into the main chat, and play
  songs the bot cannot see.
- **Chat index** (Settings > Telegram > Chats and channels): each indexed chat is saved on the device with its song count, when it
  was last read and whether the whole history was read, and can be opened in Telegram or removed. A read keeps its place after every
  page, so pausing it or closing the app does not start it over, and an indexed chat only reads newer posts. While a PC is linked, the
  phone lets it do the reading; when the PC does not answer, the phone reads by itself. Indexing carries on in the
  background and shows as a task notification. Posts are matched against your whole library: "Already have", "Better than yours"
  (with the reason, such as FLAC instead of your AAC) and "Missing, in Spotify lists" (title, artist and length must agree, and only
  the best copy in each chat is listed). Add copies a post into the main chat without a
  "forwarded from" line (a chat that blocks copying is downloaded and sent again by your account), with a speed slider and
  pause and resume; a song leaves the lists only once Telegram accepted its copy. The main chat is found by itself when you index it.
- Songs added to the library without a copy in Telegram are greyed "Bot can't reach". Select them from the library's Filter sheet (all, or only
  those whose song is already in your chat) to delete the duplicates, or copy them to the chat so the bots can read them; a copy that
  arrives takes over the library entry.
- Find and delete duplicate songs (Telegram is asked about every post, so a delete counts only when the post is really gone). Ticked
  by themselves are repeats in the same topic and surely worse copies; a smaller lossless file, a remastered or clean version and a copy
  with another length are yours to decide. One copy always stays.
- The recover page deletes an original only after Telegram confirmed its copy.
- Move songs between topics, upload songs from your phone or desktop. Android reads a chosen music folder where it is, with no
  copy, asks before it takes subfolders, and skips songs under 2 MB.
- Reading speed can adjust itself to what Telegram allows (Settings > Library > Speeds). Only a scan you start uses the
  "messages per request" setting; automatic reads stay at 100 ids and at most 50 requests a minute.
- Search by title, artist, album, genre, year and language (try `japanese`, `hindi`, `lang:tamil`, or `title by artist`). Albums with the same name
  are split by artist.

**Playing**
- Android uses Media3; desktop bundles libvlc, so nothing else needs installing.
- Android: **Bit-perfect** playback (off by default) for direct USB Audio Class 1.0 and 2.0 class-compliant DACs (exclusive isochronous streaming, UAC hardware volume control, exact integer/float repacking, 25 s pause timeout release, composite HID safety, and verified transport reporting; DACs that take 16 or 24 bit at most work too). A DAC that is plugged in is asked for once. Earbuds or headphones connected after the DAC take the music until they are gone, the DAC is plugged in again or Bit-perfect is switched on. The normal path uses Media3 with pure zero-touch pass-through when Bit-perfect is off.
- Queue editing, play next, shuffle and repeat, sleep timer, speed, a 10-band equalizer with presets, loudness
  levelling, and restore of your last session.
- Synced lyrics (tap a line to jump) and plain lyrics that scroll along; format badge and an output-info dialog.
- When a list ends (repeat off) a related song from your whole library is picked the way mixes are and plays on (Settings > Mixes >
  Keep playing when a list ends). A song joins Recently played after 30 seconds. **Private session** (profile menu on the desktop) records
  nothing for as long as it is on.
- Lyrics are large and bold, sung lines dim, and a Sync pill returns to the current line after you scroll away.
- Rewind restarts the song; a second press within 2.5 seconds goes to the song before it, and on the first song it only restarts.
- Lyrics can be switched per song (translate button in the lyrics view: original, romanized, translation, other records) and the
  choice follows you to your other devices.
- Artists in a credit like "A, B & C" are separate: each is its own tap target in the player, the song menu has "Go to" for
  every artist and the album, and an artist page lists every song they are credited on.
- Android: media notification, **standby mode** (a dark clock screen with the song, a grayscale cover tile and a Lyrics
  style that scrolls several lines with faded edges; a button turns it to landscape, either side picked by the motion sensor, only while standby is open), volume follows the system volume.
- Desktop: a top bar with back, home, search and the profile menu; now playing view, full-screen player, always-on-top mini player, tray menu, media keys, keyboard shortcuts. With the optional mpv engine: one **Bit-perfect** switch (exclusive WASAPI output, equalizer and effects bypassed), equalizer, bass, crossfeed and stereo width.
- One cache limit is shared by the main bot, your own account and each extra bot; the app trims them a minute after start and every 10 minutes, and Clear cache clears all of it (pinned songs stay). Settings > Storage and backup shows what is on the device (songs, covers, lyrics, song info, Telegram data, other) measured on disk, counts down while it clears, and can shrink covers to 600 px.
- Folders you sync (Settings > Upload) are checked every 2 minutes while the app runs; new songs go to the main chat once they finish copying.
- Light on battery: measuring how songs sound runs only while charging, the visualizer reads the audio only while it is on screen, and nearby devices are looked for less often after the first minute.

**Info, covers and mixes**
- Song info from MusicBrainz, Deezer, iTunes and TheAudioDB, plus tags and covers read from the audio files. Covers are saved at display size (at most 1000 px) and shared by the songs of one album, and cover files no song uses are removed.
- Lyrics from LRCLIB, NetEase, Kugou, QQ Music and lyrics.ovh.
- Daily mixes, genre and weather mixes, listening statistics, ListenBrainz.
- Import Spotify playlists (public link, or log in with your own Spotify client id; a logged-in account lists its own
  playlists for one-tap import or refresh). Songs you do not have are listed greyed
  out in the playlist's order, tap one to open it in Spotify, and a menu copies its real page on Spotify, Apple Music, Deezer,
  Tidal, Amazon Music and YouTube Music.
- Songs on a hits compilation ("Bravo Hits", "Top 100") do not get that album's generic cover; Settings > Library > Fix compilation
  covers resets songs that already have one.
- Covers, song info and uploads show their progress in the Tasks page and in a notification.

**Several devices**
- Local sync between your own devices on the same network: encrypted, paired with a code, merges your library both ways:
  favorites, plays, playlists (with their Spotify link and waiting list), edited tags, song info, lyrics, covers and deleted
  songs. What you made by hand wins over what was found automatically; a deletion removes the song on the other device too
  (a sync that would remove more than 2% of the library removes nothing and says so in the log). Listening time adds up over all devices.
- **App versions:** with **Block sync when versions differ** (Settings > Devices > Connection, on by default) a device neither takes
  data from nor gives data to a device with another app version, also one too old to say its version; playing and controls go on. **Force sync**
  allows it for one device until the two disconnect. Two devices that both block must both be forced.
- Linked devices show what the other plays and where the sound goes (headphones, speakers), can play, pause, skip and seek it.
  **Play on** sends the song and queue to the other device while this one keeps playing, **Move to** hands playback over, and a
  device you disconnect by hand stays disconnected until you connect it again. The PC reads its real Windows output.
- **Network play**: let other people on your Wi-Fi join a listening session with a PIN or link.

**Backup**
- Save everything to a zip (optionally password protected) and restore it on a new device; listening time and equalizer presets are
  in it. A restore goes on when the window is closed. Favorites, plays, playlists
  and edited tags also go to your Telegram Saved Messages.

**Look and feel**
- Material or glass looks, many backgrounds (cover, gradient, wallpaper, animated), accent colour from the cover,
  Material 3 Expressive or Tabler icons.
- Pages slide and fade, the full player is a sheet that follows your finger and Android's back swipe, lists glide, and
  covers and titles fade between songs. **Settings > Style > Reduce motion** turns all of it off, and **Home animation** turns off only the tiles and cards flying in when Home opens.

## Get the apps

Download from the **Releases** page: stable releases (`v1.3.0`) and numbered pre-releases (`v1.3.1-beta.1`). Each has:

| File | What it is |
|---|---|
| `PaperPlay-<version>-android-release.apk` | Android app, signed with the key stored in the repository secrets |
| `PaperPlay-<version>-windows-x64.zip` and `-setup.exe` | Windows app: zip and setup exe |
| `PaperPlay-<version>-linux-x64.tar.gz` | Linux app |
| `SHA256SUMS.txt` | checksums of the files above (the in-app updater checks them) |

Every push to `main` or `beta` is also built by GitHub Actions; the debug Android APK is only a workflow artifact
(`PaperPlay-Android-debug`).

The Windows build includes TDLib (the Telegram library) from the DLLs in `desktopApp/third_party/tdlib`; the Linux build
does not include TDLib yet and cannot reach Telegram.

The workflow is `.github/workflows/build.yml`. The tests are switched off in it because one test in `:core:jvmTest` hangs
on the runner.

## Build it yourself

You need JDK 17. Gradle itself runs on a JDK 25 daemon (`gradle/gradle-daemon-jvm.properties`) that it downloads the
first time, so the first build needs internet access. Run from the repository root:

```powershell
.\gradlew.bat :androidApp:assembleDebug          # Android debug APK
.\gradlew.bat :desktopApp:run                    # run the desktop app
.\gradlew.bat :core:jvmTest :player:jvmTest      # tests
```

The sound libraries (`libengine.so`, `libusb_audio.so`, `libusb-1.0.so`) are not in Git. Build them from `engine/` with `cargo ndk` (and
libusb with the NDK) before an Android build; the build stops when one is older than its source, and only warns when one is missing (the
equalizer or the DAC then does not work). `scripts/build_libusb.sh` does not run on Windows. CI builds all three.

The debug APK ends up in `androidApp\build\outputs\apk\debug\`. To install it on a phone: `adb devices`, then
`adb -s <serial> install -r <apk>`.

### Desktop installer and release builds

`scripts\release.ps1` builds the signed Android release, installs it when exactly one `adb` device is connected, builds the
desktop app, runs Inno Setup (`desktopApp\installer\PaperPlay.iss`) and copies `PaperPlay-<version>-setup.exe` and
`PaperPlay-<version>.apk` to Downloads. Use `-SkipAndroid` or `-SkipDesktop` to do half.

```powershell
powershell -File scripts\release.ps1
```

The version of a local build is in `version.properties`, or given with `-PappVersion=1.2.3`; `versionCode` is the commit count. Builds for
devices that sync with each other need the same version. Packaging needs a full JDK with `jpackage`.

### Desktop playback runtime (libvlc)

The bundled libvlc files are large, so they are not in Git. For a local desktop build, copy them once from a VLC 3.x
install:

```powershell
$vlc = "C:\Program Files\VideoLAN\VLC"
$dst = "desktopApp\src\jvmMain\resources\vlc\windows"
Copy-Item "$vlc\libvlc.dll", "$vlc\libvlccore.dll" "$dst\" -Force
foreach ($dir in "audio_output","audio_filter","audio_mixer","packetizer","stream_filter","stream_extractor","misc","keystore") {
    New-Item -ItemType Directory -Force "$dst\plugins\$dir" | Out-Null
    Get-ChildItem "$vlc\plugins\$dir" -Filter "*.dll" | ForEach-Object { Copy-Item $_.FullName "$dst\plugins\$dir\" -Force }
}
```

The curated `codec`, `demux` and `access` plugin subsets are copied by name (see the git history). If VLC is installed
but the bundled folder is missing, the app falls back to the system install. CI downloads VLC itself.

## Releases and versions

Every push to `beta` publishes a numbered pre-release (for example `v1.3.0-beta.7`), and a push to `main` publishes the
stable release (`v1.3.0`). The version and the change log come from the commit subjects, so nothing is bumped by hand:

| Subject starts with | Effect |
|---|---|
| `feat:` | minor bump, listed under **Added** |
| `fix:` or `perf:` | patch bump, listed under **Fixed** or **Improved** |
| `feat!:`, or `BREAKING CHANGE` in the message | major bump, listed under **Breaking changes** |
| `docs:` | listed under **Documentation**, does not release on its own |
| `chore:` `ci:` `build:` `style:` `test:` `refactor:` | hidden, never release on their own |
| no prefix | patch bump, listed under **Other changes** |

Android and desktop share one version, and the Android `versionCode` is the commit count. Every release has its notes on
the GitHub Releases page, and each stable release is also added to [`CHANGELOG.md`](CHANGELOG.md). The logic lives in
`scripts/release/release.py` (run `python3 scripts/release/test_release.py` for its tests).

Beta numbers follow the last stable release that `beta` contains. After `beta` is merged into `main` (which publishes the
stable release), merge `main` back into `beta`; until then the next beta would count again from the old version and sort
below the stable one, so the updater would not offer it.

## Updating

**Settings > About** checks GitHub Releases for a newer version (every time the app starts, or
when you tap *Check for updates*), shows what changed, and offers a dialog at launch when a newer version exists.

- **Channels:** stable by default; set *Update channel* to *Beta pre-releases* to also get pre-releases.
- **Safety:** files are only downloaded from this project's releases, checked against the release's `SHA256SUMS.txt`, and
  never installed until you tap Install. Android also refuses an update signed with a different key.
- **Android:** *Installation method* is *System installer* (Android asks you to confirm) or *Shizuku* (no confirmation
  screen; Shizuku must be running and must allow PaperPlay, and the row shows its state with a button to fix it). If
  PaperPlay is not yet allowed to install apps, it opens that settings screen and carries on when you allow it.
- **Windows:** the downloaded setup is started. **Linux:** the release page opens.

## Logging out

**Settings > Telegram** has four separate actions: *Log out* (your account if logged in, then the main bot), *Log out the
bot only*, *Log out the account only*, and *Log out and delete all data*, which removes the library, settings, covers,
cached songs, extra bots and every login from the device and starts over from the tour. Your Telegram chats are never
touched. Save a backup first (Settings > Storage and backup) if you may want the library back.

## Configuration

You provide your own Telegram API id, API hash and bot token in the first-run setup or Settings. They are stored on the
device only: in the Android Keystore on Android, and with Windows DPAPI on Windows (plain files on Linux and macOS).

## Project layout

| Module | Contents |
|---|---|
| `core` | database, Telegram client, library logic, sync, backup |
| `player` | playback engines and the queue |
| `ui` | the shared Compose screens, theme and motion (`ui/.../theme/Motion.kt`) |
| `androidApp` | the Android app |
| `desktopApp` | the desktop app (Windows and Linux) |

The shared UI has `@Preview` functions for phone and desktop sizes (`ui/.../PreviewSupport.kt` has the fake data). Screens
that need the database or Telegram have no preview yet.

## Known limitations

- **Deleting old posts:** Telegram lets a bot delete only posts younger than 48 hours (its own, or ones you sent). Older duplicates are deleted by your own account: log it in under Settings > Library. The Duplicates page says how many of the ticked posts need it.
- **Other bots' posts:** a bot cannot see songs another bot posted as itself in a group (channels are fine). Repair can read
  with every extra bot and with your own account to find them. With **Remain anonymous** on for each bot, new uploads are
  seen by every bot.
- **Language search:** a song's language comes from the writing of its title, artist and album and from a topic named for a
  language. A song in Latin letters outside such a topic cannot be told.
- **Windows:** the installer needs Windows 10 or newer, 64-bit. Windows 11 on ARM may run it through emulation (not tried); there
  is no native ARM build. It is not signed, so SmartScreen may warn.
- **USB DACs:** direct playback was never reached on DACs that stop at 24 bit until the fix of 25 November 2026, and has not been
  heard since. Song endings are cut when the next song has another sample rate or depth.
- **Pairing:** a device on your network that poses as your other one could find out the code while you pair. Pair on a network you trust.
- **App versions:** the block stops what reaches a device that has it. Two older apps still sync with each other, and a change an
  older app makes in Telegram itself is not stopped.
- **Not tried on two devices yet:** the shared player (what the other plays, source switch, output names), the sync of song data,
  listening time, covers, Spotify links and deleted songs, the version block, and the PC right-click menu.
- **Not tried on a device yet:** direct playback on a DAC, the Android database mode that lets screens read while something is written, the
  chat index after the last changes (resume after a restart, background indexing), the greyed-out Spotify songs, the language
  search, the self-adjusting reading speed, folder upload from an SD card, system volume on Windows, the tray, borderless full screen, the themed Spotify login page,
  the device popups, reading with extra bots, the new animations, installing an update through Shizuku, and Log out and
  delete all data.
- **Memory:** the desktop app uses about 300 to 400 MB at rest, mostly the graphics driver and class data. Each extra bot is
  its own Telegram session started at launch.
- **Not built yet:** backup to a Telegram topic, a live equalizer level meter, macOS packaging.
- **Written but not wired up:** `sync/WorkOwner.kt` (splitting background work between linked devices) and
  `recs/Suggestions.kt` (a "neighbours" view).
- **Secrets on Linux and macOS** are stored as plain files in the app's data folder.
