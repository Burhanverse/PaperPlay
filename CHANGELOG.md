# Changelog

All notable changes to PaperPlay are listed here, newest first. Each stable release adds its section automatically.

## [1.9.8] - 2026-11-18

### Other changes
- Checklist: settings pages and stray songs ([`3bf3272`](https://github.com/maxcodl/PaperPlay/commit/3bf32729b91de19bd5a2594f1bf05ebb8eb08d3b))
- Settings: rows keep icons and trailing content, Library and Telegram pages in cards, Clear deleted songs ([`803183b`](https://github.com/maxcodl/PaperPlay/commit/803183b241d818742233980c4a247aef271c274d))
- Backend: clear deleted songs, clearer message when nothing needs copying ([`7b1c72e`](https://github.com/maxcodl/PaperPlay/commit/7b1c72e0471924c4f09c3a814ec9b1be6349fb4f))
- Index: copy posts again that no bot can read, clear library-only songs whose post was deleted ([`1607537`](https://github.com/maxcodl/PaperPlay/commit/16075379ff81b9c22c80920682f6d23c81fbfd55))
- Readme: chat index, copying and songs the bot can't read ([`30450bd`](https://github.com/maxcodl/PaperPlay/commit/30450bdde814aa5beefbfd25a66ab182b3e8dc55))
- Checklist: chat index and copying ([`847ac13`](https://github.com/maxcodl/PaperPlay/commit/847ac134de29ed53459c1f9e21162cb1a09154e7))
- Library: copy picked songs to the chat, select songs the bot can't read ([`023dcbd`](https://github.com/maxcodl/PaperPlay/commit/023dcbdcf17ffb1c368521835992e382af01adac))
- Chats page: details, open and remove, pause and resume, copy speed, background indexing, + for Spotify matches ([`2be241f`](https://github.com/maxcodl/PaperPlay/commit/2be241f3453552dfe9baf8dbfc87f61e9f941d4e))
- Index: resumes stopped reads, matches the whole library, copies into the main chat with a fallback, library-only songs move to their post ([`986161f`](https://github.com/maxcodl/PaperPlay/commit/986161f60601ba26112b69924c99a5a267654762))
- Notifications: the first running task is the service notification, no standing card, service stops 20 s after work ([`d72c820`](https://github.com/maxcodl/PaperPlay/commit/d72c820bd3b1afd273baf86928c988dc05c81043))
- Checklist: uploads, names and the desktop layout ([`7ab5616`](https://github.com/maxcodl/PaperPlay/commit/7ab56168edbd128e8088ea234235ecd528483a63))
- Desktop: the now playing view keeps the chosen background instead of a cover colour wash ([`83241f3`](https://github.com/maxcodl/PaperPlay/commit/83241f37244bb8960252f65b840acadba2a5ce74))
- Desktop: room for the now playing panel is kept from the first frame so the page does not re-lay out ([`756bd7b`](https://github.com/maxcodl/PaperPlay/commit/756bd7b8526fc9b5114754af531be6ccb55162e6))

**Full changes:** [v1.9.7...v1.9.8](https://github.com/maxcodl/PaperPlay/compare/v1.9.7...v1.9.8)

## [1.9.7] - 2026-11-18

### Fixed
- Adjust bitmap size and update blur handling in CoverBlurLayer ([`d71007c`](https://github.com/maxcodl/PaperPlay/commit/d71007ca4afa8d28f14e212e9963a5e957d79d4b))

**Full changes:** [v1.9.6...v1.9.7](https://github.com/maxcodl/PaperPlay/compare/v1.9.6...v1.9.7)

## [1.9.6] - 2026-11-18

### Other changes
- Upload: title and artist come from the file's own tags; the sending bot owns the new song ([`80aeb0b`](https://github.com/maxcodl/PaperPlay/commit/80aeb0b7b4c2f86b429b5141a78c1173f5b4c742))
- Names: Song - Artist file names are swapped back using the artists the library already knows ([`64ca4b4`](https://github.com/maxcodl/PaperPlay/commit/64ca4b4a3fc43a43bb7441eb39aa096cd4fc4d6e))
- Agents: audio engine layout, tests, clicks, headroom and the path for a new setting ([`16d573f`](https://github.com/maxcodl/PaperPlay/commit/16d573f5fb2a42143d58048b383d5a97b2bdeaf1))
- Engine: settings per output, dynamic bass, de-esser and crossfeed; checklist ([`7491b9d`](https://github.com/maxcodl/PaperPlay/commit/7491b9d0452db7f1539d0ebaacbab7fcd80ea90a))
- Agents: rules for wrong tags and checking real data ([`16b39e0`](https://github.com/maxcodl/PaperPlay/commit/16b39e0ae4d451ac96027b69444d400f2a034f84))
- Agents: measure speed claims, phone and Telegram test rules, line endings, stop builds you started ([`a5a65fa`](https://github.com/maxcodl/PaperPlay/commit/a5a65fa89b330e04f93e39e353dfad573ec1cb60))
- Readme: Home animation setting, old posts need the account to delete ([`a456659`](https://github.com/maxcodl/PaperPlay/commit/a4566599ea47da5a87bbc9169b17353d99c6b72f))
- Agents: compile after install, bots delete only recent posts, composition and animation rules ([`185b40d`](https://github.com/maxcodl/PaperPlay/commit/185b40d1e1325b2d6f95f6497cc70b3173797ef8))
- Checklist: deletes and duplicates, start-up and Home, notifications (18 Nov) ([`40d187c`](https://github.com/maxcodl/PaperPlay/commit/40d187cd6805b8c62a42722fc9b0ce510f838900))
- Checklist: swapped names and the blank notification ([`4981d7f`](https://github.com/maxcodl/PaperPlay/commit/4981d7f240359a2a8239a20ef772c164c5b446c8))
- Engine: earphone correction (AutoEq parametric) on the phone, engine extras in the player equalizer ([`59c404a`](https://github.com/maxcodl/PaperPlay/commit/59c404a69c88927741d0e96e178a82a7f1e5ae51))
- Engine: bass keeps its rumble with less boom, stereo width above the bass, clearer bass and vocal presets ([`c8669da`](https://github.com/maxcodl/PaperPlay/commit/c8669da8611824f132e5b26b77087b02ed744f39))
- Engine: smooth changes, true-peak ceiling, held bass limiter, exact level mode, level-matched A/B ([`af1374e`](https://github.com/maxcodl/PaperPlay/commit/af1374e95f51416ed4e66b387f9ac02ac7e7ce02))
- Notifications: the service's own notification no longer counts running tasks ([`59213a7`](https://github.com/maxcodl/PaperPlay/commit/59213a79c8d723ff3ed298b8688fa373b92e5b5b))
- Media notification: posted ungrouped so the shade does not draw it as an empty card ([`6e7eb06`](https://github.com/maxcodl/PaperPlay/commit/6e7eb06597690517c1557edc8e0ef665af0d4c4d))
- Home animation: no tilt (it re-uploaded every cover each frame) and fade per piece ([`bc557cb`](https://github.com/maxcodl/PaperPlay/commit/bc557cb63e9322fecfba486b3ec6d315546e93bd))
- Home: waits for its data before animating in, no spinner at start, second background deferred; setting to turn the home animation off ([`330a4d4`](https://github.com/maxcodl/PaperPlay/commit/330a4d43180c87a34c70dbb1fc0731d4b786e692))
- Home: no placeholders, the animation waits for the first frames; the saved page loads beside the backend with one query ([`5af36f1`](https://github.com/maxcodl/PaperPlay/commit/5af36f1332bad5496bbb52f02895b9ac2fea6af5))
- Names: swapped title and artist tags are fixed, repair runs again ([`2cc4e2d`](https://github.com/maxcodl/PaperPlay/commit/2cc4e2d2f29672579f20fa7b69eee91e6ea86889))
- Home: placeholders leave at once when the fly-in starts; the first covers no longer wait for the busy main thread ([`4373f9e`](https://github.com/maxcodl/PaperPlay/commit/4373f9e81a7433901513b2ec28e1f4e0bc624db5))
- Home: wide windows get fixed-width tiles beside a compact recently played row; covers are scaled to the asked size and pre-uploaded off the main thread ([`e4d48d2`](https://github.com/maxcodl/PaperPlay/commit/e4d48d20689c77e5981843543f5858438fc96c6a))
- Home: last launch's page is drawn at once with its covers; songs, albums and artists are built only when shown; tiles and cards fly in at start ([`e2e2275`](https://github.com/maxcodl/PaperPlay/commit/e2e227562236d332cd6b187b37fd329ee1d91043))
- Home: the plan no longer waits for the whole library or restarts for weather and taste settings ([`8c1b3db`](https://github.com/maxcodl/PaperPlay/commit/8c1b3dbe7e325aebfed5fecd49f18887a21c929c))
- Startup: home plan no longer restarts when the scan or covers begin; warm the library and home flows early; update: skip saves the release shown ([`60e9259`](https://github.com/maxcodl/PaperPlay/commit/60e92591d17f3f10309f3ca409f8a9923a5be15d))
- Home: topic lists built off the main thread, cover queue no longer redraws the shell; duplicates: notice for posts only the account can delete ([`f8df3d6`](https://github.com/maxcodl/PaperPlay/commit/f8df3d6898dda6e704ab4388885571582d735f02))
- Update: download has timeouts and retries a connect timeout; desktop no longer caches the first dead GitHub address ([`59b2081`](https://github.com/maxcodl/PaperPlay/commit/59b2081b90fac54378ec2e96dfb55f1cab1a7ca2))

**Full changes:** [v1.9.5...v1.9.6](https://github.com/maxcodl/PaperPlay/compare/v1.9.5...v1.9.6)

## [1.9.5] - 2026-11-17

### Improved
- Keyed shelves and queue, idle polling gated, no right-click handler on phone ([`aacd1f7`](https://github.com/maxcodl/PaperPlay/commit/aacd1f756faf9e1ace056d214309c6a09586b914))

### Other changes
- Delete: posts older than 48 hours are not tried through bots; the error says to log in the account ([`687eff2`](https://github.com/maxcodl/PaperPlay/commit/687eff265eb293db240c32789208e94a71c1aec8))
- Ignore the local secrets folder ([`a3c293e`](https://github.com/maxcodl/PaperPlay/commit/a3c293efe9e059957b82a371b8ccd86814d524ed))
- Notifications: the activity line is not shown when it repeats another task ([`2e90a70`](https://github.com/maxcodl/PaperPlay/commit/2e90a703d4301e4802619957efbb958cede8536d))
- Glass: nav bar blurs the page colour too; duplicates: bot names, no stray @, refresh drops posts every bot cannot see ([`f10ade3`](https://github.com/maxcodl/PaperPlay/commit/f10ade395e694be2d4bc1e51414785c4c0722d0c))
- Duplicates: each post shows the bot the library holds it under, and the delete log says which bot was tried for each post ([`87d65cb`](https://github.com/maxcodl/PaperPlay/commit/87d65cb8091f70d9418afbbbb461d93adffcd6f9))
- Duplicates: the post text shares the row so the menu button is never pushed off ([`0499d93`](https://github.com/maxcodl/PaperPlay/commit/0499d937e915fb7518d67023519a552603ec0060))
- Delete: posts every bot says are not found are treated as gone; duplicates page rows get a menu to open the post in Telegram ([`f91235d`](https://github.com/maxcodl/PaperPlay/commit/f91235da6cd8fe6bf7071884d63b3e113b42deb6))
- Delete: the owning bot deletes its own posts, keeper check only with the account logged in, rights diagnosed in the log; Tasks row counts running tasks ([`2d996bb`](https://github.com/maxcodl/PaperPlay/commit/2d996bb3261d3a92fe444fb4e1883d8abd3f30c5))
- Names: split Song - Version - Artist files correctly at upload and scan, and repair the songs already split wrong ([`0d978f3`](https://github.com/maxcodl/PaperPlay/commit/0d978f3dc2d275e8c645f243c84a8ac1ee359690))
- Notifications: each running task is its own notification with its own bar; library list rebuild rate-limited; Home sections ease in; upload buttons ([`1953925`](https://github.com/maxcodl/PaperPlay/commit/19539251884d25456e6958c6247d579d259ef35c))
- Delete: extra bots are judged by Telegram's own answer per post, not by a failed fetch; notification also shows deleting and updates faster ([`4e7e97e`](https://github.com/maxcodl/PaperPlay/commit/4e7e97eb57743467ec250f8e6b033b8037d88f35))
- Selection bar: play, playlist and favorite as icons so the count fits ([`11e5fb6`](https://github.com/maxcodl/PaperPlay/commit/11e5fb6d643d25f41b0298d21b0e8a7b8fd5a085))
- Uploads: speed, totals and time left, percent per file, and an Upload entry on Profile ([`fb8138f`](https://github.com/maxcodl/PaperPlay/commit/fb8138f747d209f9ac2022e2d6c472c0ce72e553))
- Selection bar can delete songs from Telegram; duplicates buttons wrap instead of squashing ([`01b1e84`](https://github.com/maxcodl/PaperPlay/commit/01b1e84bf431b493e26229732cbc131e770c8493))
- Delete: five posts per call so progress moves in small steps ([`da8b02d`](https://github.com/maxcodl/PaperPlay/commit/da8b02dfbbdcce4ca3430af3fe5f1ece1356da93))
- Duplicates: wider re-upload size match and a tick all but the best button ([`753c98b`](https://github.com/maxcodl/PaperPlay/commit/753c98b83a9a0bed79c4bb6eb79dbf79bfa66c96))
- Delete: extra bots take the posts the account and main bot could not, and the error names Telegram's reason ([`988ea34`](https://github.com/maxcodl/PaperPlay/commit/988ea34fd3101e3b2b3bb9e43674cf48e497c16c))
- Filter chip for songs the bot can't reach is always shown, with a count ([`145ba15`](https://github.com/maxcodl/PaperPlay/commit/145ba154b22f4aa8f43701b3600f50e27b316fd4))
- Start-up: Home waits for its lead covers, any-size cover shown while the exact one decodes, softer cover fade ([`42209a5`](https://github.com/maxcodl/PaperPlay/commit/42209a5ad17bfa9f6af53c27e34c2cbd95f28ab8))
- Songs only an account can read are greyed with a warning and can be filtered ([`e533c1c`](https://github.com/maxcodl/PaperPlay/commit/e533c1cd0798ec11ba97301c72206cb0ca1ce128))
- Update: find Shizuku builds with a suffixed package id ([`f59c692`](https://github.com/maxcodl/PaperPlay/commit/f59c69231b0f77df68e091512021e6da979c0833))

**Full changes:** [v1.9.4...v1.9.5](https://github.com/maxcodl/PaperPlay/compare/v1.9.4...v1.9.5)

## [1.9.4] - 2026-11-17

### Documentation
- Checklist for account songs, bit-perfect, details popup, standby, delete notice ([`675becf`](https://github.com/maxcodl/PaperPlay/commit/675becf28ae62130b87cd3811945f7dda5102667))

### Other changes
- Ui: equalizer shows a fader board, true audio a waveform ([`a0b9f8e`](https://github.com/maxcodl/PaperPlay/commit/a0b9f8e59e55c86770617e8372bdf83c5e9eea59))

**Full changes:** [v1.9.3...v1.9.4](https://github.com/maxcodl/PaperPlay/compare/v1.9.3...v1.9.4)

## [1.9.3] - 2026-11-17

### Other changes
- Scan: an extra bot takes over the songs saved as account songs when it can read their posts ([`24df215`](https://github.com/maxcodl/PaperPlay/commit/24df215840c8aa1a8aec9485d6cd33a9aa03ad1b))

**Full changes:** [v1.9.2...v1.9.3](https://github.com/maxcodl/PaperPlay/compare/v1.9.2...v1.9.3)

## [1.9.2] - 2026-11-17

### Other changes
- Streaming: remember which bot found an account song so later plays and covers go straight to it ([`225adcb`](https://github.com/maxcodl/PaperPlay/commit/225adcb727884b71af5811fcbe5a1fefc1b937a1))
- Player: stream errors answer the player and are logged, mpv skips its youtube-dl hook ([`a5ed339`](https://github.com/maxcodl/PaperPlay/commit/a5ed3390c6110ddae2012791d3bd14d2bf2191f6))
- Streaming: songs added through an account play through the bots when the account is gone ([`edd3bc5`](https://github.com/maxcodl/PaperPlay/commit/edd3bc541a323580856c1f1015237db51ef06fef))
- Standby: always release keep-screen-on, even if closing the dialog window fails ([`201be1b`](https://github.com/maxcodl/PaperPlay/commit/201be1b37c03dbf7743e426c5a77e8746715445a))

**Full changes:** [v1.9.1...v1.9.2](https://github.com/maxcodl/PaperPlay/compare/v1.9.1...v1.9.2)

## [1.9.1] - 2026-11-17

### Other changes
- Ui: Top bars look and the Dot and Text selection looks are wired; checklist ([`c9ac84f`](https://github.com/maxcodl/PaperPlay/commit/c9ac84f5692b54e17235b82d43807802bf1cb56a))
- Ui: player bar background is transparent, card or glass ([`50957af`](https://github.com/maxcodl/PaperPlay/commit/50957afe9a9914f0cc0a3cba85b6c1eb2d0d82fc))
- Ui: glass settings (edge, shadow, glow, saturation) apply to every glass surface ([`f0982e9`](https://github.com/maxcodl/PaperPlay/commit/f0982e928f63d0bed00ad262d400bd874404bb9d))
- Delete: say when copies stay in other chats and log which posts went ([`85dbc45`](https://github.com/maxcodl/PaperPlay/commit/85dbc45d3d44f4b722ed2b5c8e5273f2514cb115))
- Ui: keyboard shortcuts stay off while a text box holds focus ([`b923a48`](https://github.com/maxcodl/PaperPlay/commit/b923a483d05c829b1b4efa9adb859281cd2bb2ff))
- Ui: separate corner size for panels, cards, covers, chips and dialogs; the bit-perfect row matches the equalizer row ([`0c88c8c`](https://github.com/maxcodl/PaperPlay/commit/0c88c8ce4f0ff2d50977808f1d1bd66606f06902))
- Ui: Settings switch for the taskbar buttons ([`fab179e`](https://github.com/maxcodl/PaperPlay/commit/fab179e463d8bde5d001a0be1cb6563939456f19))
- Desktop: previous, play or pause and next buttons under the taskbar preview ([`b1111c1`](https://github.com/maxcodl/PaperPlay/commit/b1111c119511f57a026a5210029fe2b14827efed))
- Ui: the bar shows the sound status while Now playing is open ([`2408336`](https://github.com/maxcodl/PaperPlay/commit/240833647e16d6b442a3bc953b3ba14f18324db4))
- Player: mpv output reloads itself after under-runs and when the song's format changes, and logs what it opened ([`ee38aa6`](https://github.com/maxcodl/PaperPlay/commit/ee38aa600254a5454ecebde4d1c44e1e737cbe06))
- Ui: toggles use a contrast-aware on colour, equalizer bars icon, animated true-audio gem button ([`13fe78b`](https://github.com/maxcodl/PaperPlay/commit/13fe78bc2e95aae8b0e13a2e38d589b1a4e85fd9))

**Full changes:** [v1.9.0...v1.9.1](https://github.com/maxcodl/PaperPlay/compare/v1.9.0...v1.9.1)

## [1.8.11] - 2026-11-17

### Other changes
- Player: bit-perfect opens the device at each song's own rate and takes exclusive output again on every new song ([`1b63230`](https://github.com/maxcodl/PaperPlay/commit/1b63230c59ec35b28b1c4a97d613f7d6fc1541d3))
- Ui: toggle buttons show a fill when on ([`f2df8cb`](https://github.com/maxcodl/PaperPlay/commit/f2df8cb0644be4459c1e9c5a282913d9642b850e))
- Ui: smaller logo and version text in the Profile About row ([`2dddafb`](https://github.com/maxcodl/PaperPlay/commit/2dddafb14d3433bcdd16652b8a8607c5c93edb77))

**Full changes:** [v1.8.10...v1.8.11](https://github.com/maxcodl/PaperPlay/compare/v1.8.10...v1.8.11)

## [1.8.10] - 2026-11-17

### Documentation
- Sidebar lists fold like Tools ([`b93a7c5`](https://github.com/maxcodl/PaperPlay/commit/b93a7c530e3317cbc635805415c75b3bf5858880))

### Other changes
- Ui: Playlists and From Telegram fold like Tools in the PC sidebar ([`4d6d515`](https://github.com/maxcodl/PaperPlay/commit/4d6d515571d37ec281dd23b754ae5943096d4318))
- Player: mpv warnings go to the activity log ([`e882046`](https://github.com/maxcodl/PaperPlay/commit/e8820468fb016e81558ad16444827e013a475d65))

**Full changes:** [v1.8.9...v1.8.10](https://github.com/maxcodl/PaperPlay/compare/v1.8.9...v1.8.10)

## [1.8.9] - 2026-11-17

### Other changes
- Ui: the PC bar leaves out transport while Now playing is open, bit-perfect gets its own icon ([`9714674`](https://github.com/maxcodl/PaperPlay/commit/97146740f261690f83fe66e31927724d258f359f))
- Sync: a playlist change reaches linked devices within seconds ([`3ff3d8b`](https://github.com/maxcodl/PaperPlay/commit/3ff3d8b3ff0294b5468df21699ec23456712d1a9))
- Installer: end the app without its child processes so the in-app updater's setup survives ([`066a423`](https://github.com/maxcodl/PaperPlay/commit/066a42325f81ed45d63d2f00a36aedf1e127ede5))
- Ui: smaller playlist cards on Home so more show in the sideways row ([`6635ff0`](https://github.com/maxcodl/PaperPlay/commit/6635ff0f50ddfe7398d73c5aee2ca24a07a2d639))

**Full changes:** [v1.8.8...v1.8.9](https://github.com/maxcodl/PaperPlay/compare/v1.8.8...v1.8.9)

## [1.8.8] - 2026-11-17

### Other changes
- Ui: one details popup for the song and its sound ([`a1ac296`](https://github.com/maxcodl/PaperPlay/commit/a1ac296dca237823d92305fc05111edf6e7fadfd))
- Ui: bit-perfect button in the PC player bar ([`d61d03a`](https://github.com/maxcodl/PaperPlay/commit/d61d03a113b75e1f0f97cc549fa25a5dfa77a991))
- Player: mpv engine gives back exclusive output while paused and takes it again on play ([`2fcfdd7`](https://github.com/maxcodl/PaperPlay/commit/2fcfdd77ebf494f48dad8e95dfea683b59636f1b))
- Memory: trim idle pages when the PC window is hidden, free decoded covers when the phone app is in the background ([`c051b1f`](https://github.com/maxcodl/PaperPlay/commit/c051b1f2ba025ed29935cbd1dd1abed9c3ef1618))
- Ui, core: a picked cover is shrunk once when saved ([`e396023`](https://github.com/maxcodl/PaperPlay/commit/e3960236ad55520ebc4914aff78c5cd8568f9399))
- Ui: the page under the full player is not composed while the player covers it ([`31e93be`](https://github.com/maxcodl/PaperPlay/commit/31e93be57991e04efd74e1bef6abef07d3f137c8))
- Ui: Home keeps recently played on top and folds the other shelves into one row of cards ([`e51aba4`](https://github.com/maxcodl/PaperPlay/commit/e51aba44e4a5b6d9d7884e30f6c3fcbd384ef82f))
- Installer: end the tray-hidden app before replacing files ([`9a8560f`](https://github.com/maxcodl/PaperPlay/commit/9a8560fdf236e36642e03ae684e1b85c59311077))

**Full changes:** [v1.8.7...v1.8.8](https://github.com/maxcodl/PaperPlay/compare/v1.8.7...v1.8.8)

## [1.8.7] - 2026-11-17

### Other changes
- Ui, player: bit-perfect mode on the PC, bass gives back some level, Output info from the player bar ([`f1486e6`](https://github.com/maxcodl/PaperPlay/commit/f1486e6a37eaad3f2c4b2368ddd7465360758f8d))

**Full changes:** [v1.8.6...v1.8.7](https://github.com/maxcodl/PaperPlay/compare/v1.8.6...v1.8.7)

## [1.8.6] - 2026-11-17

### Other changes
- Ui: Bass dial on the PC with the mpv engine and an Equalizer button in the player bar ([`c909213`](https://github.com/maxcodl/PaperPlay/commit/c909213f3c7c6bafa6c418eeb155f090d79c4d6e))
- Ui: playing song and queue pick up a changed cover at once ([`ac2aa15`](https://github.com/maxcodl/PaperPlay/commit/ac2aa15e4a72a10a3a11dbd29d652362ed356e47))
- Player: mpv engine remembers a seek made before the song loads ([`6b1fef4`](https://github.com/maxcodl/PaperPlay/commit/6b1fef4290ee0d14fca275a039f3bf6f6a351235))

**Full changes:** [v1.8.5...v1.8.6](https://github.com/maxcodl/PaperPlay/compare/v1.8.5...v1.8.6)

## [1.8.5] - 2026-11-17

### Documentation
- Mpv engine notes and checklist ([`20a15d1`](https://github.com/maxcodl/PaperPlay/commit/20a15d1f4dc7393e08227471f042fe331fd48b65))

### Other changes
- Player: mpv output info reads the real exclusive setting and names the default device ([`0d62b75`](https://github.com/maxcodl/PaperPlay/commit/0d62b758316b1c82014131ccc68f758e94d5ec85))
- Ui: PC settings for the mpv engine, exclusive output and correction profile ([`d915b89`](https://github.com/maxcodl/PaperPlay/commit/d915b89a27c6e2d0f449a12d7f48a1f2ed9b897c))
- Player: mpv engine for the desktop with equalizer, bass, profiles and exclusive output ([`473e388`](https://github.com/maxcodl/PaperPlay/commit/473e3880d9df2c6186dc2087cd285c1882157b1c))

**Full changes:** [v1.8.4...v1.8.5](https://github.com/maxcodl/PaperPlay/compare/v1.8.4...v1.8.5)

## [1.8.4] - 2026-11-16

### Documentation
- Audio engine rules and checklist ([`4a15dd4`](https://github.com/maxcodl/PaperPlay/commit/4a15dd4bea7ac3477afa122868d729df71e597e9))

### Other changes
- Ui: equalizer in the player menu, bass slider, earphone presets, engine rows in output info ([`5356454`](https://github.com/maxcodl/PaperPlay/commit/53564548c2f2e469e6d7dd9bdc2b59cf74c91bc4))
- Player: run the engine equalizer and bass in the Android audio path ([`d63107e`](https://github.com/maxcodl/PaperPlay/commit/d63107e79a3eec3ef10f74fcc0a98b48cb46b0a7))
- Engine: Rust equalizer, bass and limiter library ([`faee885`](https://github.com/maxcodl/PaperPlay/commit/faee8850b5229e724f3f5f581d5f9e6ce01f5b91))

**Full changes:** [v1.8.3...v1.8.4](https://github.com/maxcodl/PaperPlay/compare/v1.8.3...v1.8.4)

## [1.8.3] - 2026-11-16

### Fixed
- Remove the stray space from the repository URLs after the rename ([`c91d9cf`](https://github.com/maxcodl/PaperPlay/commit/c91d9cf52f105fa696427d113d23eb34f7a0434c))

**Full changes:** [v1.8.2...v1.8.3](https://github.com/maxcodl/PaperPlay/compare/v1.8.2...v1.8.3)

## [1.8.2] - 2026-11-16

### Other changes
- Playback: picking from the queue keeps its order (shuffle no longer reshuffles it) and rewind goes back to the song played before ([`0912b8c`](https://github.com/maxcodl/PaperPlay/commit/0912b8cce0976c0ccfbe973a56a4eb88e2745bed))

**Full changes:** [v1.8.1...v1.8.2](https://github.com/maxcodl/PaperPlay/compare/v1.8.1...v1.8.2)

## [1.8.1] - 2026-11-16

### Other changes
- Desktop player: shorter input buffer so a song starts sooner, and the log says codec, rate and start delay ([`89b7692`](https://github.com/maxcodl/PaperPlay/commit/89b7692780baad9470fcfe994f486883a30a44a3))
- Playback: a short fade on pause and on resume, none when a song starts fresh ([`b2a5b28`](https://github.com/maxcodl/PaperPlay/commit/b2a5b28cd17bae5ce522a7ed09015de6a4449624))
- Desktop: picking a music folder logs what it found, reports a read error, and answers on the window's own thread ([`9bd9756`](https://github.com/maxcodl/PaperPlay/commit/9bd975635f79badf42696bd94add90dc77d4a0eb))
- Missing songs: the add button says why it cannot add instead of doing nothing ([`ddb0566`](https://github.com/maxcodl/PaperPlay/commit/ddb0566a1774dea0fc21f01ddc9469caba0929a4))
- Log: a removed song names the code that removed it ([`4a69017`](https://github.com/maxcodl/PaperPlay/commit/4a6901777495ee916cdd94c68e3bc85b45b944a3))
- Devices: a screen with nothing playing follows the paired device that plays ([`1925fcc`](https://github.com/maxcodl/PaperPlay/commit/1925fcc9c6594a9f485ca809660f01fedc78e337))
- Playback: the song and the next two download in full, so seeking in them is instant ([`8db8e2b`](https://github.com/maxcodl/PaperPlay/commit/8db8e2b3deb432190044af0225060c4a048ac586))
- Desktop player: a stream end right after a seek is not the end of the song, the song reopens at the place asked for ([`e7190be`](https://github.com/maxcodl/PaperPlay/commit/e7190be5314df6e800f341b177038c7159568646))
- Desktop: Open in Telegram uses the t.me link, a browser cannot open tg:// ([`b2a8578`](https://github.com/maxcodl/PaperPlay/commit/b2a85787ae98a8687e1f7a51aa11bb8e1e8998de))
- Desktop: the Duplicates page is a lazy list and gets no scrolling column, which crashed with an infinite height ([`57cc3cc`](https://github.com/maxcodl/PaperPlay/commit/57cc3ccde79270cd1efe82a30eb56794f01d2441))

**Full changes:** [v1.8.0...v1.8.1](https://github.com/maxcodl/PaperPlay/compare/v1.8.0...v1.8.1)

## [1.8.0] - 2026-11-16

### Added
- Auto filing switch, file now button and after-scan trigger ([`b249e15`](https://github.com/maxcodl/PaperPlay/commit/b249e156aaba7639a8ef6a8dd5226d19e641b317))
- Auto filer copies or downloads and uploads songs into main chat topics ([`05f26c7`](https://github.com/maxcodl/PaperPlay/commit/05f26c73471a1a679fa44e1bb40a58539f3c3633))
- Device addresses in a dropdown ([`ed33c67`](https://github.com/maxcodl/PaperPlay/commit/ed33c67787a2018ca8cc2bd469b1710877e8efd0))
- Log app lifecycle, system, network, screen and setting events ([`a4f810b`](https://github.com/maxcodl/PaperPlay/commit/a4f810ba0f8a37e882246c9425bd6073b83217de))
- Setting for how many messages each reading request asks about ([`3f0f141`](https://github.com/maxcodl/PaperPlay/commit/3f0f141482f4218195423afe6634e51d22e352db))
- Read the cover inside the start of the audio file when the post has no thumbnail ([`b0817e2`](https://github.com/maxcodl/PaperPlay/commit/b0817e25334bf674b3850f336ac685e7aeaf6867))
- Song info also asks LRCLIB, takes the closest title for songs with no artist, and logs each lookup ([`fcad995`](https://github.com/maxcodl/PaperPlay/commit/fcad99504ad5e3b5b285dfdb2731f24b2747999b))
- Scan removes songs whose post your account no longer sees and reports skipped posts and small songs ([`ac0e853`](https://github.com/maxcodl/PaperPlay/commit/ac0e85353d4f857478bc535c50cf2fb5bd6ee4fb))
- Search lists a matching artist first, above the songs ([`cf0e4d9`](https://github.com/maxcodl/PaperPlay/commit/cf0e4d913a516683ee3c248b0632357902952d05))
- Tapping a song on Song info left plays it ([`e156ed6`](https://github.com/maxcodl/PaperPlay/commit/e156ed679e84705e76fa6f27f320bc19a9a02f9c))
- Search ignores accents, forgives typos, matches inside words and ranks best match first ([`80a2934`](https://github.com/maxcodl/PaperPlay/commit/80a29342d7e23696b71d11908dc84f54bcf44224))
- Scan page steps also go to the activity log ([`e123e7c`](https://github.com/maxcodl/PaperPlay/commit/e123e7cfda7933cc918f4b3c9efc327357edea51))
- One Scan library page with a switch for each bot and the account; replaces repair and full rescan ([`7ebf215`](https://github.com/maxcodl/PaperPlay/commit/7ebf215691c8255708adc04a8c4c37bb0978bfba))
- Standby lyrics animation style, restore single lyric line widget ([`8cb8b2b`](https://github.com/maxcodl/PaperPlay/commit/8cb8b2bb4f5eec3e99e0df23dc32b4bd8693ebff))
- Standby alert icons, scrolling lyrics, two-tap exit, cover and accent colours ([`7d8f161`](https://github.com/maxcodl/PaperPlay/commit/7d8f16173e0fe8f72cd90f5ffd7b2f85fc20001d))
- Charging and notification icon support for standby, window bar hiding ([`a9514d8`](https://github.com/maxcodl/PaperPlay/commit/a9514d80d7c8f2fad9733cdebf8a5e0a6a8ffacf))
- Standby visualizer follows the audio, seven new styles including ASCII ones ([`2261d21`](https://github.com/maxcodl/PaperPlay/commit/2261d21d58c520b9730e8500ca615321d99ce276))
- Live audio spectrum from the player for visuals ([`95cb586`](https://github.com/maxcodl/PaperPlay/commit/95cb58697f0d4fe6320fc891e804f866d8f08053))

### Fixed
- Update repository references from PaperPlay-compose to PaperPlay in various files ([`2936ebc`](https://github.com/maxcodl/PaperPlay/commit/2936ebcfbb0c346e9d8f667b8616276109a2533d))
- Inline song rows key by copy ([`d7ce71d`](https://github.com/maxcodl/PaperPlay/commit/d7ce71d36f320f6bd048ba2271a53ba6d4756de6))
- Top artists come from all results ([`fc0bffd`](https://github.com/maxcodl/PaperPlay/commit/fc0bffd90efbedde47798231e894673bfb40b0ac))
- Log share is file only and creates its folder ([`f6d043f`](https://github.com/maxcodl/PaperPlay/commit/f6d043f1432cbda623c3e551f3dae72830240f95))
- Play log sink drains held lines before going live ([`bcac654`](https://github.com/maxcodl/PaperPlay/commit/bcac65463b4e5e57cafde4d32767cd71da6d605c))
- Search ranks relevance first, wider typo prefixes, folds combining accents ([`7ee3632`](https://github.com/maxcodl/PaperPlay/commit/7ee3632efb331644b822e9ed092d2b6026622f5a))
- Auto filer retries after an hour, checks main chat, confirms delete ([`23fba31`](https://github.com/maxcodl/PaperPlay/commit/23fba31aa8aab75154c57414b94dfefb16a3c93a))
- Only the owner's account confirms a post is gone; account failure falls back to the bot ([`ce7524b`](https://github.com/maxcodl/PaperPlay/commit/ce7524b4cb8b58071f9ed790a447de561dce3a51))
- Empty read of a cached file refreshes its path ([`041c83b`](https://github.com/maxcodl/PaperPlay/commit/041c83b760975277ee6171c968e47cd2bf9ebda4))
- Rescan moves on to the next reader when a lookup is refused ([`814a6b5`](https://github.com/maxcodl/PaperPlay/commit/814a6b5b8f0d0bc8e289c2a17d005d70543cf927))
- Skip from pause counts as playing for the download window ([`3405e16`](https://github.com/maxcodl/PaperPlay/commit/3405e16680339fb8fda809bba46762c737cd498e))
- Log export and rotation share a lock, copy a fixed snapshot ([`8c02f69`](https://github.com/maxcodl/PaperPlay/commit/8c02f69f5cb87c805b9b5d6b3f3d056f7378441e))
- Covers retry unanswered songs, keep chosen covers, skip streaming files ([`5a7cdb6`](https://github.com/maxcodl/PaperPlay/commit/5a7cdb6304b08e490388331e2cc513e84f2baf28))
- Account reads fail on stalled paging and deletes stay unresolved when unchecked ([`7ae73ba`](https://github.com/maxcodl/PaperPlay/commit/7ae73ba4e20f2009895f031221d2044539aeba8f))
- Account read keeps no stale snapshot and prunes only what it covered ([`c86a083`](https://github.com/maxcodl/PaperPlay/commit/c86a08365356f8d189c4a7d36275acf3f2a239a2))
- Network log line formats the supplied capabilities ([`753b107`](https://github.com/maxcodl/PaperPlay/commit/753b10733691b790d512bef07b2c8fe03f4ee046))
- Auto filer passes the duration as an Int ([`54e5033`](https://github.com/maxcodl/PaperPlay/commit/54e503388a1429db16f011f8deeba5225954e4c9))
- Cover search skips the background rate-limit queue and times out after 8 s ([`88e72d2`](https://github.com/maxcodl/PaperPlay/commit/88e72d267515a4eedac45b6cfc0b5021c47286b6))
- Enter in the cover and tag search boxes runs the search ([`6cd28f0`](https://github.com/maxcodl/PaperPlay/commit/6cd28f07b254723304e858ea31a971805d30eeb1))
- Share and save the log as a streamed file, not one big string ([`6475c00`](https://github.com/maxcodl/PaperPlay/commit/6475c00f4ad2254ca7e9164ea62d7484c55b7c52))
- Restoring the saved index on the account page runs off the main thread ([`0cca480`](https://github.com/maxcodl/PaperPlay/commit/0cca4809fc35640bf9925f6d737ca538aca1de2b))
- Library reads stay stable while posts are deleted; Delete button follows the mini player ([`f1ced93`](https://github.com/maxcodl/PaperPlay/commit/f1ced93703d006f8d90910c517952b50e66f30f6))
- Account reads the chat only after loading it, so a new session no longer says chat not found ([`fb5b18a`](https://github.com/maxcodl/PaperPlay/commit/fb5b18a9a58ee8210ad6091cb5d1307d4cbe1a99))
- Account delete checks what is really gone and logs Telegram's reason ([`6f9cbfd`](https://github.com/maxcodl/PaperPlay/commit/6f9cbfd60b9adf6b905ba095cf8d8d13a8c1e56b))
- Song info tries title and artist swapped when nothing matched ([`9e174fc`](https://github.com/maxcodl/PaperPlay/commit/9e174fc92e62badaef7bbcbd20279512ca7516a1))
- Covers give up for good only when Telegram answered, log why a cover is missing, account knows every library chat ([`0006641`](https://github.com/maxcodl/PaperPlay/commit/00066414dc510c3937e4bd170c80ab92b3ac1005))
- Song info and covers are not asked again by themselves; a scan from the scan page asks for everything again ([`7a3e7c9`](https://github.com/maxcodl/PaperPlay/commit/7a3e7c9e8cfd19ac42c4eb4305121d062ec6284c))
- No automatic full rescan at start; only posts newer than the last read ([`d2ff80b`](https://github.com/maxcodl/PaperPlay/commit/d2ff80b564e1b4f314a04d76984bf451eb0ee50c))
- Covers also read Telegram thumbnails through your account, and songs given up on are tried again once ([`d7cfb32`](https://github.com/maxcodl/PaperPlay/commit/d7cfb329dfa74d2d198d377f9fdbc27b30c5a0af))
- Repeat posts kept as copies are not counted as missing; scan log shows how many ([`5abcbbd`](https://github.com/maxcodl/PaperPlay/commit/5abcbbd75031c951179e120d15fac953d276f8c9))
- Posts missing from the library are found by message id, not by matching title and artist ([`56f2428`](https://github.com/maxcodl/PaperPlay/commit/56f24286544c659c359ecb37cf78508cca552400))
- Account scan adds every main chat post the library has no row for, and logs how many it saw ([`d1c35eb`](https://github.com/maxcodl/PaperPlay/commit/d1c35ebc7b01f79dcf0cd1ad9b0394844757b63e))
- Full rescan shows and paces the check of saved songs, in batches of 100 ([`f81b29e`](https://github.com/maxcodl/PaperPlay/commit/f81b29eeee1f00e7c062e133d12307b28e0be172))
- Start the volume watcher after playbackMeta exists, it crashed on start ([`484e5d5`](https://github.com/maxcodl/PaperPlay/commit/484e5d55cd10c4680def7237d5ff63118b7f9ba5))
- Next and previous start playing when paused ([`b566ae0`](https://github.com/maxcodl/PaperPlay/commit/b566ae010ded344402ce71978c3db59c2393da40))
- Full rescan also reads with extra bots and the account; account on by default in repair ([`fb15345`](https://github.com/maxcodl/PaperPlay/commit/fb1534544c9ae35684a4df294373daedc573935f))
- Status toast stays dismissed during a scan and slides out fully ([`541125f`](https://github.com/maxcodl/PaperPlay/commit/541125f4e199588841e072faf98a5fe58698ed3f))

### Improved
- Bound memory caches, fix lifecycle leaks, and defer engine startup ([`fd71732`](https://github.com/maxcodl/PaperPlay/commit/fd7173253bbf9de3ce4024921619480c7b87e97b))

### Documentation
- Checklist for the event log, log sharing and standby lyrics ([`c2d43d2`](https://github.com/maxcodl/PaperPlay/commit/c2d43d262d9df4c72a04eb8b3f15be4e308bb8f0))
- AGENTS.md with rules for contributors and agents ([`e1f3b64`](https://github.com/maxcodl/PaperPlay/commit/e1f3b648d97d02efad5a35d70f066c53105499a8))
- Checklist for the scan page, account reading, duplicates and rate limits ([`bc847a4`](https://github.com/maxcodl/PaperPlay/commit/bc847a43e062b112e26e940851c25d974e61210c))
- Checklist for the logging, crash records and memory line ([`61068b4`](https://github.com/maxcodl/PaperPlay/commit/61068b4f9e03d29299ec1baa72c5c782cfa7e945))
- Checklist for the standby lyrics, colours and exit changes ([`4d9a536`](https://github.com/maxcodl/PaperPlay/commit/4d9a536a8caecebc2e61db444a2980d6bf6c8e0e))
- Checklist for the standby visualizer and alert icons ([`24df364`](https://github.com/maxcodl/PaperPlay/commit/24df364f85911a7a7f0028a9a3ebd33852ae05e1))

### Other changes
- Checklist: rewind, packaging, repository ([`30bc86d`](https://github.com/maxcodl/PaperPlay/commit/30bc86dac61dbccac68a608098879cd6b7a266f2))
- Agents: touch blocking, artists, covers, merge rules, rewind and process rules ([`c67617a`](https://github.com/maxcodl/PaperPlay/commit/c67617a017b1bba6a8f3afaaa1b1959d394d9ff5))
- Readme: rewind, lyrics choice, compilation covers ([`08e8a74`](https://github.com/maxcodl/PaperPlay/commit/08e8a747cafa32d032653360ca71c867fa9b62f7))
- Desktop package: include java.management so the memory line works ([`8db322d`](https://github.com/maxcodl/PaperPlay/commit/8db322d4b85f2a3a3ec88f6702a0df19a64a4d40))
- Rewind: one press plays the song again from the start, a second press within 2.5 s goes back, and the first song only restarts ([`ad943f2`](https://github.com/maxcodl/PaperPlay/commit/ad943f2daf8883f6cf7a03b7763d35e5e78fae1f))
- Covers: a compilation is never the cover or the first album match, and a button resets songs that got one ([`f0e2e46`](https://github.com/maxcodl/PaperPlay/commit/f0e2e46076aae35d20e8c7041c199bbea0702ed5))
- Player and bars take the touches that land on them, so nothing behind is pressed ([`f6258e1`](https://github.com/maxcodl/PaperPlay/commit/f6258e13b598cf60c75f0c945ae42bdca1e9a140))
- Checklist: audio, lyrics and covers ([`70deac6`](https://github.com/maxcodl/PaperPlay/commit/70deac603c4a8ca22f563f25842a41b9e28d843b))
- Lyrics: pick another version (original, romanized, translation, other records) for a song ([`640f729`](https://github.com/maxcodl/PaperPlay/commit/640f7297fbd7fbd57e2d89f8bfc83783002f7dc0))
- Sync: lyrics converge, a text chosen by hand wins, the choice and its time travel ([`5e818ca`](https://github.com/maxcodl/PaperPlay/commit/5e818caa59cf398b6efad64b9d72f402659f6ac6))
- Sync: devices pick the same album tags and cover when each found its own ([`99a29b6`](https://github.com/maxcodl/PaperPlay/commit/99a29b6e889e879e11bb9d2659c1c178129d9088))
- Audio: float output so 24-bit files are not cut to 16 bits without dither, and log the output format ([`e19c680`](https://github.com/maxcodl/PaperPlay/commit/e19c680b9d94112f2eea8f698f34b897b7a9356a))
- Checklist: status at the end of 16 Nov ([`7e8b24a`](https://github.com/maxcodl/PaperPlay/commit/7e8b24acbd9c971a6ce933b7c7974088aea8a6ae))
- Agents: git status before builds, release script, known failing test ([`32dbc26`](https://github.com/maxcodl/PaperPlay/commit/32dbc26579562edaa1b9115f7e5398d2c3d794fc))
- Readme: scan page, topic lists, shared player and sync, tappable artists, Spotify account list ([`672bcf2`](https://github.com/maxcodl/PaperPlay/commit/672bcf27dd97903f2aa6420a17885ad6ecb7f2bd))
- Player and song menu: tappable artists and album, go to artist or album ([`1864dbc`](https://github.com/maxcodl/PaperPlay/commit/1864dbcb91b7c9b2dfa4e855f1f300a5cf7ff466))
- Artists: a credit splits into artists, each opens its own page, and so does the album ([`a3a45fc`](https://github.com/maxcodl/PaperPlay/commit/a3a45fc247da3408381229a0a32f7426dd088f95))
- Checklist: PC parity ([`f131b6f`](https://github.com/maxcodl/PaperPlay/commit/f131b6f64aca9f80ba973a65bb50ad6b954815ae))
- Desktop: right click menu on songs, Alt+Left back, sharing to clipboard and Explorer, Spotify import in the sidebar, keep-on-device pin on every list ([`068c496`](https://github.com/maxcodl/PaperPlay/commit/068c4964b7227d9408ff3854b31e064772946934))
- Spotify: lists the account's own playlists for one-tap import ([`4319bec`](https://github.com/maxcodl/PaperPlay/commit/4319bec7fd0a91696e75f413d4fc921b74b081af))
- Sync: a Spotify playlist keeps its link and waiting list on every device ([`bd26de4`](https://github.com/maxcodl/PaperPlay/commit/bd26de4431029d84dffb9101f6420c482baecfd6))
- Sync: a brake on mass removal, removals are logged, a song posted again leaves the deletion record, and a clear button ([`d6873cc`](https://github.com/maxcodl/PaperPlay/commit/d6873cc271ba47fbccbd412f7aa9edf92375c278))
- Checklist: devices, sync and standby ([`8b3c5a2`](https://github.com/maxcodl/PaperPlay/commit/8b3c5a25206efe5e8e788787d1bcf33c7754c4c6))
- Standby: faded lyric list, plain lyrics, no-lyrics notice, grayscale cover ([`6a4e00c`](https://github.com/maxcodl/PaperPlay/commit/6a4e00cdc88e7891cc3d8302762dfb1ff9f08786))
- Devices: state with the sound output, stale states ignored, source switch waits for the other device ([`4d853a1`](https://github.com/maxcodl/PaperPlay/commit/4d853a132fab013a5cd285d23a754f049f71c2ae))
- Desktop: the real Windows sound output name and type ([`edea23f`](https://github.com/maxcodl/PaperPlay/commit/edea23f423b17fcd0c8c2f8936bd1d0340288abe))
- Sync: songs deleted on one device are removed on the other, the record travels on ([`cf5ff54`](https://github.com/maxcodl/PaperPlay/commit/cf5ff54e4ee23c379e8308c06149a58d4abca49c))
- Agents: ui rules ([`11ba1c6`](https://github.com/maxcodl/PaperPlay/commit/11ba1c6c9c66108cb06a39c995d5a53f8c193610))
- Checklist: sidebar tools row ([`5c39ee6`](https://github.com/maxcodl/PaperPlay/commit/5c39ee6e007e6c5f8e139522238575e6efaec1a7))
- Desktop sidebar: Tools is a row with an icon and fold arrow, Settings set apart by a divider ([`086f578`](https://github.com/maxcodl/PaperPlay/commit/086f5781b72ec2eb4a6f02e29bb79d9f7e7a798e))
- Player: says which device the music is playing on ([`2c3cc8c`](https://github.com/maxcodl/PaperPlay/commit/2c3cc8ca4e115dc83b21fb506cbbe0059ffe8853))
- Desktop db: wait up to 30 s for a locked database and use WAL, so concurrent writes do not close the app ([`87965cc`](https://github.com/maxcodl/PaperPlay/commit/87965cc840db9ec07cc280376316a5e3d9a0f24f))
- Sync: song info, lyrics and covers fill in between linked devices; hand-made data wins over automatic ([`3124d43`](https://github.com/maxcodl/PaperPlay/commit/3124d4331404b34a04079585a5f76de68905d16f))
- Player: shows and controls the other device's song, a one-tap source switch, and a strip when another device plays ([`7e5df3a`](https://github.com/maxcodl/PaperPlay/commit/7e5df3a2b0f15207caa0bf383c629ae7ee2f45b5))
- Devices: seek and play-this-song controls, position in the playing state, and a switchable active output ([`03cd7f8`](https://github.com/maxcodl/PaperPlay/commit/03cd7f889b6785a5d4e1c7ba36efbd8993bab75c))
- Scan page: the account counter says songs are added when the read ends ([`d8f597a`](https://github.com/maxcodl/PaperPlay/commit/d8f597a10e94d6cbe1b81c0d4a3ac806cf7303f8))
- Desktop sidebar: Tools is a drop-down with the everyday tools ([`b28dc0f`](https://github.com/maxcodl/PaperPlay/commit/b28dc0faab5e95175e1389bbdff4811cb8ef3f3f))
- Telegram topic lists on Home and in the desktop sidebar, apart from the playlists ([`d7fd42b`](https://github.com/maxcodl/PaperPlay/commit/d7fd42b5422730e1fec61eaabdb67ed7a29a711d))
- Settings: explanations sit behind an info button next to each title ([`d7dc92f`](https://github.com/maxcodl/PaperPlay/commit/d7dc92f35cb1ed02c716df0f7edaefe5aa08d705))
- Library: the Telegram topic lists under Playlists refresh with the library and include topics Telegram never named ([`114c6dc`](https://github.com/maxcodl/PaperPlay/commit/114c6dc562a2c44633a1e1d19ec03805e6993b7a))
- Delete: steps go to the Activity log and the playback log ([`d63185b`](https://github.com/maxcodl/PaperPlay/commit/d63185b53a47fb3620e3f00704169e68bfbbdd13))
- Duplicates: show a running delete's progress while the page loads, and do not check for gone posts during a delete ([`907e9b9`](https://github.com/maxcodl/PaperPlay/commit/907e9b9e22f559cb50cca9099607c1e255e7d792))
- Delete: your account deletes first, in calls of 100, so progress shows and old posts are not refused one by one ([`cd14eb9`](https://github.com/maxcodl/PaperPlay/commit/cd14eb932a95ffff255cba8e66e272540df66f81))
- Log which code removes a song from the library ([`3a485ab`](https://github.com/maxcodl/PaperPlay/commit/3a485ab92b5db94be4f709a4c5b1b5217f6fdcd2))
- Delete: a post to keep or a listed post counts as gone only when your account no longer sees it ([`69fea0f`](https://github.com/maxcodl/PaperPlay/commit/69fea0f6efa072d431b40c3a9351932e6876c10f))
- Duplicates: a lower quality copy is always suggested for deletion, in any topic ([`8c65165`](https://github.com/maxcodl/PaperPlay/commit/8c65165f877d577a4489a2b067ddbf4bcf37d4fa))
- Stream: when TDLib moves a finished download out of temp, ask it for the new path instead of stopping the song ([`f25e1a7`](https://github.com/maxcodl/PaperPlay/commit/f25e1a70fa27e341e69adb7e376160af0c334292))
- Delete: a post the bot reports as not found only counts as gone when your account no longer sees it ([`83215c0`](https://github.com/maxcodl/PaperPlay/commit/83215c043a31088c4fba933e2630a21d79f91a02))
- Account scan fetches the names of the main chat's topics, so repeats are not listed under 'Unnamed topic' ([`d009d44`](https://github.com/maxcodl/PaperPlay/commit/d009d449f6f6a2f9b8b345075985144148dffc71))
- Automatic reads are held to 50 requests a minute ([`951f328`](https://github.com/maxcodl/PaperPlay/commit/951f32854db567dc58494016ca1189dab7ebfe72))
- Only a scan the owner starts uses the messages-per-request setting; automatic reads use 100 ([`16cd7c8`](https://github.com/maxcodl/PaperPlay/commit/16cd7c8983ca308aea2e209969bb4f7cb5a59f28))
- Reading: after two waits in a row with a big request the size goes back to 100 and the read stops ([`45bae3a`](https://github.com/maxcodl/PaperPlay/commit/45bae3a54ab5fd6ba68bf5e5e1d48b9def64fe53))
- Duplicates: never mark every post of a group, even when another group also ticks one of them ([`04f679b`](https://github.com/maxcodl/PaperPlay/commit/04f679be0a871778d47f0be4b1c709d0ccfd3d50))
- Scan page counts posts while the account reads, since repeats are included ([`c66c990`](https://github.com/maxcodl/PaperPlay/commit/c66c990fb47bd18c80e915a6a223fa4ddd5e8d27))
- Account read shows the real count while reading, not an estimate that ran ahead and dropped back ([`f474e59`](https://github.com/maxcodl/PaperPlay/commit/f474e5901529a2e99a06617b89e80ec1648f37fd))
- Account read keeps going past an empty page and logs Telegram's own post count ([`30e75b4`](https://github.com/maxcodl/PaperPlay/commit/30e75b4d6deb1106fbead8cdacf1058fbb40d367))
- Up to 500 ids per reading request, put back to 100 if Telegram refuses; scan notes come from the full read ([`8e8f668`](https://github.com/maxcodl/PaperPlay/commit/8e8f668ae6c5ffc7bc5dadd6f9bc110f8238ac84))
- Log how many posts each account search returned ([`4736ee5`](https://github.com/maxcodl/PaperPlay/commit/4736ee5881e63208ad2b3c706513f6f700000d7d))
- Ui: Home placeholders cross-fade into the page instead of going blank first ([`018019e`](https://github.com/maxcodl/PaperPlay/commit/018019e48f28b46b2403cb9f199cd74820b1b8b8))
- Ui: Telegram page shows account state and no speed dials, account copy speed moves to the account page, logout tells what happened, up to 200 messages per request ([`fe9db5d`](https://github.com/maxcodl/PaperPlay/commit/fe9db5dc43f67cbbadd0266aef29d603d1e46698))
- Ui: Songs list also shows every repeat post of a file as its own row ([`6191e14`](https://github.com/maxcodl/PaperPlay/commit/6191e14a0e16c40ce98e0f58060369975cbf1fa4))
- Ui: Delete button on Duplicates sits just above the bottom bar ([`e236fb6`](https://github.com/maxcodl/PaperPlay/commit/e236fb6eeb3eebe1c5a9204a57d6b46063bab90e))
- Ui: song rows always show format, bitrate and size, format first and size on the right ([`10cbcb0`](https://github.com/maxcodl/PaperPlay/commit/10cbcb028db40eff5214c5d2ebd578607652a7f5))
- Ui: Home shows all placeholders first and fades in once ready; placeholders shimmer, covers too ([`916b9ff`](https://github.com/maxcodl/PaperPlay/commit/916b9ffdc9a8929c17eb6fe26be6494738968cb0))
- Ui: merge Devices and Storage on Profile ([`046041b`](https://github.com/maxcodl/PaperPlay/commit/046041b0b1678fa61ab8c60b3451033c301458e7))
- Ui: shorter Library list on Profile ([`fe931da`](https://github.com/maxcodl/PaperPlay/commit/fe931da504f62587aab825c829f42a2b9bf4e19f))

**Full changes:** [v1.7.0...v1.8.0](https://github.com/maxcodl/PaperPlay/compare/v1.7.0...v1.8.0)

## [1.7.0] - 2026-11-14

### Added

- Record crashes, how the last run ended, and the system log ([`c2cf455`](https://github.com/maxcodl/PaperPlay/commit/c2cf455c5855404b6ce20add2b6e6f78d0aaff95))
- Share the full log as a file or text from the Log page ([`01bf96a`](https://github.com/maxcodl/PaperPlay/commit/01bf96abcc58baa1882e36a85cd5c27e3d8e0d45))
- Memory line every ten seconds in the log ([`fdea970`](https://github.com/maxcodl/PaperPlay/commit/fdea970f493a33a9f5e41442c3042ea0a95a4ae0))
- Playback log kept on disk with every player, stream and file event ([`5e97c42`](https://github.com/maxcodl/PaperPlay/commit/5e97c42508f42dc926854f9b00aab73f4b79cbf6))

**Full changes:** [v1.6.0...v1.7.0](https://github.com/maxcodl/PaperPlay/compare/v1.6.0...v1.7.0)

## [1.6.0] - 2026-11-14

### Added

- Scan banner shows the bot's name ([`de64d8a`](https://github.com/maxcodl/PaperPlay/commit/de64d8a243fe4c4dbce0d530915ee7cf5f83be35))
- Indexing count goes up smoothly ([`95d1434`](https://github.com/maxcodl/PaperPlay/commit/95d143414fdafa2781c8109a9c47c6ed3513a5c1))

### Documentation

- Checklist for indexing count and bot names ([`3877980`](https://github.com/maxcodl/PaperPlay/commit/38779808794ff936470ad5204c4a7e195945e209))

**Full changes:** [v1.5.0...v1.6.0](https://github.com/maxcodl/PaperPlay/compare/v1.5.0...v1.6.0)

## [1.5.0] - 2026-11-14

### Added

- Smooth sync counter, full scan shows which bot or account is reading ([`427f18b`](https://github.com/maxcodl/PaperPlay/commit/427f18b24bbfc69fcb5cac50b6990aa4c73b3193))
- Duplicates delete counts one by one, page updates live and scrolls to top ([`3c5c03f`](https://github.com/maxcodl/PaperPlay/commit/3c5c03fb9a02f3495151fe2b231f346834321997))

### Documentation

- Checklist for the last round ([`26768dd`](https://github.com/maxcodl/PaperPlay/commit/26768ddf43350b869109d465b3e78890fd5e3eee))

**Full changes:** [v1.4.0...v1.5.0](https://github.com/maxcodl/PaperPlay/compare/v1.4.0...v1.5.0)

## [1.4.0] - 2026-11-14

### Added

- Library settings in drop-downs, read with other bots after a rescan ([`80ca842`](https://github.com/maxcodl/PaperPlay/commit/80ca842f2d7e78b59a7aa5d80126a33d7ccf4148))
- Song info left page redesign, tasks open the live list ([`d49ccc7`](https://github.com/maxcodl/PaperPlay/commit/d49ccc7866b848fd1362a8350273c1dd14b99160))
- Faster song info, Deezer first ([`e8ce2df`](https://github.com/maxcodl/PaperPlay/commit/e8ce2df67af12f3959815d036852880943d98276))
- Reading speed adjusts itself ([`977f251`](https://github.com/maxcodl/PaperPlay/commit/977f251e00e52f5084649b9c389ac93a9da115e5))
- Search songs by language ([`2bbe168`](https://github.com/maxcodl/PaperPlay/commit/2bbe168e93d30b830aab7da785a3e932cba65b1e))
- Duplicates page with a floating delete, auto-ticked extras, topic names and a lazy list ([`fbb4fdb`](https://github.com/maxcodl/PaperPlay/commit/fbb4fdb9e735fafeecf9f7fe7b91b0c7e668c304))
- Spotify playlists list missing songs greyed out, with copy links, and lists remember their sort ([`d14810f`](https://github.com/maxcodl/PaperPlay/commit/d14810f0566d8ff9135bbe2870d1e2caac41897a))
- Home skeletons, steadier covers, swipeable tiles, coloured toasts and a mini player that swipes away ([`f1988d3`](https://github.com/maxcodl/PaperPlay/commit/f1988d3c8c1fe196845d1f1d31119a3d0afb2f70))
- Upload a music folder in place, ask before subfolders, skip songs under 2 MB ([`c4443aa`](https://github.com/maxcodl/PaperPlay/commit/c4443aae0c864b8f0a942cd7d469dd94263d5006))
- Progress for missing and low-quality covers in the tasks list and the notification ([`d8b4613`](https://github.com/maxcodl/PaperPlay/commit/d8b46138c7820eed428410d23de421190b01fc7d))
- Read a song's length from its file header ([`edce335`](https://github.com/maxcodl/PaperPlay/commit/edce335d5e21b5b1cca795a69db79c113afe9fe3))
- Show an update dialog on every launch when a newer release exists ([`0656b1c`](https://github.com/maxcodl/PaperPlay/commit/0656b1c34ceb68a9702f3df83113279bf002be80))
- The in-app logo follows the app theme ([`84afc56`](https://github.com/maxcodl/PaperPlay/commit/84afc56dfc1b917f9657d4a13fd618db5f76d7f9))

### Fixed

- Duplicate delete goes through the Bot API per post ([`a830641`](https://github.com/maxcodl/PaperPlay/commit/a8306414c099e689217ba33db47e8bb45c799a06))
- Windows release bundles a Java 25 runtime so the installer starts ([`5273468`](https://github.com/maxcodl/PaperPlay/commit/5273468b3fdbf33f2069a1cc6a1cf004162f200c))
- Onboarding shows the chosen photo and no false Not ready yet on the bot page ([`0ed267e`](https://github.com/maxcodl/PaperPlay/commit/0ed267ea7b77791e7b3b9ad68184ebad5965b075))
- Uploads use the request shape of the loaded TDLib, run in parallel and send the song length ([`48bf3ad`](https://github.com/maxcodl/PaperPlay/commit/48bf3ad0500c78b66a4da61e8f90ce7489798d8d))
- The Windows release includes TDLib so it can reach Telegram ([`734f350`](https://github.com/maxcodl/PaperPlay/commit/734f3500d0f8dafe5108fe94aeb88d3c11e63dab))
- CI ProGuard reads the JDK 17 classes because the Gradle JDK 25 has no jmods ([`7abe514`](https://github.com/maxcodl/PaperPlay/commit/7abe5148acd33e9f25ad6dbdd090e62a74baec38))
- Setup tells the user to remove an older per-machine install ([`27e0c98`](https://github.com/maxcodl/PaperPlay/commit/27e0c9861fea9d9695bd7fcfd359295429b48362))
- GitHub Windows release uses the same minified build and Inno installer as the release script ([`501cd20`](https://github.com/maxcodl/PaperPlay/commit/501cd20349d674146ff23ea8f624763df8aa8650))
- Package Compose resources on Android so the settings logo no longer crashes ([`cfba237`](https://github.com/maxcodl/PaperPlay/commit/cfba237764ef0308f8aa134c0a3eb4ba18acda74))

### Documentation

- Checklist and readme ([`222d1e9`](https://github.com/maxcodl/PaperPlay/commit/222d1e9b7faaa16e726b4b47599fb6e88de77227))
- Checklist for this round of work ([`163609f`](https://github.com/maxcodl/PaperPlay/commit/163609f461c44a41389e265e1b20270f64ffd95a))
- TDLib is in the repository, CI fixes in the checklist ([`3b2d01e`](https://github.com/maxcodl/PaperPlay/commit/3b2d01eb2b55962758383dad291067ff26278100))
- The update check runs at every launch and shows a dialog ([`ed49815`](https://github.com/maxcodl/PaperPlay/commit/ed49815a2b6b0d395da7a234bdb9640bba8ac9d4))
- Checklist for the settings crash, themed logo and one Windows installer ([`cd3c93e`](https://github.com/maxcodl/PaperPlay/commit/cd3c93e08d2a32d0a02502da9e8460b030307e1c))
- Update the README and the checklist for releases, the updater, About, logout and the merge-back rule ([`cc1ff44`](https://github.com/maxcodl/PaperPlay/commit/cc1ff44979c3a0acc12797453346eab4110ebd94))

**Full changes:** [v1.3.1...v1.4.0](https://github.com/maxcodl/PaperPlay/compare/v1.3.1...v1.4.0)

## [1.3.1] - 2026-11-14

### Fixed

- The in-app logo uses the launcher icon's own plane image instead of a redrawn badge ([`2a81d2c`](https://github.com/maxcodl/PaperPlay/commit/2a81d2c27e2d9ce24579a0c5a4e05413a16fd68f))

**Full changes:** [v1.3.0...v1.3.1](https://github.com/maxcodl/PaperPlay/compare/v1.3.0...v1.3.1)

## [1.3.0] - 2026-11-14

### Added

- Compact About page with drop-down rows for the update channel and installation method ([`111eced`](https://github.com/maxcodl/PaperPlay/commit/111eced1349e77c0374146f8b4b7492336f28acd))
- Real app icon with a plane-hits-the-note animation, animated tour background, About card on Profile ([`32e150b`](https://github.com/maxcodl/PaperPlay/commit/32e150b76cf79e8bc8b51b38bbbbed229596f246))
- Settings grouped under headings with About last; About shows the installation method with Shizuku below ([`d3a9a6b`](https://github.com/maxcodl/PaperPlay/commit/d3a9a6b58c6ded7f81620da2ed3d67f40fa74917))
- Log out options (everything, bot only, account only) and log out with delete all data ([`e84d831`](https://github.com/maxcodl/PaperPlay/commit/e84d8317d34bb094af254f22bd31d46f6e433281))
- Optional Shizuku installer for updates, with permission prompts and toasts ([`84b797d`](https://github.com/maxcodl/PaperPlay/commit/84b797d6e66243b5b935b6c8bd8188d91969604f))
- Install updates on Windows by running the downloaded setup; Linux opens the release page ([`fdb136d`](https://github.com/maxcodl/PaperPlay/commit/fdb136d6158e9e4ff875ea16c7d35a9c1637e3f7))
- Open the install permission screen at once and continue the update when it is allowed ([`e6837b8`](https://github.com/maxcodl/PaperPlay/commit/e6837b891d1ce91bf880132493d3daea3c21eec6))
- Install updates on Android through the package installer, with a permission check ([`bd80e0d`](https://github.com/maxcodl/PaperPlay/commit/bd80e0da076083bdccd3f7fac62176136950cde5))
- About page with update check, update choices and the Made by Max footer ([`69cc5ec`](https://github.com/maxcodl/PaperPlay/commit/69cc5ecf38abbdd21ab2932d0eb7a01bd18304c0))
- Update logic - versions, the GitHub release feed and an update service ([`aede1b0`](https://github.com/maxcodl/PaperPlay/commit/aede1b090413594bc5bd351290aba51b436bb28b))
- Automatic versions, releases and change logs from the commit subjects ([`229efc2`](https://github.com/maxcodl/PaperPlay/commit/229efc24495dba09e543763fd8cf59a93a77f575))

### Fixed

- Import the state delegates the drop-down row needs ([`6d941db`](https://github.com/maxcodl/PaperPlay/commit/6d941db36f74535aea572e27213399b1861f57f6))
- A running Shizuku counts as installed even when the package lookup is hidden ([`928410c`](https://github.com/maxcodl/PaperPlay/commit/928410c84ffb5ca6cea21da9d5523aedc39c2c8c))
- Pass the update service to the Profile screen after the positional arguments ([`ee9b88e`](https://github.com/maxcodl/PaperPlay/commit/ee9b88e703cd75b449c843171eb0de022ae6f888))
- A mention of BREAKING CHANGE in prose no longer counts as a major bump ([`8e1a546`](https://github.com/maxcodl/PaperPlay/commit/8e1a546dbc63732be138eef72115431ee0f57433))

### Documentation

- Checklist entries for logout options, grouped Settings, About and the new logo ([`262f8a1`](https://github.com/maxcodl/PaperPlay/commit/262f8a1faa02a6d366f6398b36167fd8d1554cbf))
- Describe in-app updates and update the checklist ([`bf102a3`](https://github.com/maxcodl/PaperPlay/commit/bf102a3c35826abcb674646858ab3cebf3fe7fd9))
- Rewrite the README, update the checklist ([`4fae430`](https://github.com/maxcodl/PaperPlay/commit/4fae4306dd39237efbf8bc72e43c3370d4953ac8))
- Motion work, CI state, open items ([`5656ac2`](https://github.com/maxcodl/PaperPlay/commit/5656ac287c8e1b0d291ad9804211ef3eaefe2317))

### Other changes

- Smoother UI: press feedback, title and cover crossfades, gliding grids, tabs keep their scroll ([`e650ade`](https://github.com/maxcodl/PaperPlay/commit/e650ade4c3a284dacdcdb84e8e2a1d68aed06548))
- Windows build: also run createDistributable so the zip step finds the app folder ([`a411086`](https://github.com/maxcodl/PaperPlay/commit/a411086213a7720af8ea3fc7aa20257fa0739ab4))
- Desktop motion: sidebar, panel, hover, overlay and tray menu ([`31e6dd5`](https://github.com/maxcodl/PaperPlay/commit/31e6dd52b23c767825302c3879229b32eab0ab89))
- Lists glide, covers fade in, nav pill uses the shared spring ([`15fccc6`](https://github.com/maxcodl/PaperPlay/commit/15fccc6347ee5589d1bb541f0ffd72bfc573c93e))
- Screens slide and fade between pages instead of switching instantly ([`723628e`](https://github.com/maxcodl/PaperPlay/commit/723628ef4e0da077261b9e9993d0bcf2b0a3ac74))
- Full player slides up and down, follows the finger and the back swipe ([`ee60f8d`](https://github.com/maxcodl/PaperPlay/commit/ee60f8d232ed3879ae78263077ef45bbb6e864f6))
- Motion tokens and a Reduce motion setting ([`100b17d`](https://github.com/maxcodl/PaperPlay/commit/100b17d91eb427b7348ceb81b27037eaf1699fc9))
- Announce each paired device once per run, not every 20-second dial ([`79a694d`](https://github.com/maxcodl/PaperPlay/commit/79a694d491d6c92cc5bdb2b53430b05b895c3eb9))
- Windows build: a missing TDLib release no longer fails the step ([`966e52b`](https://github.com/maxcodl/PaperPlay/commit/966e52b93938c2e5afe948b7e95da51ca7289343))
- Build the Android debug and release APKs and the Windows and Linux apps ([`056120b`](https://github.com/maxcodl/PaperPlay/commit/056120b8fe1bf84231a6911d9827285dd6415383))
- Workflow reads the signing secrets under the names the other repo uses ([`c9fed95`](https://github.com/maxcodl/PaperPlay/commit/c9fed953de14f17f3ddeb61bb2d33b10e18e7d86))
- Respect upload back-off, count real listening time, refuse delete without a main chat; update docs ([`b9e70bc`](https://github.com/maxcodl/PaperPlay/commit/b9e70bc482032339ee6c1accbd7846428ff8860a))
- Restore while connected: shut everything down first and wait for TDLib's receive loop ([`d2457e8`](https://github.com/maxcodl/PaperPlay/commit/d2457e8dc2598e2c6d50f1738daf552e83ce2e25))
- Checklist: audit of what the code contains ([`42d9aae`](https://github.com/maxcodl/PaperPlay/commit/42d9aaec2ecc958ec9a391015c62a238179b7783))
- Lock the release-group cache and time out idle loopback logins ([`42aa3f6`](https://github.com/maxcodl/PaperPlay/commit/42aa3f6985a920e5307947f423b969a17244b2ac))
- Fix keep-running setting, pairing lockout counters, shared import race and range parsing ([`b3001cf`](https://github.com/maxcodl/PaperPlay/commit/b3001cfc84b5e6c4282d7f561e0c86abcce93658))
- Workflow builds only the Android debug APK for now ([`691a094`](https://github.com/maxcodl/PaperPlay/commit/691a09470a396d8162e34f8c816ba3364464b4a9))
- Backup: background and standby settings follow the layout choice ([`fa77b73`](https://github.com/maxcodl/PaperPlay/commit/fa77b7307975eeae71feb13ec3c832d83e18ea1c))

**Full changes:** [e7a6417...v1.3.0](https://github.com/maxcodl/PaperPlay/compare/e7a6417...v1.3.0)
