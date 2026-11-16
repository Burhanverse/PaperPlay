# PaperPlay Compose: what's done and what's left

Updated 14 Nov 2026 (section 6 is the 13 Nov code audit; the 14 Nov updates at the end cover the motion work, CI, releases, the updater and the About page). Everything compiles and CI builds it; most of it has not been tried on a device, and `[x]` in section 1 was not individually re-checked)

**Marks:** `[x]` done and written, `[~]` half done, `[ ]` not done.
"Compiles, not tried" means the Android release and desktop release built on 12 Nov (night) but the feature has not been tried on a device.

> Heads up: "done" means it was written and (unless noted) compiles. The phone release build with the
> speed fixes feels much better, but most features below have not been checked one by one on the phone.


---

## 1. What works now

### App and look
- [x] Home, Search, Library, Profile, Settings, mini player, full player, queue
- [x] Desktop layout (sidebar, now playing panel, player bar)
- [x] Glass and Material styles, themes, accent from the cover colour
- [x] Material 3 Expressive and Tabler icon options
- [x] Tabler icons work: `AppIcon` strips the `Rounded.` prefix before the lookup (phone too)
- [x] Unified onboarding, Telegram bot login and backup restore wizard, with Material 3 Expressive cards
- [x] Onboarding "About you" step: name and photo (`OnboardingSteps.kt`, `ProfilePage`)
- [x] Nunito variable typography, text contrast and Material 3 Expressive shapes (12dp to 36dp) on all screens
- [x] Scan chip, sticky play/shuffle, sort sheet, swipe up for queue, seek preview, recent searches
- [x] Style, Appearance, Navigation bar and Glass pages: glass on cards and top bars (blur and tint);
      desktop glass is blur, tint and gradient only
- [x] Full Glass controls (blur, refraction, saturation, tint, depth, colour fringing, edge, shadow, inner glow,
      reset), `GlassStyle` in `UiPrefs.kt` (compiles, not tried)
- [x] Settings: Repair library moved into Library, Style and Appearance merged, categories ordered by use (compiles, not tried)
- [x] Backgrounds: Plain, Cover, Blurred cover, Gradient, Wallpaper, Animated (stars, snow, aurora, bubbles,
      waves) with colour controls, in Settings > Appearance > Background (compiles, not tried)
- [x] Cover gradient on by default (off only when the pref is `false`)
- [x] Status toast hides after 20 seconds; swipe threshold lowered
- [x] Dark Windows title bar that follows the app colours (needs a PC rebuild to see)
- [x] Windows icon with all sizes (needs a PC rebuild and an icon cache refresh)
- [x] Backup carries the `bg_*` and `standby_*` prefs as layout (`bg_image` stays per device); needs the new test run

### Playback
- [x] Android (Media3) and desktop (bundled libvlc, no VLC install needed)
- [x] Shuffle, repeat, saved volume, play next, sleep timer, 10 band equalizer with response graph (compiles, not tried)
- [x] Media notification, keep-alive service with scan progress
- [x] Lyrics with synced lines, tap a line to jump there
- [x] Playback speed 0.5x to 2x from the full player (compiles, not tried)
- [x] Edit the queue: row menu with move, play next, remove; no drag yet (compiles, not tried)
- [x] Restore last session (paused; setting in Listening) (compiles, not tried)
- [x] Standby mode, Android only: black AMOLED screen from the full player, clock, song, lyrics, weather and
      battery widgets, Bars/Wave/Rings/Orb animation, customise sheet (compiles, not tried)
- [ ] Standby: auto-start while charging; animation does not follow song position

### Desktop
- [x] Spotify-style player: now playing view, full-screen synced lyrics, mini always-on-top window,
      device picker (this PC only), keyboard shortcuts overlay (compiles, not tried)
- [x] Sidebar: corner radius slider (`nav_radius`) and width (`sidebar_width`) are in `UiPrefs.kt` and Settings > Navigation bar (found in the 13 Nov audit; glass option and selection style not re-checked) (compiles, not tried)
- [x] Sidebar Tools group (Sort General, Duplicates, Song info left): Tools is a row with a toolbox icon and a fold arrow, the open tool is highlighted, and a divider sets Settings apart below (16 Nov, compiles not checked, not tried)
- [x] Mute button restores the previous volume (`DesktopShell.kt` remembers `lastVolume`; found in the 13 Nov audit)
- [x] Settings back arrow goes back one page, leaves Settings only from a top-level page
- [x] Media keys (`WindowsMedia.kt`)
- [x] Tray menu has a Mini player item (`TrayMenu.kt`); the bar lyrics button no longer opens the old dialog (see the 13 Nov update below)
- [ ] Queue drag-reorder (rows only have the move / play next / remove menu; no drag code)

### Telegram and library
- [x] Bot login, credentials, channels, main chat, TDLib on Android and desktop
- [x] Scan, full rescan, forum topics, backup restore
- [x] Own account: phone, code, password, QR login
- [x] Chats and channels page: index a chat, add songs the bot can't see (they play through the account);
      reachable from Channels, Profile and Settings; More toggle, select all / add all under the chips
- [x] Index refresh and Spotify refresh in the background
- [x] Duplicates: find them, tick extras, keep best quality, delete through the bot, Bot API or own account
- [x] Sort General: topic suggestions and moving songs between topics
- [x] Uploads hub: queue, retry, rate limit, share to PaperPlay on Android
- [x] Extra bots: add and remove tokens, bot pool shares uploads, rate limits per bot
      (compiles, not tried; bots from a restored backup are not started)
- [x] Save backup in the Flutter format with an optional password (Settings > Storage and backup),
      restore from file, round trip test passes
- [x] Cloud save in Saved Messages also holds favorites, plays, skips, pins, edited tags and playlists;
      restored once after login, saved on logout
- [x] Plus button on a not-added track copies the indexed match to the main chat (when auto copy is off)
- [x] Offline cache: eviction, pinning and the Cache manager page (Settings > Storage)
- [x] Quality badges: format badge plus Hi-Res badge on song rows
- [x] Delete from Telegram from the song menu (compiles, not tried)
- [x] Export list screen: Settings > Library > Saved export lists (compiles, not tried)
- [x] Library status tiles at the top of Settings > Library (compiles, not tried)
- [x] Topic picker (already in `SortPage`)
- [x] Songs missing: cause found, a bot cannot see posts another bot made as itself (anonymous admin or
      forwarding them again fixes new and old posts)
- [x] Rescans never remove songs by themselves: songs whose post looks gone are kept and listed in
      Repair library with a Remove button
- [x] Uploader saving right after an extra bot sends it: not needed for now (same Telegram limit as above)

### Playlists and Spotify
- [x] Import a public Spotify playlist link, match review
- [x] Spotify login with my own client id (PKCE), reads whole playlists
      - redirect to register: `paperplay://spotify` (phone), `http://127.0.0.1:8888/callback` (desktop)
- [x] Spotify added dates kept; playlist "Date added" sort uses them; manual refresh uses the Spotify login
- [x] Playlist page: sort menu, refresh from link, rename, delete
- [x] "N not in your library yet" sheet with index matches and Add buttons
- [x] Library Playlists tab (Favorites, Recently played, New playlist, Import)
- [x] Song menu: Open in Telegram, Song info, Find metadata, Edit tags, Change cover
- [x] Playlist add-songs picker (`AddSongsDialog.kt`, compiles, not tried)

