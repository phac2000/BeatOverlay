# Building Beat Overlay in Code On The Go

1. Put the BeatOverlay folder (the one containing settings.gradle.kts) in phone storage, e.g. Documents/BeatOverlay.
2. Code On The Go > Open saved project > pick that folder. Let the first Gradle sync finish (needs internet, a few minutes).
   If it says the Gradle JVM is incompatible, pick Java 17 (or 21) in the app's settings, then sync again.
3. Build: Quick Run (play button) or the Build menu > assembleDebug.
4. APK: app/build/outputs/apk/debug/app-debug.apk. Tap it in a file manager to install (allow "install unknown apps").
5. Open Beat Overlay, tap Start in the bottom bar. The first time it sends you to the overlay permission screen:
   enable "Allow display over other apps", go back, tap Start again.

## Files
- MainActivity.kt   screens (Home, Presets, Settings) and the floating bottom bar
- Ui.kt             cards, sliders, switches, buttons
- Palette.kt        light / dark colours
- SettingsStore.kt  saved settings, presets, export / import
- Keys.kt           names of all settings
- OverlayService.kt the floating bar service (start, stop, pause)
- BeatBarView.kt    the beat bar drawing and rhythm logic
- res/drawable/     icons
