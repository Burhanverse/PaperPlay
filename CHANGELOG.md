# Changelog

All notable changes to PaperPlay are listed here, newest first. Each stable release adds its section automatically.

## [1.7.0] - 2026-11-14

### Added
- Record crashes, how the last run ended, and the system log ([`c2cf455`](https://github.com/maxcodl/PaperPlay-compose/commit/c2cf455c5855404b6ce20add2b6e6f78d0aaff95))
- Share the full log as a file or text from the Log page ([`01bf96a`](https://github.com/maxcodl/PaperPlay-compose/commit/01bf96abcc58baa1882e36a85cd5c27e3d8e0d45))
- Memory line every ten seconds in the log ([`fdea970`](https://github.com/maxcodl/PaperPlay-compose/commit/fdea970f493a33a9f5e41442c3042ea0a95a4ae0))
- Playback log kept on disk with every player, stream and file event ([`5e97c42`](https://github.com/maxcodl/PaperPlay-compose/commit/5e97c42508f42dc926854f9b00aab73f4b79cbf6))

**Full changes:** [v1.6.0...v1.7.0](https://github.com/maxcodl/PaperPlay-compose/compare/v1.6.0...v1.7.0)

## [1.6.0] - 2026-11-14

### Added
- Scan banner shows the bot's name ([`de64d8a`](https://github.com/maxcodl/PaperPlay-compose/commit/de64d8a243fe4c4dbce0d530915ee7cf5f83be35))
- Indexing count goes up smoothly ([`95d1434`](https://github.com/maxcodl/PaperPlay-compose/commit/95d143414fdafa2781c8109a9c47c6ed3513a5c1))

### Documentation
- Checklist for indexing count and bot names ([`3877980`](https://github.com/maxcodl/PaperPlay-compose/commit/38779808794ff936470ad5204c4a7e195945e209))

**Full changes:** [v1.5.0...v1.6.0](https://github.com/maxcodl/PaperPlay-compose/compare/v1.5.0...v1.6.0)

## [1.5.0] - 2026-11-14

### Added
- Smooth sync counter, full scan shows which bot or account is reading ([`427f18b`](https://github.com/maxcodl/PaperPlay-compose/commit/427f18b24bbfc69fcb5cac50b6990aa4c73b3193))
- Duplicates delete counts one by one, page updates live and scrolls to top ([`3c5c03f`](https://github.com/maxcodl/PaperPlay-compose/commit/3c5c03fb9a02f3495151fe2b231f346834321997))

### Documentation
- Checklist for the last round ([`26768dd`](https://github.com/maxcodl/PaperPlay-compose/commit/26768ddf43350b869109d465b3e78890fd5e3eee))

**Full changes:** [v1.4.0...v1.5.0](https://github.com/maxcodl/PaperPlay-compose/compare/v1.4.0...v1.5.0)

## [1.4.0] - 2026-11-14

### Added
- Library settings in drop-downs, read with other bots after a rescan ([`80ca842`](https://github.com/maxcodl/PaperPlay-compose/commit/80ca842f2d7e78b59a7aa5d80126a33d7ccf4148))
- Song info left page redesign, tasks open the live list ([`d49ccc7`](https://github.com/maxcodl/PaperPlay-compose/commit/d49ccc7866b848fd1362a8350273c1dd14b99160))
- Faster song info, Deezer first ([`e8ce2df`](https://github.com/maxcodl/PaperPlay-compose/commit/e8ce2df67af12f3959815d036852880943d98276))
- Reading speed adjusts itself ([`977f251`](https://github.com/maxcodl/PaperPlay-compose/commit/977f251e00e52f5084649b9c389ac93a9da115e5))
- Search songs by language ([`2bbe168`](https://github.com/maxcodl/PaperPlay-compose/commit/2bbe168e93d30b830aab7da785a3e932cba65b1e))
- Duplicates page with a floating delete, auto-ticked extras, topic names and a lazy list ([`fbb4fdb`](https://github.com/maxcodl/PaperPlay-compose/commit/fbb4fdb9e735fafeecf9f7fe7b91b0c7e668c304))
- Spotify playlists list missing songs greyed out, with copy links, and lists remember their sort ([`d14810f`](https://github.com/maxcodl/PaperPlay-compose/commit/d14810f0566d8ff9135bbe2870d1e2caac41897a))
- Home skeletons, steadier covers, swipeable tiles, coloured toasts and a mini player that swipes away ([`f1988d3`](https://github.com/maxcodl/PaperPlay-compose/commit/f1988d3c8c1fe196845d1f1d31119a3d0afb2f70))
- Upload a music folder in place, ask before subfolders, skip songs under 2 MB ([`c4443aa`](https://github.com/maxcodl/PaperPlay-compose/commit/c4443aae0c864b8f0a942cd7d469dd94263d5006))
- Progress for missing and low-quality covers in the tasks list and the notification ([`d8b4613`](https://github.com/maxcodl/PaperPlay-compose/commit/d8b46138c7820eed428410d23de421190b01fc7d))
- Read a song's length from its file header ([`edce335`](https://github.com/maxcodl/PaperPlay-compose/commit/edce335d5e21b5b1cca795a69db79c113afe9fe3))
- Show an update dialog on every launch when a newer release exists ([`0656b1c`](https://github.com/maxcodl/PaperPlay-compose/commit/0656b1c34ceb68a9702f3df83113279bf002be80))
- The in-app logo follows the app theme ([`84afc56`](https://github.com/maxcodl/PaperPlay-compose/commit/84afc56dfc1b917f9657d4a13fd618db5f76d7f9))

### Fixed
- Duplicate delete goes through the Bot API per post ([`a830641`](https://github.com/maxcodl/PaperPlay-compose/commit/a8306414c099e689217ba33db47e8bb45c799a06))
- Windows release bundles a Java 25 runtime so the installer starts ([`5273468`](https://github.com/maxcodl/PaperPlay-compose/commit/5273468b3fdbf33f2069a1cc6a1cf004162f200c))
- Onboarding shows the chosen photo and no false Not ready yet on the bot page ([`0ed267e`](https://github.com/maxcodl/PaperPlay-compose/commit/0ed267ea7b77791e7b3b9ad68184ebad5965b075))
- Uploads use the request shape of the loaded TDLib, run in parallel and send the song length ([`48bf3ad`](https://github.com/maxcodl/PaperPlay-compose/commit/48bf3ad0500c78b66a4da61e8f90ce7489798d8d))
- The Windows release includes TDLib so it can reach Telegram ([`734f350`](https://github.com/maxcodl/PaperPlay-compose/commit/734f3500d0f8dafe5108fe94aeb88d3c11e63dab))
- CI ProGuard reads the JDK 17 classes because the Gradle JDK 25 has no jmods ([`7abe514`](https://github.com/maxcodl/PaperPlay-compose/commit/7abe5148acd33e9f25ad6dbdd090e62a74baec38))
- Setup tells the user to remove an older per-machine install ([`27e0c98`](https://github.com/maxcodl/PaperPlay-compose/commit/27e0c9861fea9d9695bd7fcfd359295429b48362))
- GitHub Windows release uses the same minified build and Inno installer as the release script ([`501cd20`](https://github.com/maxcodl/PaperPlay-compose/commit/501cd20349d674146ff23ea8f624763df8aa8650))
- Package Compose resources on Android so the settings logo no longer crashes ([`cfba237`](https://github.com/maxcodl/PaperPlay-compose/commit/cfba237764ef0308f8aa134c0a3eb4ba18acda74))

### Documentation
- Checklist and readme ([`222d1e9`](https://github.com/maxcodl/PaperPlay-compose/commit/222d1e9b7faaa16e726b4b47599fb6e88de77227))
- Checklist for this round of work ([`163609f`](https://github.com/maxcodl/PaperPlay-compose/commit/163609f461c44a41389e265e1b20270f64ffd95a))
- TDLib is in the repository, CI fixes in the checklist ([`3b2d01e`](https://github.com/maxcodl/PaperPlay-compose/commit/3b2d01eb2b55962758383dad291067ff26278100))
- The update check runs at every launch and shows a dialog ([`ed49815`](https://github.com/maxcodl/PaperPlay-compose/commit/ed49815a2b6b0d395da7a234bdb9640bba8ac9d4))
- Checklist for the settings crash, themed logo and one Windows installer ([`cd3c93e`](https://github.com/maxcodl/PaperPlay-compose/commit/cd3c93e08d2a32d0a02502da9e8460b030307e1c))
- Update the README and the checklist for releases, the updater, About, logout and the merge-back rule ([`cc1ff44`](https://github.com/maxcodl/PaperPlay-compose/commit/cc1ff44979c3a0acc12797453346eab4110ebd94))

**Full changes:** [v1.3.1...v1.4.0](https://github.com/maxcodl/PaperPlay-compose/compare/v1.3.1...v1.4.0)

## [1.3.1] - 2026-11-14

### Fixed
- The in-app logo uses the launcher icon's own plane image instead of a redrawn badge ([`2a81d2c`](https://github.com/maxcodl/PaperPlay-compose/commit/2a81d2c27e2d9ce24579a0c5a4e05413a16fd68f))

**Full changes:** [v1.3.0...v1.3.1](https://github.com/maxcodl/PaperPlay-compose/compare/v1.3.0...v1.3.1)

## [1.3.0] - 2026-11-14

### Added
- Compact About page with drop-down rows for the update channel and installation method ([`111eced`](https://github.com/maxcodl/PaperPlay-compose/commit/111eced1349e77c0374146f8b4b7492336f28acd))
- Real app icon with a plane-hits-the-note animation, animated tour background, About card on Profile ([`32e150b`](https://github.com/maxcodl/PaperPlay-compose/commit/32e150b76cf79e8bc8b51b38bbbbed229596f246))
- Settings grouped under headings with About last; About shows the installation method with Shizuku below ([`d3a9a6b`](https://github.com/maxcodl/PaperPlay-compose/commit/d3a9a6b58c6ded7f81620da2ed3d67f40fa74917))
- Log out options (everything, bot only, account only) and log out with delete all data ([`e84d831`](https://github.com/maxcodl/PaperPlay-compose/commit/e84d8317d34bb094af254f22bd31d46f6e433281))
- Optional Shizuku installer for updates, with permission prompts and toasts ([`84b797d`](https://github.com/maxcodl/PaperPlay-compose/commit/84b797d6e66243b5b935b6c8bd8188d91969604f))
- Install updates on Windows by running the downloaded setup; Linux opens the release page ([`fdb136d`](https://github.com/maxcodl/PaperPlay-compose/commit/fdb136d6158e9e4ff875ea16c7d35a9c1637e3f7))
- Open the install permission screen at once and continue the update when it is allowed ([`e6837b8`](https://github.com/maxcodl/PaperPlay-compose/commit/e6837b891d1ce91bf880132493d3daea3c21eec6))
- Install updates on Android through the package installer, with a permission check ([`bd80e0d`](https://github.com/maxcodl/PaperPlay-compose/commit/bd80e0da076083bdccd3f7fac62176136950cde5))
- About page with update check, update choices and the Made by Max footer ([`69cc5ec`](https://github.com/maxcodl/PaperPlay-compose/commit/69cc5ecf38abbdd21ab2932d0eb7a01bd18304c0))
- Update logic - versions, the GitHub release feed and an update service ([`aede1b0`](https://github.com/maxcodl/PaperPlay-compose/commit/aede1b090413594bc5bd351290aba51b436bb28b))
- Automatic versions, releases and change logs from the commit subjects ([`229efc2`](https://github.com/maxcodl/PaperPlay-compose/commit/229efc24495dba09e543763fd8cf59a93a77f575))

### Fixed
- Import the state delegates the drop-down row needs ([`6d941db`](https://github.com/maxcodl/PaperPlay-compose/commit/6d941db36f74535aea572e27213399b1861f57f6))
- A running Shizuku counts as installed even when the package lookup is hidden ([`928410c`](https://github.com/maxcodl/PaperPlay-compose/commit/928410c84ffb5ca6cea21da9d5523aedc39c2c8c))
- Pass the update service to the Profile screen after the positional arguments ([`ee9b88e`](https://github.com/maxcodl/PaperPlay-compose/commit/ee9b88e703cd75b449c843171eb0de022ae6f888))
- A mention of BREAKING CHANGE in prose no longer counts as a major bump ([`8e1a546`](https://github.com/maxcodl/PaperPlay-compose/commit/8e1a546dbc63732be138eef72115431ee0f57433))

### Documentation
- Checklist entries for logout options, grouped Settings, About and the new logo ([`262f8a1`](https://github.com/maxcodl/PaperPlay-compose/commit/262f8a1faa02a6d366f6398b36167fd8d1554cbf))
- Describe in-app updates and update the checklist ([`bf102a3`](https://github.com/maxcodl/PaperPlay-compose/commit/bf102a3c35826abcb674646858ab3cebf3fe7fd9))
- Rewrite the README, update the checklist ([`4fae430`](https://github.com/maxcodl/PaperPlay-compose/commit/4fae4306dd39237efbf8bc72e43c3370d4953ac8))
- Motion work, CI state, open items ([`5656ac2`](https://github.com/maxcodl/PaperPlay-compose/commit/5656ac287c8e1b0d291ad9804211ef3eaefe2317))

### Other changes
- Smoother UI: press feedback, title and cover crossfades, gliding grids, tabs keep their scroll ([`e650ade`](https://github.com/maxcodl/PaperPlay-compose/commit/e650ade4c3a284dacdcdb84e8e2a1d68aed06548))
- Windows build: also run createDistributable so the zip step finds the app folder ([`a411086`](https://github.com/maxcodl/PaperPlay-compose/commit/a411086213a7720af8ea3fc7aa20257fa0739ab4))
- Desktop motion: sidebar, panel, hover, overlay and tray menu ([`31e6dd5`](https://github.com/maxcodl/PaperPlay-compose/commit/31e6dd52b23c767825302c3879229b32eab0ab89))
- Lists glide, covers fade in, nav pill uses the shared spring ([`15fccc6`](https://github.com/maxcodl/PaperPlay-compose/commit/15fccc6347ee5589d1bb541f0ffd72bfc573c93e))
- Screens slide and fade between pages instead of switching instantly ([`723628e`](https://github.com/maxcodl/PaperPlay-compose/commit/723628ef4e0da077261b9e9993d0bcf2b0a3ac74))
- Full player slides up and down, follows the finger and the back swipe ([`ee60f8d`](https://github.com/maxcodl/PaperPlay-compose/commit/ee60f8d232ed3879ae78263077ef45bbb6e864f6))
- Motion tokens and a Reduce motion setting ([`100b17d`](https://github.com/maxcodl/PaperPlay-compose/commit/100b17d91eb427b7348ceb81b27037eaf1699fc9))
- Announce each paired device once per run, not every 20-second dial ([`79a694d`](https://github.com/maxcodl/PaperPlay-compose/commit/79a694d491d6c92cc5bdb2b53430b05b895c3eb9))
- Windows build: a missing TDLib release no longer fails the step ([`966e52b`](https://github.com/maxcodl/PaperPlay-compose/commit/966e52b93938c2e5afe948b7e95da51ca7289343))
- Build the Android debug and release APKs and the Windows and Linux apps ([`056120b`](https://github.com/maxcodl/PaperPlay-compose/commit/056120b8fe1bf84231a6911d9827285dd6415383))
- Workflow reads the signing secrets under the names the other repo uses ([`c9fed95`](https://github.com/maxcodl/PaperPlay-compose/commit/c9fed953de14f17f3ddeb61bb2d33b10e18e7d86))
- Respect upload back-off, count real listening time, refuse delete without a main chat; update docs ([`b9e70bc`](https://github.com/maxcodl/PaperPlay-compose/commit/b9e70bc482032339ee6c1accbd7846428ff8860a))
- Restore while connected: shut everything down first and wait for TDLib's receive loop ([`d2457e8`](https://github.com/maxcodl/PaperPlay-compose/commit/d2457e8dc2598e2c6d50f1738daf552e83ce2e25))
- Checklist: audit of what the code contains ([`42d9aae`](https://github.com/maxcodl/PaperPlay-compose/commit/42d9aaec2ecc958ec9a391015c62a238179b7783))
- Lock the release-group cache and time out idle loopback logins ([`42aa3f6`](https://github.com/maxcodl/PaperPlay-compose/commit/42aa3f6985a920e5307947f423b969a17244b2ac))
- Fix keep-running setting, pairing lockout counters, shared import race and range parsing ([`b3001cf`](https://github.com/maxcodl/PaperPlay-compose/commit/b3001cfc84b5e6c4282d7f561e0c86abcce93658))
- Workflow builds only the Android debug APK for now ([`691a094`](https://github.com/maxcodl/PaperPlay-compose/commit/691a09470a396d8162e34f8c816ba3364464b4a9))
- Backup: background and standby settings follow the layout choice ([`fa77b73`](https://github.com/maxcodl/PaperPlay-compose/commit/fa77b7307975eeae71feb13ec3c832d83e18ea1c))

**Full changes:** [e7a6417...v1.3.0](https://github.com/maxcodl/PaperPlay-compose/compare/e7a6417...v1.3.0)