### Metadata, covers, lyrics
- [x] Metadata lookups: MusicBrainz, Deezer, iTunes, TheAudioDB, all at once
- [x] Find metadata picker, edit tags, change cover (file, link or online)
- [x] Song info left page (what's missing, look up again, copy list)
- [x] Read tags and covers from inside the audio files (ID3, FLAC, Ogg, MP4)
- [x] Lyrics from LRCLIB, NetEase, Kugou, QQ Music, lyrics.ovh, run over the whole library, misses skipped for 14 days
- [x] Cover colours, daily mixes, genre tiles, weather mixes
- [x] Mood scan starts after the first catch-up and metadata run (untested on a device)
- [x] Mixes: Home opens a mix with remove and undo
- [x] "Doesn't fit" votes feed the daily mixes (rebuilt as the scan advances); no vote screens, no Mix settings page
- [x] Listening stats, ListenBrainz, mood code in core

### Speed and battery (12 Nov, big win on the phone)
- [x] Library reloads batched to about one every 2 seconds, one shared song list
- [x] Fewer background workers (metadata 2, covers 1, lyrics 2), batched writes
- [x] Full rescan of every chat resumes instead of restarting, runs in the foreground or while charging
- [x] Keep-running service off by default and stops when nothing is running
- [x] Position updates no longer redraw the whole screen, scan progress split out
- [x] Chats and channels page uses lazy lists, covers use a real cache and smaller thumbnails, theme and shapes remembered
- [x] Songs start faster: lookup runs next to queue setup, next and previous prefetched, stream reads use the known downloaded size
- [x] Smaller release APK: 35 MB down to 15.7 MB
- [x] Detail screen no longer redraws on every library change, seek bar reads progress at draw time,
      stable queue keys, handwritten baseline profile
- [x] Seek bar wave stops drawing when the Android app is in the background (needs a phone check)
- [x] Home mixes cached so Home opens faster

### Song list, rows, search and playlist toolbar redesign (12 Nov, edited, compiles not tried)
- [x] Slim song rows (one format chip plus status icon, size only when wide, `maxLines = 1`), slim search field with clear (`PillField`), grouped search results (Songs, Albums, Artists headers), playlist top bar overflow menu: all found in the code on 13 Nov (compiles, not tried). The flat opaque sticky Play/Shuffle/Sort bar was not re-checked

### Profile and navigation (12 Nov, compiles; debug build not yet on the phone)
- [x] Profile sections collapse with a chevron and always start collapsed (state not saved)
- [x] Profile rows fixed: Duplicates goes to the right page, new rows for Upload songs, Sort General, Connection and Devices
- [x] Back from a page opened from Profile returns to Profile, not the Settings list
- [x] Phone and desktop Back return to the previous page or tab through a history stack (needs a device check)
- [x] Nav bar polish: outlined Search icon, 24dp icons, shadow and outline, 16dp side margin (compiles, not tried)
- [x] Permission checker: Settings > Devices > Permissions lists notifications, Bluetooth (permission and on/off), battery and Wi-Fi with Allow / Open settings buttons, refreshed every second (`PermissionsPage.kt`, `Permissions.kt`, hooks in `MainActivity.kt`; compiles, not tried)
- [x] Extra bottom scroll room on phone pages so nothing sits behind the nav bar (compiles, not tried)
- [x] Previews for equalizer, ListenBrainz, account, song info rows, stats and log

### Other
- [x] Task centre page (running tasks, history, clear)
- [x] Task centre: stop buttons for scan, index, sync and upload; history kept across restarts (untested on a device)
- [x] Statistics, play next, clear cache, log out, profile photo
- [x] Android debug and release signed with the odo key, blue icon, desktop packaging (libvlc, TDLib, java.sql)


---

## 2. Half done, finish these

### Build and device checks
- [~] **Small TDLib:** built (14.4 MB raw, about 7.8 MB in the APK, same exports as the current one).
      Swapped into `androidApp/src/main/jniLibs/arm64-v8a`, old one backed up in `D:\tdlib-build\backup`.
      Not built or tested. Desktop unchanged (no Windows build). Would give an APK of about 10.8 MB.
- [~] **Desktop opens slowly (over 2 minutes):** fixes compile (VLC cache saved on exit and made at build time,
      services built off the main thread with a splash, player starts on first play, `startup.log` with timings),
      not tested on the PC.
- [~] **PC app is still the old build:** new icon, dark title bar and one shuffle button only show after the
      PC image is built and installed.
- [~] **Baseline profile** is handwritten, not recorded on a phone.

### Features
- [~] **Uploads:** queue kept across restarts; desktop Choose folder and window drag and drop; Profile has an
      Upload songs row (edited, compiles, not tried). Android folder pick not done.
- [~] **Missing info page:** multi select, "Retry no match", Copy as CSV and Save to file (edited, compiles, not tried)
- [x] **Detail screens:** add-songs picker done; playlist reorder written, compiles, not tried (schema v15
      `playlist_songs.position`, migration ranks by `added_at`, up/down dialog in `PlaylistReorder.kt`;
      cloud save keeps order, old snapshots restore in list order; `LibraryMerge` has no order yet)
- [~] **Playlist page:** Pin for offline now pins every song (untested on a device); Favorites and Recently
      played open as fixed lists
- [~] **Pin playlists and songs for offline:** Pin button and song menu work (account-read songs skipped)
- [~] **Sort General:** asks Telegram if the bot is an admin that can post and delete (skips with a note if not),
      leaves the playing song in General; deletes still go only through the bot (edited, compiles, not tried)
- [x] **Spotify login:** the in-progress login (state, PKCE verifier, redirect) is saved so a return link after
      Android ended the app finishes it (edited, compiles, not tried); the Spotify page does not refresh by itself after a late finish
- [x] **Loudness normalising:** Listening setting. Android limiter (Android 9+), desktop VLC `normvol` (compiles, not tried)
- [~] **LAN sync:** core, Connection page and Network play are written (see Devices). Queue messages are sent (`sendQueue`) and received (`LanMessage` type `queue`), but `AppServices.bindLan` only acts on `control` (play, pause, next, previous) and sends now-playing, so queue and now-playing sharing between own devices is still not used

### Needs keys or has known gaps
- [~] **Metadata:** Last.fm, Discogs, Spotify, Genius not used (they need keys)
- [~] **Lyrics:** Genius and Musixmatch not used (they need keys)
- [~] **Covers:** online cover thumbnails in Change cover may not show
- [~] **Embedded tags:** very big Ogg covers get cut (ID3 genre numbers now become names)
- [~] **Duplicates:** Dart duplicates tests not ported


---

## 3. Not started

### Fix first
- [ ] Android crash after the merge (error text is in Settings > Telegram > Activity log). The 16:29 crash was the volume poller and is fixed. The older TDLib "Receive must not be called simultaneously" aborts most likely came from a restore starting a second receive loop while the old one still ran; that is fixed in `AppServices.shutdown` (13 Nov audit, R1, not tried on a device; other causes are not ruled out)
- [ ] Check everything on the phone: login, QR, indexing, copying, refresh, cloud restore, uploads,
      lyrics run, duplicates delete

### Playback
- [~] Audio output choice: Android system output switcher from the player (compiles, not tried); desktop follows the Windows default output
- [ ] Remote and group playback

### Library
- [ ] Auto filing (new songs go to the right topic by themselves); moves real Telegram posts
- [ ] Neighbours view in Discover (core `recs/Suggestions.kt` exists; no UI found)
- [~] Recover songs the bot cannot read (Settings > Library > Recover songs): account copy, or account download and main bot upload, then delete the old post. UI wired (`RecoverPage.kt`, Settings row). Not built or tried. Describing the account as optional in onboarding still to do
- [x] Channels and chats on one page, per channel Copy to main chat switch (default off; `ChannelsPage.kt` exists). Not built or tried
- [~] Music folders with optional sending to Telegram (desktop picker, Android typed path only, no watcher). Not built or tried
- [ ] Backup to a Telegram topic: song database, covers and lyrics only (no tokens or session), selectable
      period in Settings (daily, 3 times a week, weekly, manual), restore through the owner's account

### Devices
- [x] LAN sync core (edited, compiles, not tried, no UI): `core/.../sync/LanSync.kt` (API), `JavaLanSync.kt`, `LanCrypto.kt` (X25519, HKDF, AES-256-GCM frames),
      `AddressBook.kt`, `PairingGuard.kt`, `LanSnapshot.kt`, `Lan.sq`; off by default (`AppServices.enableLanSync`). Pairing code, password,
      same-bot-token mode, only-paired switch, lockout, discovery, network scan, saved addresses. Tests: `LanCryptoTest`, `LanSyncTest`, `PairingAndBookTest` (not run)
- [x] Connection page (edited, compiles, not tried): `ui/app/LanConnectionPage.kt` inside Settings > Connection (route `conn`), `CoreBackend.lanSync` and `setLanOn`, starts at launch when pref `lan_on` is 1, manifest INTERNET and ACCESS_WIFI_STATE, `lan_*` prefs in `devicePrefs`
- [x] Network play (edited, compiles, not tried): other people on the same network join a listening session by QR link or 6 digit PIN and songs play in sync. Core `core/.../sync/netplay/` (`NetplayModel`, `NetplayGuard` PIN and `GuestPolicy`, `NetplaySync` clock offset, drift, song match, `NetplayLink`) and `javaMain/.../netplay/JavaNetplay.kt` (own port 47873 and discovery 47874, same X25519 and AES-GCM transport, guests get play control and audio ranges only); player glue `app/PlaybackNetplayPlayer.kt`, `TrackResolver.setExternalSource`; UI `ui/app/NetplayPage.kt` (Settings > Devices, Profile > Devices, pill in `Shell.kt`). Tests: `NetplayCoreTest`, `NetplayLoopbackTest` (not run). Gap: no camera QR scan (paste the link) and no `paperplay://` manifest intent filter yet
- [x] Apply a sync merge to the database (snapshot build and apply written in `LanSnapshot.kt`, edited, compiles, not tried)
- [ ] Remote playback and PC offload
- [ ] Log out of all devices
- [ ] Session export and import

### Desktop
- [x] Tray icon, close to tray (`desktopApp/.../Main.kt`, not tried)
- [x] Keyboard shortcuts (`PlayerShortcuts.kt`, overlay in `Shell.kt`, not tried)
- [ ] Battery and notification permissions
- [~] macOS and Linux libvlc and packaging (Linux .deb target in `desktopApp/build.gradle.kts`; no macOS)
- [ ] Check release packaging on Windows, macOS and Linux

### Carried over from TODO.txt (merged 13 Nov, TODO.txt removed)
- [ ] Windows now playing overlay and lock screen controls (needs WinRT media session; `WindowsMedia.kt` has media keys only)
- [ ] Phone: Settings entry in the side menu (asked; the phone has no side menu, decide where it goes)
- [ ] Check the first-run tour on a fresh install: restore choice, Skip, bot connect failing, swipe past the locked bot page
- [~] Repair library: embedded tags are applied when a song's download finishes (`AppServices.kt`, `EmbeddedTagFiller`), but Repair does not walk files already cached. Still to do: run lyrics after lookups, retry bot-posted songs it cannot see
- [ ] Cover search for compilations (e.g. "00s Mixtape") still weak; Deezer and iTunes do not list them
- [ ] Activity log: keep lines across restarts, add warning level, log more sources (playback, sound, sync, covers) like the Flutter app

### Ideas I haven't built yet
- [ ] Album pages that fill themselves from all the sources
- [ ] Lyrics run and metadata run progress on the Home scan chip
- [ ] Pick lyrics source order in Settings
- [ ] Lyrics from the audio file itself (embedded lyrics tag)
- [ ] Per-song "wrong lyrics" button that tries the next source
- [ ] GitHub builds TDLib and the app downloads it, in-app update from Releases
      (skipped for now because the bot needs TDLib too)
- [ ] Parts in Rust (tags, cover resize, mix scoring, search), only if a profile shows they are slow


---

## 4. Tests

- [x] Kotlin tests: backup (writer round trip too), database, TDLib client, audio info, index, selection,
      auto sync, QR, user session, playback controller, streaming, scanner, covers
- [x] Kotlin tests that exist but were not listed here (25 files: 23 in `core`, 2 in `player`, none in `ui`, `androidApp` or `desktopApp`): LAN crypto, LAN sync, pairing and address book, library merge, netplay core and loopback, album split, genre, JVM player engine, stream HTTP server, TDLib JSON smoke. Tests do not run in CI at the moment (see 6e)
      auto sync, QR, user session, playback controller, streaming, scanner, covers
- [ ] Dart tests with no Kotlin version yet: album_art, cover, cover_colors, duplicates_view, equalizer,
      glass_style, home_mix, later_stamp, listen_recorder, listen_stats, listenbrainz, logic, loudness,
      main_chat, migration, missing_export, mood, neighbours, playlist_import, setup, spotify, sync, taste,
      td_compat, topic_sort, upload
- [ ] No tests written for most of what was added in the last two days


---

## 4b. Added 2026-11-12 late (edit-only, not built)

- [~] Onboarding: animated logo, auto-advance after the bot connects, optional Spotify page, permission shows allowed
- [~] Desktop: native Explorer-style dialogs for open, save and folder (compiles, not tried), frameless mini player, adaptive player bar, song menu, cover-gradient now playing
- [~] Cover colours and sharper covers
- [x] Settings: merged Appearance page, All parts incl. sidebar, bold transport icons
- [x] Android: one AppServices per process (`MainActivity.shared`; found in the 13 Nov audit). Notification icon not checked. A restore from Settings now shuts the services down properly first (R1, fixed, not tried on a device)
- [ ] Background fill of info and lyrics
- [~] Playlists: From Telegram, starred channels; duplicates covers
- [x] Installer asks to delete data on uninstall
- [ ] Measure play start time on the PC; find the Android crash; check PC vs phone song count

---

## 5. Order I'd do it in

1. Everything now compiles (Android release 10.9 MB and desktop installer built); install both and try the features marked "compiles, not tried"
2. Build and install the PC app, check the slow start with `startup.log`
3. Install the debug APK with the small TDLib; test bot login, scan and playback on the phone
4. Go through the device checks, then the Android crash
5. Check the half-done items on a device (uploads, reorder, sort, Spotify resume)
6. Auto filing and the Telegram backup topic
7. LAN hub, pairing and devices
8. Desktop tray and shortcuts, then macOS and Linux
9. Port the Dart tests as each thing lands

## Update 2026-11-13 (desktop player, player bar, glass)
- [x] Full player: Queue/Lyrics tabs, window full screen with exit icon (native title bar)
- [x] Now playing page: Queue/Lyrics switch; bar lyrics and three-dot buttons removed
- [x] Player bar settings, custom volume bar, Space focus fix, phone Devices button
- [x] Cover search dedupe and ranking; sort label width; pinned list bar colour
- [x] Desktop glass blur off by default (switch on the Glass page); covers mipmapped
- [ ] Try on screen: player bar settings, glass without blur, covers, volume bar, full screen
- [ ] Custom title bar (reverted: no drag, laggy)
- [x] Esc exits full screen (borderless full screen, compiles, not tried)
- [ ] Lag check; "Wrong remote file identifier" on the phone; prefs missing from backups

## Update 2026-11-13 (fixes round; desktop and Android release Kotlin compile, nothing tried on screen)
- [x] Tray: left click opens the app (press/release plus action listener, stronger raise); right click opens a themed menu window (`TrayMenu.kt`)
- [x] One copy only: a second launch shows the running window (`app.lock`, `app.port` in `%APPDATA%\PaperPlay`)
- [x] Full screen on Windows is borderless over the monitor (`BorderlessFullscreen.kt`); Esc leaves it; the player layout uses the width better
- [x] Panel toggle hides while the panel or full player is open and comes back when closed
- [x] Mini player: graphicsLayer clip for the corner artifacts, time readout fills the right side
- [x] "Song info left" icon is the checklist icon
- [x] File dialogs: Explorer-style open and save on Windows (WinForms through PowerShell), COM folder picker kept, zenity or kdialog on Linux, AWT fallback
- [x] Desktop app icon art enlarged (`icon.png`, `app.ico`; Windows may need an icon cache refresh)
- [x] Memory: JVM limits (`-Xmx448m`, SerialGC, metaspace 160m), one shared cover cache (32 MB), covers decoded at the size shown with shrunk copies in `~/.paperplay/thumbs`
- [x] Equalizer: 10 bands, 13 presets, draggable response curve that glides between presets; old 5 band saves are stretched to 10
- [x] Desktop audio: WASAPI (`--aout=mmdevice`), soxr resampler, EQ preamp against clipping, EQ re-applied for each track, normalise ceiling 1.0, 500 ms buffering. The cause of the "weird noise" is not confirmed
- [x] Output info dialog (opens from the format badge and the player three-dot menu) on both platforms: source, rate, depth, output, effects, bit-perfect or not
- [x] Android: Devices button in the player tools row (Network play, Connect devices, Audio output) and the device pill opens the system output switcher
- [x] Channels page: songs, topics, size, duration, lossless, offline, pinned, favourites and copied counts per channel
- [x] `scripts/release.ps1`: Android release build and install, then the desktop installer to Downloads (`-SkipAndroid`, `-SkipDesktop`)
- [ ] Try all of the above on screen and on the phone; check tray click, full screen click-to-minimise, the noise, file dialogs
- [ ] RAM: earlier build used about 450 MB (heap 40 MB, metaspace 67 MB, Intel D3D12 driver files about 175 MB, TDLib and libvlc about 100 MB). Not a Rust problem. Test `-Dskiko.renderApi=OPENGL` and `SOFTWARE`, lazy libvlc, fewer classes
- [ ] Not done: live level meter in the equalizer (curve only), channel last-scanned time, panel toggle also hidden in the now playing view, VLC output rate not shown on desktop

## Update 2026-11-13 (late: sync, bots, search, volume, icons; compiles, not tried on a device)
- [x] Search no longer stops after the first query (the result flow was collected, never cancelled: `collectLatest` in `SearchScreen`); search also matches the year; film songs match through the album tag
- [x] Plain lyrics (no timestamps, any language) drift down with the song; dragging takes over, they follow again after 6 s
- [x] Phone mini player: devices button opens the app's Devices dialog (This phone, live linked devices with controls, Audio output switcher, Network play, Connect devices)
- [x] Desktop player bar Devices popup is live (it was a static placeholder): own addresses, linked devices with now playing and controls, paired devices away with Connect (`DevicesPanel.kt`, `LocalLanSync` from the shell)
- [x] Connection page: one entry per device with all its addresses, each editable and deletable, plus add an address to a device; "This device" card with own addresses first; sync result says it goes both ways and counts both sides
- [x] A paired device that announces itself is linked at once (old or new address, tried first), and a seen address shows "Seen on this network" instead of "Trying now"
- [x] Profile photo and name changes show a saved message
- [x] Bots page: name, @username and picture for the main bot and every extra bot (read through each bot's own TDLib session)
- [x] Spotify client id is hidden with an eye button; the browser page after Spotify login is themed (light or dark)
- [x] PC player bar, now playing view and mini player use the phone's rewind, play and fast-forward glyphs; mini player has format badge (switch in Player bar settings), width that follows the song text, play/pause/cover/progress animations, Windows rounded corners (no transparent window)
- [x] System volume: the volume bar follows and sets the Android media volume and the Windows master volume (`SystemVolume` in the player module, Core Audio through JNA); app-only volume stays where there is no system volume. Windows part is compile-checked only
- [ ] Try on screen and phone: all of the above, especially system volume on Windows, tray click, full screen, themed login page, device popups
- [ ] Idea list: remote control and live device list already exist; per-device permissions, safety code on pairing, trusted networks, offline-proof pairing (PAKE), "Only paired devices may link" on by default
- [ ] RAM (measured on the new build, idle): desktop 319 MB working set, heap 46 MB, metaspace 64 MB, Intel D3D12 driver files about 175 MB, libvlc loaded at idle (create it on first play), 52 threads, CPU 1%; Android native heap 158 MB allocated (TDLib), Java heap 14 MB, private 64 MB. Next: lazy libvlc, `-Dskiko.renderApi=OPENGL`/`SOFTWARE` test, TDLib options (no message database, smaller caches)

## Update 2026-11-13 (last: reading with every bot; compiles, not tried)
- [x] Reading speed is the real number of requests a minute (one at a time when slow; lanes only above 30 a minute) so Full rescan and Repair stop hitting Telegram's rate limit; the slider is on the Repair page and above Full rescan (Library settings)
- [x] Repair can read each chat with every logged-in extra bot (switch "Also read with the extra bots", on by default). Each bot adds only songs the library lacks; songs only an extra bot can read are stored as `bot:<id>:<remote id>` and played, pinned and given covers through that bot
- [x] Repair can also read the main chat with your own account (switch "Also read with your account", off by default); songs no bot could read are added to play through the account (the Recover page's index and `addMissingFromMainChat`)
- [ ] Try on the real chat: Repair with the bots switch on; the log shows one line per bot. Check a song that only an extra bot can read plays, and that the song count rises towards the phone's 3,112
- [ ] Not done: start extra bots only when a scan or upload needs them and stop them after idle (they all run at launch now and cost memory); find which bot sent a song from the account's view; a device without a song's owner bot falls back to the main bot, which cannot read it

## Update 2026-11-13 (end of session: clean devices, memory leaks, docs)
- [x] Devices: no addresses in the player popups; phone sheet "Play on" (current output card with the song, other devices with controls, tiles for Connection, Network play and Output); PC keeps the minimal popup with Connection and Network play buttons that open those settings pages
- [x] Memory leaks (desktop, grow with songs played): `StreamHttpServer` kept every played song registered (now the last 8), `QueueItem` cached one stream source per item for the life of the queue (now asks the resolver each time). An idle app was flat over 8 minutes (private 678 MB, heap 24 to 38 MB, 68 threads)
- [x] Extra bots run with no message database (less memory per bot)
- [x] README updated: features, release script, known limitations
- [ ] Proposed, not built: Spotify style active device. Selecting another device sends it the queue, song and position and it starts there (the first pauses); this player mirrors the active device's song and progress with a "playing on <device>" icon and controls it; selecting this device pulls the queue back; a linked device that starts playing becomes the active one on the others automatically. Needs queue and position messages in `JavaLanSync` (`LanMessage` and `sendQueue` exist as stubs) and an `activeDevice` state in `CoreBackend`
- [ ] Next memory steps: start extra bots only when a scan or upload needs them and stop them when idle; create libvlc on the first play (restoring the last session creates the player at startup); test `-Dskiko.renderApi=OPENGL` and `SOFTWARE`; measure a long listening session
- [ ] Try everything on screen and phone before trusting it: the unchecked lines of the last four sections

## Update 2026-11-13 (after the phone test: crash, standby, menu, search, sort; compiles, not tried)
- [x] Crash fixed (last log, 16:29): the system volume poller in `CoreBackend` started before `playerStarted` existed and hit a null; a rotation recreates the backend, which exposed it. The poller now starts after the player setup. The older crashes in the log (11-12) are TDLib "Receive must not be called simultaneously from two different threads" aborts, not fixed
- [x] Turning the phone no longer recreates the activity (`configChanges` on `MainActivity`)
- [x] Standby: full screen (status and navigation bars hidden, swipe from the edge shows them, draws under the cutout); burn-in drift of up to 6% of the shorter side along a path that repeats only every few minutes, including the settings gear (setting "Shift the screen slightly" is on by default); portrait and landscape layouts exist
- [x] Song menus stay 95% opaque in Glass: menus are separate windows and cannot blur the page, so text behind showed through. Real blur inside menus is not possible without drawing the menu inside the page
- [x] Search: every query word must start a word of the title, artist, album, genre or year (no more "udi" matching "Studio"); combining marks of Indian scripts stay inside a word; results ranked title, artist, album, with an exact title first; the in-list search uses the same rule (`searchTokens` in `LibraryRepository`, `searchWords` and `searchScore` in `SongLogic`)
- [x] Sort order: song lists already saved one shared order; each playlist now also remembers its own "Sort by" (`playlist_sort:<id>`, `UiPrefs.getString` and `putString`)
- [ ] Try on the phone: standby in portrait and landscape (bars hidden, drift visible), rotate during standby and during playback, the song menu in Glass, search for "udi udi" and a Malayalam word
- [ ] Not done: sort order of the albums, artists and playlists tabs themselves (only song lists and playlist contents remember theirs); the TDLib thread abort in the older crash log; menus with real blur

## Update 2026-11-13 (player cleanup, cover search, seek touches; compiles not checked, not tried)
- [x] Seek bar: only touches on the line itself seek (tap, long press, drag); brushing past the bar or the times no longer resets or skips
- [x] Full player: star and three dot buttons have no background and the title is clipped before them; bottom row is Lyrics, Shuffle, Repeat, Queue and a Tune menu (Sleep timer, Speed, Standby, Devices)
- [x] Format badge reads "FLAC" or "FLAC Hi-Res" instead of "FLAC Lossless"
- [x] Change cover starts with title and artist like Find metadata (album text found nothing); cover search no longer asks Deezer and iTunes twice
- [ ] Try on the phone: tap near the progress bar and times, long titles beside the star, the Tune menu, Change cover search. Deezer returned empty results from the PC, so covers may come from iTunes only

---

## 6. Code audit, 13 Nov 2026

Method: file inventory of every module, then a search of the code for each "not done" and "half done" line, a
sample of the `[x]` lines, and a search for code that no line mentions. Nothing was run on a device. "In the
code" means the code exists, not that it works.

### 6a. Not in the code at all (every search came back empty)
- [ ] Auto filing (new songs go to the right topic by themselves). The filing code (`filing/TopicMover`, `TopicSort`, `Topics`) only runs from the Sort General page
- [ ] Backup to a Telegram topic with a schedule (no schedule or topic backup code; `BackupExport.kt` only writes the zip)
- [ ] Neighbours view in Discover, and any other use of `recs/Suggestions.kt` (nothing calls it)
- [ ] Remote playback and PC offload; Spotify style active device handoff (`sendQueue` and the `queue` message exist, nothing uses them)
- [ ] Log out of all devices; session export and import
- [ ] Queue drag-reorder
- [ ] Windows now playing overlay and lock screen controls (`WindowsMedia.kt` has media keys only)
- [ ] Standby auto-start while charging (the standby code reads the battery level only)
- [ ] Embedded lyrics tag; pick the lyrics source order; per-song "wrong lyrics" button
- [ ] Lyrics and metadata run progress on the Home scan chip (progress shows in Tasks)
- [ ] In-app update from GitHub Releases; the app downloading TDLib
- [ ] Android folder picker for music folders (no `OpenDocumentTree`) and any folder watcher
- [ ] Camera QR scan for Network play (the page says so) and the `paperplay://netplay` manifest intent filter (the manifest only has `paperplay://spotify`)
- [ ] macOS packaging (`VlcLoader.kt` knows macOS library paths; there is no macOS target)
- [ ] Activity log kept across restarts and a warning level
- [ ] Desktop battery and notification permissions

### 6b. Partly in the code
- [~] LAN queue and now-playing sharing: transport exists, `AppServices.bindLan` ignores `queue`
- [~] Repair library reading embedded tags: applied after a download, not over cached files
- [~] Secrets: Android Keystore and Windows DPAPI protect them; on Linux and macOS `DesktopSecretStore` writes plain files
- [~] Extra bots all start at launch (no lazy start); libvlc is created at startup
- [~] Sort order is remembered for song lists and each playlist only, not for the albums, artists and playlists tabs
- [~] Netplay: join by pasted link or PIN works in code; no camera, no intent filter
- [~] Dart tests ported: only some (sync merge, LAN, netplay, album split, genre); the list in section 4 is mostly still open

### 6c. In the code but missing from this list
- [x] Work splitting between linked devices (`sync/WorkOwner.kt`: which device does song info, covers, lyrics and mood scans for a song). **Written but nothing calls it**
- [x] Library merge rules for synced libraries (`sync/LibraryMerge.kt`: the newest change to a song wins, plays take the larger count, deleted playlists and removed playlist items are remembered; has tests)
- [x] Automatic refresh of imported Spotify playlists (`integrations/PlaylistAutoSync.kt`, wired in `AppServices`, has a test)
- [x] Albums that share a name split by artist (`library/AlbumSplit.kt`, used by `CoreBackend`, has a test)
- [x] Crash capture: an uncaught exception is written to `crash.txt` and shown in the Activity log on the next start (`MainActivity.kt`)
- [x] Android Keystore secret store (`core/.../AndroidSecretStore.kt`) and Windows DPAPI store (`DesktopSecretStore.kt`)
- [x] Desktop loopback stream server for libvlc with range support (`player/.../StreamHttpServer.kt`, has a test)
- [x] Home shelves (`recs/HomeShelves.kt`: rows of albums and artists, each cover used once on the page)
- [x] CI (14 Nov): the workflow builds the Android debug and release APKs (signed with the repository keystore secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`) and the Windows and Linux desktop apps on `main` and `beta`, 30 minute timeout per job. The tests and the GitHub Release job are switched off with `if: false`

### 6d. Dead or empty code (written, never used)
Removed on 13 Nov: `account/Stubs.kt` (`NoAccount`, `NoIndex`) and the empty `library/Placeholder.kt`.
- `desktopApp/.../AppTitleBar.kt`: the custom title bar that was reverted; nothing calls it
- `core/.../recs/Suggestions.kt`: written, never called
- `core/.../sync/WorkOwner.kt`: written, never called

### 6e. Build and CI to do
- [ ] Find the test that hangs `:core:jvmTest` on CI (one run ran 6 hours, run 31 ran 53 minutes), then put the tests back in the workflow
- [x] Windows CI job failed every time at the "Get libvlc" step: it now downloads with `curl --fail` from download.videolan.org and unzips with 7-Zip; the TDLib step no longer fails when the `deps` release is missing (14 Nov). The installers then built (`PaperPlay-1.2.0.exe` and `.msi`) but "Collect files" failed: `packageDistributionForCurrentOS` does not create the unpacked `main\app\PaperPlay` folder the zip step reads, only `createDistributable` does. The build step now runs both
- [x] Linux and Windows jobs are back on and green (14 Nov, run #46: Windows zip, MSI and setup exe, Linux archive); the release-publishing job is now on (see the automatic releases update below)
- [x] README now says Gradle runs on a JDK 25 daemon it downloads on first use. A machine that cannot reach the download (or the Google Maven repository for the Android plugin) cannot build
- [ ] The new tests have never been run: `backgroundAndStandbyFollowTheLayoutChoice` (backup prefs), `oldWrongTriesDoNotAddUpToALockout` and `aSuccessClearsTheOverallCount` (pairing)

### 6f. Bugs found in the audit
Fixed and pushed to `beta` (compiled by CI except where noted):
- [x] Keep-running setting was read once, so turning it off was undone by the next scan or upload
- [x] Pairing lockout counters never decayed or reset on success (15 old typos locked pairing for 5 minutes)
- [x] Shared audio import updated its list without an atomic update; crash handler was wrapped again on every activity
- [x] Manifest lacked `FOREGROUND_SERVICE` and `POST_NOTIFICATIONS` (confirm a notification shows on Android 13 and later)
- [x] Release-group cache in `MetadataEnricher` was an unlocked `HashMap` used by two workers
- [x] Backup: `bg_*` and `standby_*` prefs now follow the "apply layout" choice; `bg_image` stays per device
- [x] `StreamHttpServer.parseRange` threw on huge numbers (desktop source, not compiled by CI)
- [x] Desktop Spotify login could stall on an idle connection; reads now time out after 5 s (desktop source, not compiled by CI)
- [x] R1 Restore while connected: `AppServices.shutdown()` stops LAN sync (keeping the `lan_on` choice), netplay, playback, the main and extra bots and the account, waits for TDLib's receive loop (`TdRuntime.stop()`), then closes the database; `App.kt` calls it behind a spinner before the restore screen, and Android never reuses services that were shut down. Not tried on a device
- [x] R2 Upload retries now wait: a failed job carries a retry time that every worker honours, so Telegram's flood wait is respected (the Uploads page already shows "Retrying, attempt N")
- [x] R3 "Listened" time now adds up normal forward playback steps; a seek no longer counts as listening (`PlaybackController`)
- [x] R4 Deleting a song with no main chat set now fails with a clear message instead of removing it only locally (`DeleteQueue.deleteSong`)

Open:
- [ ] R5 Many empty `catch` blocks, for example in `JavaNetplay.kt` (the poll loop swallows every `tick` failure). They need a logger passed in (netplay has none, only a notice flow); not changed without one
- Not a bug (R6): the local stream server cutting a response short when a read comes back empty ends the connection, and the player sees the short body; nothing to fix

## Update 2026-11-14 (device toast, player sheet, motion; compiles in CI, not tried on a device)
- [x] Device toast: "Connecting to X" is shown once per device per run. `JavaLanSync.autoConnect` dials an offline paired device every 20 seconds and the old toast appeared each time (`Shell.kt`). "Connected to X" still shows on every real link
- [x] Full player is a slide-up sheet (`PlayerSheet.kt`): it follows your finger when you drag the cover down, settles open or closed with the fling speed, and shrinks with Android's back swipe (cancel puts it back). It moves with `graphicsLayer`, so it does not recompose per frame. The mini player fades as it rises; the queue and song views crossfade
- [x] Pages slide and fade (`PageTransitions.kt`): going deeper (detail page, Tasks, Settings page) slides in over the old page, going back reverses it, switching tabs fades through. On the phone page area, the desktop page card and the Settings pages
- [x] Song list and queue rows glide when they move (`animateItem`); a cover that loads late fades in over the mosaic; the nav pill uses the shared spring; the dead constant scale animation is gone
- [x] Desktop motion (14 Nov, compiles, not tried): the sidebar glides open and closed (`animatedWidth`, runs in the layout phase so `Shell` does not recompose), sidebar items and song rows ease their selected colour and lighten under the mouse pointer, the now playing panel expands and collapses from the right edge, the keyboard shortcuts overlay fades, the tray menu fades in and rises a few pixels. The page transitions, player sheet, list gliding and cover fade apply on the desktop too (they live in shared code)
- [x] Motion tokens (`theme/Motion.kt`) and a Settings > Style > Reduce motion switch (pref `reduce_motion`, per device, carried as layout in backups); everything above becomes instant with it on
- [ ] Try on the phone: drag the player down slowly, fling it, drag half way and release, back swipe and cancel, open it from the notification and the mini player quickly, rotate while it is open, switch the four tabs, open an album and go back, walk through Settings pages, scroll long lists with the glass nav on and off, reduce motion on
- [ ] Not done: a "moving" flag to drop blur while the sheet moves (add it only if the drag stutters on glass), `animateItem` on the Home rows, press-scale on song rows
- [ ] The signed debug APK from CI failed to install on the phone with INSTALL_FAILED_NO_MATCHING_ABIS although the phone reports `arm64-v8a` and the bundled `libtdjson.so` is a valid AArch64 library. Cause unknown; waiting for the list of `lib/` folders inside the APK (`unzip -l`)
- [ ] The Windows and Linux builds have no TDLib (the CI looks for a GitHub release tagged `deps` with `tdlib-windows.zip`, and there is none), so those apps build but cannot reach Telegram yet

## Update 2026-11-14 (smoother pass; compiles in CI, not tried on a device)
- [x] Windows CI job passes: the build step also runs `createDistributable`, because `packageDistributionForCurrentOS` makes the installers but not the unpacked app folder the zip step reads
- [x] Pressing the mini player and the nav bar items squeezes them slightly and springs back (`bouncy`, now off with Reduce motion)
- [x] The song title and artist fade into the next song's in the full player and the mini player; the big cover crossfades between songs inside one shadow
- [x] Album and artist grids and lists and the search results glide when they change (`motionItem` for grids as well as lists)
- [x] Each tab keeps its scroll position while you are on another page (`rememberSaveableStateHolder`, phone and desktop; detail, Settings and Tasks pages are not saved)
- [ ] Try on the phone: leave a long list half way, open an album and come back; skip songs and watch the title and cover; press the nav bar and mini player quickly

## Update 2026-11-14 (automatic releases; compiles in CI once pushed, never run on GitHub before)
- [x] Version and releases from the commit subjects (`scripts/release/release.py`, 17 tests): `feat:` minor, `fix:`/`perf:` patch, `!` major, no prefix patch; docs/chore/ci/build/style/test/refactor never release alone. Push to `beta` = numbered pre-release, push to `main` = stable. One shared version for Android and desktop, first release 1.3.0; Android `versionCode` is the commit count
- [x] Change log: grouped notes on every GitHub release (Added, Fixed, Improved, Documentation, Other changes) and a `CHANGELOG.md` section committed to `main` by each stable release
- [x] The version is passed to Gradle as `-PappVersion` / `-PappVersionCode`; local builds read `version.properties`. The app reads it for the Telegram client string (`PackageInfo.versionName` on Android, `paperplay.version` on desktop)
- [x] (done 14 Nov, see the next update) First run on GitHub: check the `version` job output, the pre-release and its files, that the APK installs over the current app, and that a `docs:` push creates no release. If `main` blocks pushes from the workflow, the CHANGELOG step needs a bypass for the Actions bot


## Update 2026-11-14 (in-app updates, About page; compiles in CI, not tried on a device)
- [x] First run on GitHub worked: run #55 published the pre-release `v1.3.0-beta.1` with grouped notes. A mention of "BREAKING CHANGE" in a commit message body no longer counts as a major bump (only a line starting with `BREAKING CHANGE:` does)
- [x] Settings > About and updates: version, check for updates, release notes, download with progress, SHA-256 check against `SHA256SUMS.txt`, install, skip this version; *Beta updates* (pre-releases) and *Check automatically* switches. The start-up check runs at most once a day and shows an "Update X available" snackbar that opens About
- [x] Android installs through the package installer. A missing "install unknown apps" permission shows a toast, opens that settings screen and the update continues once it is allowed; Settings > Permissions has an "Install updates" row
- [x] Optional Shizuku installer (shown when Shizuku is installed): asks Shizuku for permission, toasts "Start Shizuku first" or "Shizuku permission denied", offers the system installer instead; never switches on its own. Permissions has a Shizuku row while it is selected. The AIDL service is the likeliest thing to need a fix
- [x] Windows runs the downloaded setup; Linux opens the release page
- [x] Footer "Made by Max with heart and coffee" at the end of About and of the Settings list, both icons in the theme's accent colour
- [ ] Try on a device: two releases are needed (install the older one, publish a newer one), then check the check, download, permission prompt, system install, Shizuku install, the beta switch, and the footer colours

## Update 2026-11-14 (logout options, grouped Settings, About, new logo; compiles in CI once pushed, not tried on a device)
- [x] Log out is split into four named actions in Settings > Telegram: **Log out** (account if logged in, then the main bot), **bot only**, **account only**, and **Log out and delete all data** (logs the account out, shuts everything down, deletes the data folder and caches, and starts over from the tour). The old single "Log out" only forgot the main bot's API id, hash and token. Extra bots stay for the first three
- [ ] Try: delete all data returns to the tour with an empty library and no login on Android and desktop; check nothing is left under the app's data folder, and that a backup made first restores
- [x] Settings list is grouped under headings (Look and feel, Music, Telegram and devices, About) with About last; every category must name a group, so none can be dropped. The desktop sidebar has the same headings
- [x] About (renamed from "About and updates") has an Installation method choice (System installer or Shizuku) with Shizuku's state and its Allow / Open Shizuku button directly below, also when Shizuku is not installed (choosing it then says "Install Shizuku first")
- [x] Profile ends with an About card (icon, version, channel, "Update X available") that opens About, with the Made by line under it
- [x] The tour's logo is the launcher icon on every platform (first built as a redrawn badge, replaced the same day by the launcher's own plane image; see the last entry of this update). Its loop: the plane flies off, a music note pops up, the plane comes back and hits it, the note spins away in sparkles and the icon squashes and springs back. Reduce motion shows it still. The tour background also has slowly rising notes and dots
- [ ] Try on a device: the timing of the logo loop and the calmness of the background (constants at the top of `AnimatedLogo.kt` and in `GradientBackdrop`)
- [x] About page cleanup (14 Nov, compiles in CI, not tried): one header with the icon, version, update status and a single button that follows the state; Update channel and Installation method are drop-down rows (`DropdownRow` in `SettingsUi.kt`); Shizuku's state shows inside the Installation method row, with its Open Shizuku / Allow button; Check automatically stays a switch; the three links are tight rows. Choosing Shizuku when it is not installed says "Install Shizuku first" in that row
- [x] The in-app logo (tour first page, Profile About card, About header) now draws the launcher's own plane image (`ui/src/commonMain/composeResources/drawable/app_plane.png`, a copy of `ic_launcher_foreground.png`) on a flat blue square, the way the launcher shows it. The redrawn purple scalloped badge and hand-built plane are gone. The desktop window icon (`desktopApp/.../icon.png`) is a different, scalloped badge; the in-app logo follows the Android launcher

## Update 2026-11-14 (first stable release, beta numbering, state of the updater)
- [x] The first stable release `v1.3.0` was published when `beta` was merged into `main` (PR #4), with `CHANGELOG.md` and `version.properties` committed to `main` by the release job. Changelog entry, release assets and the pre-release flow all worked on GitHub; a `docs:` push did not create a release
- [x] Bug found and fixed: beta numbering counts from the last stable tag that `beta` contains. Until `main` was merged back into `beta`, the next beta was `v1.3.0-beta.6`, which sorts below the stable `1.3.0`, so the in-app updater never offered it. After the merge-back the plan gave `v1.3.1-beta.1`. Rule: after every merge of `beta` into `main`, merge `main` into `beta` (README, Releases and versions)
- [x] On the phone (14 Nov): About shows the version and the update check answers "You have the latest version"; Shizuku shows "Running and allowed" (a running Shizuku now counts as installed, because the package lookup was hidden from the app); the grouped Settings, Profile About card and the new logo were looked at
- [ ] Not seen yet: an actual update installed through Shizuku and through the system installer; the Install-unknown-apps prompt on a phone where it is off; the logo loop (plane flies off, note, hit) and the tour background in motion; Log out and delete all data; the three narrower logout options
- [ ] The `deps` release with `tdlib-windows.zip` still does not exist, so Windows and Linux builds cannot reach Telegram
- [ ] Open: `INSTALL_FAILED_NO_MATCHING_ABIS` when installing the signed debug APK from CI on the phone, and the memory-leak candidates from the 14 Nov investigation (nothing changed for them yet)

## Update 2026-11-14 (Android settings crash, themed logo, one Windows installer)
- [x] Settings crashed on the phone with `MissingResourceException: composeResources/.../app_plane.png`: the `ui` KMP Android library did not package Compose resources. Fixed with `androidResources.enable = true` in `ui/build.gradle.kts`; the rebuilt debug APK contains the PNG and installed (not opened since)
- [x] The in-app logo (tour, Profile About card, About header) uses the theme: `primary` badge, `onPrimary` plane, note, sparkles and trail. The plane PNG is tinted flat, so check its edges on the phone. The launcher and notification icons stay blue
- [x] Windows ended up with two installs: the script's Inno installer (per-user, `AppData\Local\Programs\PaperPlay`) and the GitHub jpackage setup exe or MSI (per-machine, Program Files, 141 MB against 82 MB because CI used the unminified `createDistributable`)
- [x] CI now runs `createReleaseDistributable` and builds the Inno installer from `PaperPlay.iss` (same `AppId` as the script), so the update page upgrades the same install; the MSI is no longer published. Not run yet: the first release from CI is the test (Inno Setup is installed with choco if the runner lacks it)
- [x] The Inno setup warns when an older install exists in Program Files; the 1.3.1 release on GitHub still has the old jpackage installer
- [x] The MSI copy in Program Files was uninstalled on the PC (exit 3010, restart or closing the running app finishes it)
- [ ] Open: the GitHub setup exe should be near 82 MB but the goal is under 80 MB. Biggest parts of the 162 MB image: `tdjson.dll` 28 MB, JVM runtime 49 MB, `skiko` 14 MB, `icudtl.dat` 10 MB. Try `Compression=lzma2/ultra64` first, then measure the CI build before cutting anything

## Update 2026-11-14 (CI fixes, TDLib in the Windows build)
- [x] CI ProGuard failed (`IncompleteClassHierarchyException`): Gradle runs on a downloaded JDK 25 (`gradle-daemon-jvm.properties`) that has no jmods. The workflow now sets `PAPERPLAY_JAVA_HOME` to the Temurin 17 and `desktopApp/build.gradle.kts` uses it as `javaHome`. Worked: `v1.4.0-beta.1` has a 65 MB setup exe, a 76 MB zip and no MSI
- [x] That beta had no `tdjson.dll` (the `deps` release was never made), so it could not reach Telegram. TDLib's four Windows DLLs now live in `desktopApp/third_party/tdlib` and the Gradle default reads them; the `deps` download step is gone from the workflow. The Windows installer should grow back toward 80 MB
- [ ] Linux still has no TDLib
- [ ] Not tried: installing the CI setup exe over the script-built install (same `AppId`, should upgrade in place); the update dialog on launch

## Update 2026-11-14 (evening: Android uploads, TDLib shapes, duplicates page). Key: [x] seen working on the phone, [~] built and installed but not tried, [ ] open
### Uploads (Android)
- [x] Cause of "InputFile is not specified": TDLib 1.8.60+ wants `inputMessageAudio.audio` as an `inputAudio` object (and `topic_id: messageTopicForum`); older TDLib wants the flat shape. `core/telegram/TdCompat.kt` picks the shape from `getOption version` (cached in `TdClient.newApi()`), as the Flutter app's `td_compat.dart` does. Uploads are accepted since (several bots posted). Same fix in `TopicMover` (reposts) and `UserSession` (backup document), both [~] not tried
- [x] Parallel uploading as in Flutter: 6 workers by default, up to 20, each worker rests on its own after a file (the `rate_uploads` gap, 6 a minute is about 10 s, varied 30%, 3x with "Upload more slowly", longer after every 20). The old global gate (one start every 8-20 s, 3 workers at most) was why it felt stuck. About 12 songs went up in 13 s. [~] The Settings slider now goes to 20 and the "Upload more slowly" text was corrected: not tried
- [x] Duration is read from the file header (`core/metadata/Duration.kt`: FLAC, MP3, M4A, Ogg Vorbis/Opus, WAV) and sent with the song, so Telegram shows the time. `DurationTest` has 3 passing tests; the log has `duration <name> = N s`
- [ ] A file TDLib has already uploaded from the same path is re-sent from its first upload with the first upload's attributes (no duration): 3 songs (Yot Club, Duvet, Alexander Stewart) still show `-:--`. Explanation from the log, not proven. Rename the file, or log in fresh (new TDLib database), to test
- [ ] Cover art is not sent as a thumbnail (Flutter sends `album_cover_thumbnail`); Telegram showed covers itself
- [ ] Bot rights are not checked before a bot is picked (Flutter's `canWriteWith`): a bot that is not an admin of the target chat would fail its files
- [x] Skip rule: songs under 2 MB are never uploaded (`MIN_UPLOAD_BYTES`, in `Uploader.enqueue` and the folder scan); the page says how many were left out. [~] The "Left out N under 2 MB" message has not been seen
- [x] Restart pause: a queue restored after a restart is paused by design, and adding songs did not lift it (the "it paused by itself" report). [~] Fixed so adding songs lifts a restart pause only (`pausedByRestart`); a Pause the user pressed stays. Not tried
- [~] Duplicate uploads: the app now remembers every file it sent by name and size (pref `uploaded_files`, the Flutter format) and skips a file that is already queued or sent; before, the same files were sent twice because the library only learns of a song after a scan. Turn off "Skip songs already in my library" to send a deleted song again. Not tried
- [~] "Internal Server Error during file upload" (Telegram side, seen on 3 songs) and "InputFile is not specified" now retry (up to 4 attempts) instead of failing at once; a missing file fails at once with a clear message. Not tried
- [x] Logging: `appLog` writes `PaperPlay <tag>: ...` lines (enqueue, upload start/done/failed, duration, every Telegram error with the sendMessage request, folder scan, copy failures) to Logcat. The phone's log buffer is 256 KiB; `adb logcat -G 8M` raises it until a reboot
### Android folders and files
- [x] Cause of the crash and the failures on the phone: the data partition was almost full (about 0.9 GB free), TDLib aborts the process when it cannot write `td.binlog`. Picking a folder copied every song into the cache. Free space is still the owner's to make
- [x] Folder upload reads songs where they are, as Flutter does (no copy): audio access is asked at the first "Choose folder", and a folder on the phone's own storage is scanned by path (`FolderTree.kt`). Seen working: songs under `/storage/emulated/0/Music` uploaded without copies
- [~] The same for an SD card (`/storage/<volume>/...`) and for songs chosen one by one (`pickedFile`): not tried. TDLib cannot read a content URI, so cloud, Downloads and other providers are still copied to the cache first; copying keeps 300 MB free and skips what would not fit, with "Could not copy N" in the page message
- [~] Subfolders are left out; the "Songs in subfolders" dialog lists each subfolder's song count and the first 8 file names, nothing ticked at first, "Skip subfolders" or "Add N songs": not seen yet. Dropping a folder on the desktop window still includes subfolders
- [~] Settings > Permissions has a "Music files" row (Allow / opens settings): not tried
- [~] Feedback after picking a folder ("Copying N songs for upload...", "Could not copy N"), the three buttons wrap (the Pause button was squashed into a column: seen fixed), the Upload-to dialog scrolls and no longer lists General twice: not tried
### Duplicates page
- [x] Floating "Delete N" button (not at the very bottom), extras ticked on load (repeats in the same topic and clearly worse copies; the best copy is never ticked), the "Keep best quality" button is gone, each copy says "Stays" or "Will be deleted". Seen on the phone
- [~] Each copy shows its topic (playlist) name from the Telegram topic table (`forum_topics`), format, size, date and file name; "Unnamed topic" appeared because the page used the owner-edited name list instead. Not tried after the fix
- [~] The page is a lazy list (it was one long column that built hundreds of rows with covers: slow and laggy) and tapping anywhere on a copy ticks it. "Tick topic repeats" keeps one copy of a song posted in several topics (a named topic before General) and ticks the others; it can take a song out of a topic playlist. Not tried
- [ ] Both copies of "The Night We Met" showed ticked once, against the rule that one copy always stays. No cause found
- [ ] Unclear whether the 172 deletes removed the posts: the page said 172 of 172 done, but Telegram's Music count stayed 3513 (before and after, also after reopening Telegram). Check by searching the channel for "Golden (Glowin" and "Airplanes". `deletePostsBest` returns success as soon as TDLib accepts the call and only re-checks when it fails
- [ ] The auto-ticked "clearly worse" copies include a smaller FLAC of a different release (Maroon 5 "Memories", different cover): the size-only rule (under two thirds of the best) may tick a different version
- [ ] The Chats and channels index only fills a separate catalog; songs reach the library with "Add all shown" / "Add N" (not a bug, but the progress text "Indexing ...: N songs" reads as if they were added)
### Player
- [~] The mini player can be swiped away while paused or stopped and comes back when playback starts (`Shell.kt`). Not tried. In several screenshots a faded mini player sits at the right edge while the page shows no mini player: possibly stuck partway. To check
### Windows build and updates
- [x] CI builds the release image and the same Inno installer as the script (per-user, one `AppId`), ProGuard reads the JDK 17 classes (`PAPERPLAY_JAVA_HOME`; Gradle runs on a JDK 25 without jmods), TDLib's four DLLs live in `desktopApp/third_party/tdlib`. `v1.4.0-beta.2`: setup exe 77.2 MB (under the 80 MB goal), zip 94.3 MB, no MSI; the zip has `tdjson.dll`; checksums match
- [~] `v1.4.0-beta.2` was installed silently over the script-built 1.3.0 (exit 0, now 1.4.0, `tdjson.dll` present). The PC app was not opened: Telegram connection, playback and the in-place upgrade are not verified. The old `C:\Program Files\PaperPlay` MSI install was removed, its folder may be left
- [~] Setup warns about an older Program Files install; the update dialog on every launch; themed in-app logo; the settings screens after the resources fix: none seen yet
- [ ] Linux has no TDLib; the 1.3.1 GitHub release still has the old installer
### Not committed
- [ ] All of the above since commit `ed49815` is uncommitted (uploads, TDLib shapes, duration, folders, permissions, duplicates page, mini player, logging, tests). Split into commits when the [~] items are confirmed
## Update 2026-11-14 (standby visualizer follows the audio)
- [x] Cause found: `StandbyVisual` was purely procedural (a tempo and phases from the song id, level from the volume), so it never reacted to the audio
- [~] Fix: `player/AudioTap.kt` (1024-point FFT, 32 log bands, auto gain) fed by `TapProcessor` in the ExoPlayer audio sink (`PlaybackCore` uses `TapRenderersFactory`); the visual reads it, 60 fps, fast attack and slow release smoothing, rings jump on bass hits. No audio shows a flat baseline (desktop has no tap yet). Compiled, installed, `AudioTapTest` passes
- [ ] Check on the phone: bars follow the music, silence settles, pause settles, no audio glitch from the processor, battery use in standby. The tap runs ~0.1 s ahead of the sound (sink buffer): add a delay if it feels early
- [ ] Standby ideas: auto-start while charging; spectrum for the desktop (vlcj audio callback); cover-art colour pulse; synced lyrics large mode; album art as the visual; a sleep timer countdown and alarm clock; next alarm; notification count; now playing from the lock screen; night mode that dims by time; tap-hold for brightness; double tap to play/pause; swipe left/right to skip; visual styles: mirrored bars, circular spectrum, particles; peak-hold caps on the bars
- [~] Seven new standby styles in `StandbyVisual.kt` and `StandbyVisualMore.kt`: Mirror, Peaks (falling caps), Circle (spokes), Particles (burst on bass), ASCII bars, Matrix (rain, speed follows the bands), ASCII pulse (text ring). Not compiled or tried; check text cost on the phone (monospace rows, ~16 text draws per frame)

## Update 2026-11-14 (night: start-up, toasts, Spotify lists, Home). Key as above: [x] seen working, [~] built but not tried, [ ] open
### Start-up and toasts
- [~] Daily mixes flickered or vanished at start: Home is replanned several times while the library loads, and a build that came back empty (a partly loaded library, saved mixes that could not be read yet) replaced the mixes on screen. `CoreBackend` now remembers the last mixes shown (`lastMixes`) and an empty result never replaces them. Not tried
- [~] Home shows skeleton shapes (`HomeSkeleton.kt`: six tiles, card rows, the mix row) while the library and the plan load, and a cover without art shows a pulsing shape instead of the coloured mosaic, only during start-up (`LocalCoversPending`, until the cover queue first empties or 10 s). An earlier version kept covers as skeletons while scrolling; fixed. Not tried
- [~] Covers blinked away and came back on scroll: the cover cache held 32 MB and Home decoded each 150 dp card at 768 px (about 2.4 MB), so about 13 fitted. Cards now decode at 512 px (new size step in `Cover.kt`), the cache is 64 MB, and `FileCoverLoader.preload` decodes the covers of the planned tiles and shelves ahead of drawing, so art should be ready when Home is drawn (it showed about 2 s late). Not built after this change (see Build)
- [~] Connection toasts ("Connecting to ...", "Connected to ...") wait until the app has been open 10 s (`waitForQuietStart` in `Shell.kt`); every snackbar is coloured with the page tint and swipes sideways away (`Toasts.kt`, `AppSnackbarHost`, also used by the playlist page). Not tried
- [ ] Start-up order: favorites first, then the rest, then the art about 2 s later. The skeletons and the preload should cover it; `HomeShelves.plan` and the 2 s re-plan delay were not changed
### Spotify lists and Home
- [~] Refreshing from Spotify shows a toast when it starts and the result when it ends (Spotify settings page and the playlist page's "Refresh from Spotify link")
- [~] Songs a Spotify playlist has but the library does not are listed greyed out after the songs under "Not in your library (N)", with their art (`GhostRow.kt`; the art comes once from Spotify's oEmbed page and is saved as `covers/spotify_<id>.jpg` by `MissingArt`); tapping one opens it in Spotify
- [~] The "N not in your library" sheet has a link button per song with copy items: Spotify, Apple Music, Deezer, Tidal, Qobuz, Amazon Music, YouTube Music (`TrackLinks.kt`; direct pages from song.link, otherwise a search page marked "(search)"). `TrackLinksTest` has 2 passing tests. song.link is unofficial and rate limited
- [~] Sort order is remembered for each list that has none saved (Songs, Favorites, albums, artists, generated lists; playlists already kept theirs): `SongList(listKey = ...)`, stored with `UiPrefs.putString("list_sort:<key>")`. Filters and search text are not saved
- [~] The six quick tiles on Home are a row you swipe sideways (three tiles per column, every playlist included, at least six tiles). Not tried
### Build
- [ ] The Android build is blocked by another session's unfinished player work: `player/.../TapProcessor.kt` (untracked) calls `DefaultAudioSink.Builder.setEnableAudioOutputPlaybackParams`, which this Media3 version lacks. Those files are not ours; do not touch them

### Windows installer did not start (found 2026-11-14 on a second PC)
- [x] Cause: the `v1.4.0-beta.2` Windows installer bundled a Java 17 runtime (`PAPERPLAY_JAVA_HOME` pointed at Temurin 17 to give ProGuard its jmods), but the app jars (`core`, `player`, `ui`) are Java 25 classes and `backdrop`/`shapes` need Java 21: `UnsupportedClassVersionError` before `main()`, so no window, no `startup.log`, launcher exit code 1. The script-built installer bundles Zulu 25 and works. Seen with a class-version scan of the installed jars and `runtime\release` (JAVA_VERSION 17.0.21)
- [~] Fix: the Windows CI job now uses Zulu 25 (jmods included) for Gradle and for `PAPERPLAY_JAVA_HOME`. Not built by CI yet. Until then use the script-built installer. Check next release: `runtime\release` says JAVA_VERSION 25

## Update 2026-11-14 (late night: deletes, language search, reading speed, Windows start-up). Key as above: [x] seen working, [~] built and installed but not tried, [ ] open
### Covers, toasts and the mini player
- [x] The progress notification and the Tasks page show the cover run (seen: "Loading covers", "Covers: 50 left", "2906 of 3014"). The count kept growing because it was finished plus waiting, and each cover found queued a full-size upgrade job
- [~] The cover run now works only on songs missing a cover (none, or the file is gone) and low-quality thumbnails with a full-size source, counted separately ("N missing, M left", "N low quality, M left"); the Tasks entry stays open 20 s after the queue empties so batches are one entry. Installed 21:34, numbers not seen yet
- [~] The mini player swipe finishes fully gone or fully back (the drag is a plain value; the settle animation could be cancelled by per-step coroutines); toasts sit above the mini player and the bar instead of over the page title. Installed, not tried
### Spotify lists
- [~] Greyed-out songs are rows of the playlist, placed by the chosen order (Playlist order by Spotify position, Title, Artist, Newest, Oldest by Spotify added date), not queued, not in recommendations, tap opens Spotify; a search gathers the matching ones after the songs. Placement is exact only for the original import
- [~] Copy links list only real song pages: the song's Spotify page, then song.link's pages (Apple Music, Deezer, Tidal, Amazon Music, YouTube Music), then an Apple Music or Deezer page matched by title, artist and length within 5 s; no search pages, no Qobuz. `TrackLinksTest` 2 tests pass
- [ ] Lyrics and song info are not fetched for songs that are not in the library (only the cover, from Spotify's oEmbed page)
### Deleting duplicates
- [x] Cause of "172 of 172 done" with the posts still in Telegram: `deleteMessages` through the bot's TDLib answers ok and skips posts that bot's local database does not have (older posts, other bots' posts), and the queue took ok as done and dropped the posts from the library
- [~] Fix: after that call the queue asks Telegram's server about each post (Bot API delete-one: deleted, already gone, or refused with a reason) and only then marks it Done; refused posts go to the account if it is logged in, else Failed. Installed 22:04, not tried on a real delete. The 172 posts the old version dropped must be brought back with Repair library
### Search and reading
- [~] Search by language: typed words (japanese, hindi, tamil, malayalam, korean, russian ...), also "japanese songs" and "lang:hindi". A song's language comes from its writing (`core/metadata/Language.kt`) and, in the Search tab, from the language its topic is named for (Japanese, Bollywood, Mollywood, Tollywood, Kollywood, Hollywood ...). Latin-letter songs outside such a topic cannot be told. `LanguageTest` 3 tests pass
- [~] "Adjust the reading speed by itself" (Settings > Library > Speeds, off by default): starts at the chosen or last good speed, +1/8 every 8 good requests, 60% after a flood wait with the old speed kept as a ceiling; saved as `reading_auto_rpm`; the reading slider now reaches 300 a minute. A Full rescan hit a flood wait at 20 a minute before this
- [~] The Library settings page: status first, then Repair library, Full rescan, Find duplicates and Sort General, then drop-downs for Speeds, Song info/covers/lyrics and More tools. Installed 22:27
- [ ] Idea, not built: after the normal scan, let every extra bot read the chat on its own, slowly, in the background (Repair library already does it by hand). With "Remain anonymous" on, new uploads are visible to every bot
### Windows
- [x] The `v1.4.0-beta.2` installer never started on any PC: it bundled Java 17 while `core`, `player` and `ui` are Java 25 classes (`backdrop` and `shapes` need 21). The script installer bundles Zulu 25. CI now uses Zulu 25; not run by CI yet (check `runtime\release` says 25)
- [x] The `v1.3.1` GitHub installer has no `tdjson.dll` (built before the DLLs were committed), so "Save and connect" does nothing there; likely, not checked inside the installer. A clean uninstall, deleting `%APPDATA%\PaperPlay` and reinstalling made it open on the second PC; the earlier non-start was probably old data or the one-copy lock
- [~] Onboarding showed the chosen profile photo as a mosaic (no image loader before the main screen) and the bot page said "Not ready yet" after a successful save; both fixed, not tried on the PC
- [ ] Before the next release: a start-up smoke test in CI (install the setup exe on the runner and start it), one Java target for all modules, a launcher that reports a start-up error, a software-drawing fallback. ARM Windows: x64 under emulation is untried; a native ARM build needs a JDK, skiko, libvlc and TDLib for ARM64
### Repository
- [x] Everything above is committed and pushed to `main` (`222d1e9`, then `3c5c03f` and `427f18b` on top of the release bot's `3771c21`); `beta` was brought level with `main` at `222d1e9`. The release workflow bumped `version.properties` to 1.4.0, wrote `CHANGELOG.md` and tagged `v1.4.0` by itself. Left uncommitted on purpose: the other session's audio-tap files.
- [ ] Build note: the Android build in this folder fails while those other files do not compile. A clean copy (`D:\PaperPlayBuild`, a git worktree of `HEAD` with this session's files copied over) builds the phone APK; remove it with `git worktree remove` when done

## Update 2026-11-14 (last round: tasks, sync counter, live duplicate delete, reader labels). Key as above: [x] seen working, [~] built and installed but not tried, [ ] open
### Song info page and Tasks
- [~] "Song info left" page redesigned: where the library stands (complete count and bar, lookups running, covers queued), "Show only" filter chips with counts, an Actions block that wraps (Look up again, Select all, Retry no match, Copy CSV, Save file), a thin divider between songs.
- [~] The page no longer hides its last rows behind the bottom bar: lazy pages (Song info left, Log) pad their list by the bar height instead of clipping it.
- [~] Tapping a running Looking up song info or Loading covers task opens that page on the Waiting or No cover filter, so the live list is one tap away.
- [~] Fast song info (Settings, Song info, covers and lyrics): 4 workers, Deezer asked first and, when it is sure (title, artist, length), the slow MusicBrainz and AudioDB calls are skipped. Off switch asks every source, as before.
### Reading and scanning
- [~] The sync banner counts up smoothly: the shown number is estimated between pages from the measured speed (never more than the pages asked for ahead) and snaps to the real count on each page, instead of jumping by 100.
- [~] A full scan or Repair names who is reading: "Bot 1" (main bot), "Bot 2", "Bot 3" for the extra bots, and "Your account: reading <chat>, N songs" for the account. The normal background scan stays unlabeled.
- [~] After a Full rescan the app offers to read with the other bots (and the account) too. Nothing is deleted.
- [~] The indexing count ("Indexing <chat>: N songs") counts up smoothly the same way: estimated between batches, snapped to the real count when a batch lands.
- [~] The scan label shows the bot's own name (display name, else @username) instead of Bot 1, Bot 2; the account shows as "Your account".
### Deleting duplicates
- [~] The delete counts one post at a time ("Deleting: n of 152 done"): each post is marked Done as soon as Telegram confirms it. The page drops a deleted post at once, and a song with no extra copy left goes with it. Pressing Delete scrolls the page to the top.
### Repository
- [x] Commits this round: language search, duplicate delete through the Bot API, auto reading speed, faster song info, Song info page with the Tasks tap, Library settings in drop-downs with the other-bot read, docs, then the live duplicate delete and the sync counter with reader labels.
- [ ] Owner still to check on the phone: greyed-out Spotify songs in order, cover counts, mini player and toast, a real duplicate delete (posts gone from Telegram), language search, auto reading speed, the sync counter and reader labels. Then look at the Windows job of the v1.4.0 run: `runtime
elease` must say Java 25 and the setup exe must start.
## Update 2026-11-15 (visualizer truth, standby alert icons)
- [x] Visualizer no longer invents motion: with no live audio it shows a quiet baseline (the old song-seeded movement is gone from `StandbyVisual`). If the bars stay flat while music plays, the tap is not getting audio. Visual reads the spectrum 120 ms late to match the speaker (`AudioTap.read`); the tap also reads 24 and 32 bit PCM now
- [~] Standby widget "Charging, call and notification icons" (off by default): bolt while charging, one status-bar-style icon per app with a notification, bigger pulsing icon for a call (WhatsApp calls included), count when more than one. `StandbyNotificationListener` (`ui/.../StandbyNotifications.kt`, declared in the app manifest); needs notification access, the customise sheet has a button for it; reads only app and call flag, never text; ongoing notifications (media, services) are left out. Not compiled or tried
- [ ] Try on the phone: grant access, get a message, a WhatsApp call, plug in the charger; check the icon looks right (an app with no loadable icon is left out; no bell)
## Update 2026-11-15 (later: lyrics, colours, exit, commits)
- [x] Particles redrawn as sparks rising from the bands (the odd big ball is gone); bars use a squared dB curve so only real peaks reach the top
- [x] Standby: first tap shows the settings button, a second tap exits; the charging bolt also shows beside the Battery line; the alert row sits below the battery line
- [~] Lyrics: the Lyrics widget is a 3 line scroller (smooth scroll, next line faded); new widget "Lyrics beside the song" shows 5 lines (3 in portrait) in place of the animation. Not seen on the phone
- [~] Colours: Cover is the cover's own main colour, Accent is the chosen app accent (matches the theme when the app accent follows the cover). Not seen on the phone
- [~] The settings sheet hides the system bars (`StandbyHideBars`); the first try still showed the status bar, the second looks for the sheet window in the view and its parents. Not seen on the phone
- [x] Standby work committed in five commits and pushed to `origin/beta` (an old build had overwritten the phone: other agents build from what is committed)
- [ ] Phone check once the latest build is on it: all 11 styles listed, bars follow the music, particles, lyrics, colours, status bar in the sheet, notification access and WhatsApp call icon

## Update 2026-11-15 (logging for the "songs stop early" report, crash records, memory line). Key as above: [x] seen working, [~] built and installed but not tried, [ ] open
### Playback log and sharing
- [x] Playback log on disk (`playback-log.txt`, rolls to `playback-log-older.txt` at 20 MB, never dropped): player state with position, duration and buffer at every change, why it paused, song changes with the reason (ended, seek, queue change), position jumps, decoder and input format, underruns, load and codec errors, full errors with stack; stream open, first read, short, slow and empty reads, the byte where data ended and what was never read; the stored size against Telegram's size (flags a mismatch), stalls; the controller's "now playing" and state lines with position next to both durations; the activity log lines too. Bot tokens are blanked. Seen working on the phone: songs played to the end, sizes matched, ALAC decodes (`c2.mtk.alac.decoder`).
- [x] Settings > Log: Share full log as file (share sheet, `paperplay-log-<date>.txt`) and Share as text (a log over 200 KB goes as a file so nothing is cut); the old save icon saves the full log too. The file starts with the app version, phone model, Android version and ABI.
- [ ] The reported "all songs stop before the end" is not reproduced: on the owner's phone songs played fully. Waiting for the reporter's log (Share full log as file after a song stops early). Look for `controller state Ended` with the position next to the durations, `stream EMPTY read`, a size mismatch, or a decoder error.
- [x] Correction: a scan line like "0 songs read, 0 new, 200 ids held nothing" is a normal up-to-date scan, not a bot that cannot see the chat.
### Crashes and the system log
- [x] Crash handler installed in the app class before any screen (it was only set when the main screen opened, so crashes in the playback service or at start-up left nothing). Writes the full stack to the playback log and keeps the last few crashes (up to 20 KB) in `crash.txt`, newest first, shown in the Log page box. Seen working: it caught `ClassNotFoundException` for a service another session had put in the manifest.
- [~] On every start Android's record of how the last run ended is written to the log as `EXIT` (crash, native crash, ANR, stopped for memory, signal), with the trace for native crashes and ANRs. Android 11 or later; older phones only get the Java crash.
- [~] Every Android log line of the app (all tags and levels) is copied to `logcat.txt` (8 MB, then `logcat-older.txt`; lines already saved are skipped by their time) and the newest 6 MB is added to the shared log. Costs CPU and flash writes on a chatty app; meant for test builds.
- [ ] Idea: put the logcat capture behind a "Detailed logging" switch (off by default in release) and stream the share instead of loading it as one text.
### Memory
- [~] A `memory` line every 10 seconds in the log (Android: Java heap, native heap, total and graphics memory, threads; desktop: heap, non-heap, direct and mapped buffers, loaded classes, threads; both: running jobs) and printed to the console on desktop. A number that only climbs is a leak. Desktop compiles with JDK 25; not run.
- [ ] How to measure by hand: Windows `jcmd` (pid), `jstat -gcutil <pid> 5s`, `jcmd <pid> GC.class_histogram` twice and compare, `jcmd <pid> GC.heap_dump`, then Eclipse MAT "Leak Suspects"; Android `adb shell dumpsys meminfo io.max.paperplay.compose` before and after, Android Studio Profiler heap dump, LeakCanary in debug.
### Repository
- [x] Four commits (`5e97c42`, `fdea970`, `01bf96a`, `c2cf455`) pushed to `main` and `beta`. The manifest was committed with only these changes: the other session's service entry points at a class this build copy does not have, and the build crashed on it until its entry was left out.
- [ ] Merge the friend's memory-leak branch when he pushes it, as a plain merge so he can update with a pull. Both sides add a memory monitor: keep one `MemoryProbe`, merge by hand where they overlap.


## Update 2026-11-16 (scan page, account reading, duplicates, search, rate limits). Key as above: [x] seen working, [~] built and installed but not tried, [ ] open
### Scan library and the account
- [x] One Scan library page (`RepairPage.kt`) with a switch for every source (main bot, each extra bot, your account) and the two reading dials; it replaced Repair and Full rescan (Profile > Library > Scan library, and the Scan button on Channels). Steps also go to the Activity log.
- [x] Reading with the account (`UserSession.songsIn`: `searchChatMessages`, filters Audio and Document, 100 a page) adds every main-chat post the library lacks; extra posts of one file are kept as `post_copies` (shown on Find duplicates, and now as rows in Library > Songs). Result line: "Posts returned by Telegram: 3571 by audio (Telegram counts 3571), 17 by document (Telegram counts 17)"; library 3465 songs + 123 repeats = 3588 posts, matching what Telegram's search returns.
- [x] `AUTH_KEY_DUPLICATED` on the account scan = the same login session used from two places; log out of the account and log in again (Settings > Telegram > Your account). A session that had not seen the chat answered "Chat not found" to every read: reads now load the chat first (`knowChat`).
- [x] Songs whose post is gone are removed after an account scan (`pruneGoneFromMainChat`; refuses when it would remove more than half the library). Log line: "Removed N saved songs whose post is gone from Telegram".
- [~] The account read keeps going past an early empty page and the live count is the real count (an estimate between pages ran ahead and dropped back); the counter says "posts (repeats included)" because repeats count there but not in Songs.
- [~] Topic names: after the account read, `getForumTopics` fills the names of the main chat's topics (bots cannot see topics another bot made), so Find duplicates no longer says "Unnamed topic". Line "Your account named N topics of the main chat". The TDLib field names were not checked against a live answer.
- [ ] The owner counts 3603 songs in Telegram; Telegram's own search total and the app agree on 3588. Not settled. Idea: ask the account for every message id 1 to 5308 (`getMessages`, about 54 calls) and count the songs, which no filter can hide.
### Reading speed and rate limits
- [x] "Messages per request" slider (10 to 500, default 100) next to "Reading channels". Cause of a 31 s rate-limit loop that lasted 22 minutes: 299 ids at 100 requests a minute, and the start-up catch-up read used the setting too.
- [x] Only a scan the owner starts uses the chosen ids per request; automatic reads (start, new posts) always use 100 ids and at most 50 requests a minute. A manual scan that is refused, or made to wait twice in a row, with more than 100 ids puts the setting back to 100 and stops. Start-up catch-up now finishes in 6 s with no waits.
- [ ] Whether Telegram answers more than 100 ids in one `getMessages` is not known; the account scan stays at 100 (the search call's limit).
### Find duplicates
- [x] Crash while deleting (15 Nov 23:48): `CursorWindow: Failed to read row 346 ... from a window with 345 rows`; a delete between two cursor windows shifted the rows. Fix: every library read runs in one transaction and is tried 3 times (`LibraryRepository.stableRead`). No crash since.
- [x] The account delete used to answer ok for posts it never loaded, so posts left the library but stayed in Telegram (the library lost songs this way). Now the account asks Telegram which posts still exist, and "Your account removed N of M: <reason>" is logged. Extra copies went 167 > 129 > 93 and the chat from 3650 to 3587 posts.
- [~] A post can be in two groups; a tick counts in both, so a group could show every post ticked. `keepOnePerGroup` unticks the copy to keep of such a group. The Delete button sits just above the bars and follows the mini player.
- [ ] Why the first deletes were refused ("Telegram wouldn't let this post be deleted") is not known; the new log line will say.
### Library, search, covers
- [~] Songs list shows every repeat post of a file as its own row (`SongUi.copyKey`); song rows always show format chip, bitrate and size.
- [~] New search engine (`SongSearch.kt`): accents, typos, words inside words, best match first, a matching artist as "Top result". Not tried on the phone.
- [~] Covers: Telegram thumbnails also through the account, the first 3 MB of the file when the post has none, give up only when Telegram answered; song info also asks LRCLIB and tries title and artist swapped. Songs without a match are retried only by a manual scan; no repeat every 30 min.
- [~] Home: all placeholders show first, then they cross-fade into the page (the blank step in between is fixed); the greeting line is chosen once.
### Settings and Profile
- [~] Profile Library list is shorter; Devices and Storage are one card. Settings > Telegram: no speed dials, the account row says Logged in or Logged out, the copy speed moved to the account page, a logout shows a toast ("Logged out", "Your account was not logged in" or the error). The account page restores the saved index off the main thread (lag).
- [x] The friend's memory-leak and performance commit `fd71732` is in `main`: all 479 added lines still present (checked 16 Nov); his repo has no newer commits. Memory over runs of 1 to 7 h is flat (7 h run: PSS 352 > 384 MB).
### Repository
- [ ] Nothing since `c2cf455` is pushed. Pull with `git pull --rebase --autostash origin main` first. The release build (`d009d44`) is on the phone; a release build cannot be read with `adb run-as`, install a debug build to read the logs.

## Update 2026-11-16 (event log, sharing the log as a file, standby Lyrics style). Key as above: [x] seen working, [~] built and installed but not tried, [ ] open
### Event log (none built or installed yet)
- [ ] `AppEvents.kt` writes to the shared log: activity lifecycle, memory trim and low-memory, configuration changes, screen on/off and unlock, charger, battery low and level (every 5 points), headphones plugged or noisy, airplane, time zone, locale, power saving, doze, network (connected, changed, lost, with wifi or mobile and metered), and a main-thread watchdog (stack of the main thread after 4 s without an answer).
- [ ] Every screen change in `Shell.kt` logs a `nav` line; every setting change (`LibraryRepo.setPref`) logs a `pref` line (value cut at 80 characters, names with token, secret, password, hash, key or auth show `<hidden>`).
- [ ] `PlayLog` holds up to 300 lines written before the log file exists and hands them over when the sink is set (start-up lines used to be dropped).
- [ ] Crash records were already there (`CrashLog`: Java crash, native crash, ANR with trace, memory kill, previous exit reasons, full logcat copy).
### Sharing the log
- [ ] Share and Save no longer build the log as one string (up to about 40 MB of playback log plus system log, copied several times). `PlayLogFile.writeAll` copies the files into one file in 64 KB blocks (`Fs.concat`) and the share sheet gets that file (`BackupFiles.shareFilePath`); Save copies the same file to the chosen place. Always a file, nothing cut. Last 8 MB of each logcat file are included. `readAll` and `fullLogText` are removed.
- [ ] Not checked on the phone: a share of a large log, and that the file opens on the receiving side.
### Standby
- [~] Lyrics animation style (12th style): scrolling synced lyrics in the animation area, 5 rows in landscape and 3 in portrait, bars when the song has no synced lyrics. The "Lyrics line" widget is back to a single line; "Lyrics, three lines scrolling" is its own widget. The side-lyrics widget is removed. The user reported a problem with "the lyrics one" but did not say which; open.
### Devices, sync and Standby (16 Nov)
- [~] Every linked device sends what it plays and where the sound goes (name and kind: headphones, speakers, phone) with each state, also when nothing plays; a state older than 12 s counts as quiet. "Playing on <device> via <output>" shows in the player pill, the device sheet, the PC devices button and the strip.
- [~] Switching the source waits for the other device to report the song before this one pauses; if it does not answer in 6 s nothing changes. Playing here again ends control of another device.
- [~] The PC names its real output through Windows Core Audio (checked here: "Headphones (realme Buds Air8 Pro)").
- [~] Songs deleted on one device are removed on the other at the next sync, unless the file was posted again later; the record expires after a year. Song info, lyrics and covers fill in between devices, hand-made data first.
- [~] Standby: Lyrics shows 7 (landscape) or 5 lines with faded top and bottom, plain lyrics scroll, the instrumental notice means no lyrics ("No lyrics for this song"), the cover tile is grayscale.
- [ ] Not tried on two devices at once.
### PC parity (16 Nov)
- [~] A playlist imported from Spotify keeps its link and its "not in your library yet" list on every linked device, so the refresh and the missing row show on the PC too; a device that already has its own link keeps it.
- [~] Spotify import lists the logged-in account's own playlists with Import or Refresh (needs the Spotify connection; the paste box stays for other people's links).
- [~] PC: right click on a song row opens its menu; Alt+Left goes back; sharing copies text to the clipboard or shows the file in Explorer; Spotify import is in the sidebar Tools; the keep-on-device pin shows on album, artist and Telegram lists too.
- [ ] Not done: right-click menu on sidebar playlists, Go to album or artist in the song menu, Ctrl+F for search.
- [~] Artists: a credit like "A, B & C" is split; the full player and the PC now-playing panel show each artist as its own tap target (and the album), the song menu has "Go to <artist>" per artist and "Go to album", and an artist page and the Artists tab list a song under each of its artists.
### Status (16 Nov, end of day)
- Built and installed (phone release, PC installer) but not seen working on two devices at once: shared player and source switch, output names, sync of song data, covers, Spotify links and deleted songs, the PC right-click menu, Alt+Left, tappable artists and albums, the Spotify account list.
- Open: right-click menu on sidebar playlists; Ctrl+F for search; topic names do not sync between devices; why the first duplicate deletes were refused is still unknown; the 3603 vs 3588 count.
- Pushed to `main` and `beta` at the end of 16 Nov (see Repository below).
### Audio, lyrics and covers on two devices (16 Nov, evening)
- [~] Audio: the Android sink now outputs float, so 24-bit FLAC is not cut to 16 bits without dither (the likely cause of the grain; the playback log now has an "audio output ..." line with the real format). Not heard yet: play a 24-bit file and compare.
- [~] Both devices pick the same album tags and cover when each found its own; a text or cover chosen by hand wins.
- [~] Lyrics: the two devices converge on one text (timed before plain, then longer); a lyrics choice made by hand wins and its time travels; the choice lives in the `lyrics_meta` preference (no table change).
- [~] Lyrics pane (phone and PC): the translate button lists the versions the sources have (LRCLIB records with their script, NetEase original, romanized and translation) and keeps the picked one for the song; Automatic forgets the pick.
- [ ] No global default language yet (only a per-song pick); QQ and Kugou variants are not read.
- [x] `UserSessionTest.deleterBatchesAndReportsRefused` fixed (the fake answered the existence check with no message list).
### Touch-through and generic covers (16 Nov)
- [~] Nothing underneath is pressed through the player sheet or the mini player and navigation bars (`blockTouchesBehind`); to check on the phone: open the player, tap empty areas, the list behind must not play. Other overlays (dialogs, sheets) already have their own scrim.
- [~] Covers: a match on a compilation ("Bravo Hits 95", "Top 100", "Now That's What I Call...") comes after the song's own album; a cover is never taken from a compilation; MusicBrainz releases that are compilations sort last. Settings > Library > "Fix compilation covers" clears album, cover and info of songs that already got one (hand-edited ones and file-tag albums stay), then Scan library looks them up again.
- [~] Artist pages list every song an artist is credited on (a phone on an older build showed one song for "Dhee"); check after the install.
### Rewind and packaging (16 Nov, night)
- [~] Rewind (`PlaybackController.previous`): one press plays the song again from the start; a second press within 2.5 s goes to the song before; on the first song every press only restarts it. Tested with a fake engine. The notification and headset buttons use Android's own previous rule (restart only after 3 s) and are not changed.
- [~] The desktop package includes `java.management`, so the PC memory line works (the log said "could not measure: ManagementFactory").
- [ ] PC folder upload: reported broken, not found. The PC log shows a folder upload working at 16:13 and the folder picker script compiles; need the exact symptom (button, dialog, nothing after picking, or songs not uploading; "Choose folder" is disabled until an upload target is chosen).
### Repository
- [x] `main` and `beta` were pushed together at the end of 16 Nov (both remote branches were already inside `main`, so both were fast-forwards).
