# Beat Overlay

<p align="center">
  <img src="app/src/main/res/raw/logo_beatoverlay.svgl" width="128" height="128" alt="Beat Overlay Logo">
</p>

**Beat Overlay** is a feature-rich, highly customizable Android application that draws a floating rhythm bar over other apps and games. Designed for rhythm training, tempo practice, and gaming overlays, it provides real-time visual beat tracking, tempo progression, random pauses, slow rests, and haptic feedback.

----

## Features

- **Floating Overlay Track**: Draws a sleek rhythm bar over any app with customizable width, thickness, opacity, edge fading, and vertical position.
- **Dynamic Tempo Ramping**: Set a starting BPM, continuous speed-up rate (% per minute), and maximum cap.
- **Random Pauses & Restart Countdown**: Introduces random freeze intervals with configurable countdown timers before resuming.
- **Slow Rest Phases**: Configurable low-tempo rest periods that can trigger randomly or follow a pause.
- **Random Tempo Drift (Chaos)**: Simulates natural rhythm variation with time-based tempo fluctuations.
- **Haptic Feedback**: Tactile vibration ticks on every beat and navigation button with adjustable strength and duration.
- **Custom Beat Markers**: Customize the scrolling marker with standard shapes or your own custom PNG, JPG, WebP, or SVG images (including the built-in Beat Overlay logo).
- **Overlay Gestures**: Configure hold, double-tap, triple-tap, and swipe gestures directly on the floating bar.
- **Presets & Backup**: Built-in rhythm presets, custom user presets, and complete JSON export/import support.
- **Modern UI & Themes**: Clean Material-inspired design with System, Light, and Dark mode support and reduced motion options.

---

## 🛠️ Tech Stack & Requirements

- **Language**: [Kotlin](https://kotlinlang.org/)
- **Minimum SDK**: API 26 (Android 8.0 Oreo)
- **Target SDK**: API 34 (Android 14)
- **Architecture**: Pure Android View Framework with Foreground Services
- **Libraries**:
  - `androidx.core:core-ktx` — Android KTX Extensions
  - `com.caverock:androidsvg-aar` — Vector SVG Rendering

---

## 🚀 Building & Installation

### Prerequisites
- Android Studio Iguana (2023.2.1) or newer
- JDK 17 or higher
- Android SDK 34

### Build via Command Line
```bash
# Clone the repository
git clone https://github.com/your-username/BeatOverlay.git
cd BeatOverlay

# Build Debug APK
./gradlew app:assembleDebug
```
The output APK will be located at `app/build/outputs/apk/debug/app-debug.apk`.

### Permission Requirements
When launching **Beat Overlay** for the first time:
1. Tap **Start** in the bottom navigation bar.
2. Grant the **Display over other apps** (`SYSTEM_ALERT_WINDOW`) permission when prompted.
3. Return to the app and tap **Start** again to display the floating overlay bar.

---

## 📁 Project Structure

```
BeatOverlay/
├── app/
│   ├── src/main/
│   │   ├── java/com/example/beatoverlay/
│   │   │   ├── MainActivity.kt    # Main UI with Home, Presets, and Settings screens
│   │   │   ├── OverlayService.kt  # System Alert Window Floating Overlay Service
│   │   │   ├── BeatBarView.kt     # Rhythm track rendering and beat math engine
│   │   │   ├── SettingsStore.kt   # Preferences, presets, and JSON import/export
│   │   │   ├── MarkerImage.kt     # Image/SVG loader and processor for beat markers
│   │   │   ├── Ui.kt              # Custom View components and helpers
│   │   │   ├── Palette.kt         # Color palette definitions (Light/Dark themes)
│   │   │   ├── Gestures.kt        # Gesture detection for the overlay bar
│   │   │   └── Keys.kt            # Preference key definitions
│   │   ├── res/                   # Drawables, raw SVG assets, and layouts
│   │   └── AndroidManifest.xml    # App permissions and service definitions
│   └── build.gradle.kts           # App module build configuration
├── build.gradle.kts               # Top-level build file
└── settings.gradle.kts            # Project settings
```

---

## 📄 License

This project is open-source and available under the [MIT License](LICENSE).
