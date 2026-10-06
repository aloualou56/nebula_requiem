The first release of the native Android edition of [Nebula Requiem](https://github.com/aloualou56/android_html_game), a ground-up Kotlin rewrite of the cosmic bullet-hell roguelike. No HTML, no JavaScript, no WebView and no network access: rendering, sound effects and music all come from native code on the device.

**Download:** `nebula-requiem-1.0.apk` below (about 520 KB).

### What's in it

- The whole game: eight hostile types, four guardians with multi-phase bullet patterns, sector mutators and the Ascension loop
- Twenty-four synaptic grafts, three hulls and eleven permanent Hangar refits
- Graze and flux, Overdrive, the nova bomb and the phase dash
- Touch twin-stick controls, plus keyboard, mouse and gamepad
- Procedural sound effects and music, generated live on the device
- 60 to 240 Hz with a fixed-step simulation, so the game plays the same at any refresh rate
- Saves that survive crashes, plus a mid-run checkpoint you can resume

### Requirements

- Android 13 or newer
- Phones, tablets, foldables and Chromebooks; the game runs in landscape only
- The only permission is vibration (for haptics); there is no internet permission

### Installing

Open the APK on the device and allow your browser or file manager to install unknown apps when Android asks.

If you installed an earlier download of 1.0 that closed as soon as it opened, install this one over it: that build crashed on launch on every phone, and this one (build 101) fixes it. If the game ever stops unexpectedly, the next launch shows a report you can copy and send in.

This build was tested headlessly (simulation tests and software-rendering tests); the GPU effects, audio output and high refresh rates have not yet been checked on a physical device.

It is signed with a development key. If a copy of the game signed with a different key is already installed (for example the WebView edition), Android will refuse to install this one over it. Uninstall that copy first; note that uninstalling deletes its save.

SHA-256: `028bd4d34888c2d918986dcf2fb05a0db993418f1a71b2bdddb2cb033090ccb5`
