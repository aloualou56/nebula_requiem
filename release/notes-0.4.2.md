Nebula Requiem 0.4.2 for Android: the music no longer drops out during play.

**Download:** `nebula-requiem-0.4.2.apk` below. It installs over 0.4.1 (and the same build published as 0.3.1), 0.4 and 0.3, and keeps your progress.

> **Coming from 1.1.1 or earlier?** Like 0.3 and 0.4, this version is signed with a different key from 1.0, 1.1 and 1.1.1, so Android won't install it over them. Uninstall the old version first; that deletes its save.

### Fixed

- **The music bug is fixed: the music no longer stops for several seconds during play.** The game's music was fine; the sound got stuck on its way to the speaker. The game handed its sound to Android and waited for Android to take more. When Android stopped taking it for a moment, as can happen on a busy phone, the game could be left waiting, and the music went silent until it came back on its own seconds later. The game no longer waits. It notices within a fraction of a second when the sound stops going out, keeps the music in time, and if the sound still isn't going out after half a second, reconnects to the speaker.
- On phones that can't keep up with the audio, its buffer now grows a little instead of crackling.

Gameplay, difficulty, saves and everything else are unchanged from 0.4.1.

### Requirements

- Android 13 or newer, in landscape; the only permission is vibration (no internet permission)

### Installing

Open the APK on the device and allow your browser or file manager to install unknown apps when Android asks. It is signed with the same key as 0.3, 0.4 and 0.4.1, so it installs as an update and keeps your progress, including a run in progress. If the game ever stops unexpectedly, the next launch shows a report you can copy and send in.

SHA-256: `7e4d989317fdc6f6cb19a148bba611683e5e7427176df2c5f22eb6a389d5ceb9`
