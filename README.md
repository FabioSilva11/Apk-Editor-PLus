# APK Editor Plus

APK Editor Plus is an Android application for inspecting and modifying APK files directly on the device. The interface is being migrated to Jetpack Compose with Material 3 while the editing behavior is brought closer to the original APK Editor.

> [!WARNING]
> This project is currently **under development** and may contain bugs. Use it at your own risk.

## 🚀 Current features

- **Simple Edit with real pages**: Browse APK files through separate **Files**, **Images**, and **Audio** pages backed by `ViewPager2` and fragments.
- **Hierarchical file browser**: Navigate APK folders, replace individual files, export entries, and clear pending replacements.
- **Real image thumbnails**: File managers show the actual image preview for local files and resources stored inside APKs, using memory-limited decoding.
- **Image resources**: Preview images and replace matching density variants together when they share the same resource name.
- **Audio resources**: Find common audio formats, play or pause them, replace them, and export them.
- **String resources by installed language**: Show only languages that already exist in the APK; the add-language action lists the remaining languages with flags and marks newly copied texts for review.
- **Per-file change history**: Review textual changes as line-by-line diffs, identify changed binary files, and discard changes one file at a time.
- **Resource Editing**: Modify AXML, string resources, colors, and XML files directly.
- **Advanced Code Editor**: Powered by [Sora Editor](https://github.com/Rosemoe/sora-editor), featuring syntax highlighting and smooth performance.
- **APK Signer**: Build and sign your modified APKs with custom or internal keystores.
- **KeyStore Manager**: Easily manage your digital certificates and signing keys.
- **SQLite Support**: View and edit SQLite databases within APKs or app data.
- **Image Editor**: View and modify PNG assets within packages.
- **Project-Based Workflow**: Manage complex modifications as organized projects for better tracking.
- **Material 3 interface**: Consistent file rows and icons, navigation-bar-safe actions, and the original APK Editor launcher icon.
- **Git status**: Display commit authors with their GitHub profile photo when available.

## 🛠️ Tech Stack

- **Language**: Kotlin
- **Build System**: Gradle (Kotlin DSL)
- **Core Libraries**:
    - [Sora Editor](https://github.com/Rosemoe/sora-editor) for code editing.
    - `apksig` for secure APK signing.
    - `bouncycastle` for cryptographic support.
    - `gson` for data handling.
    - `aXML` for Android XML manipulation.

## 🏗️ Getting Started

### Prerequisites

- Android Studio Ladybug or newer.
- JDK 17.
- Android device or emulator (API 24+).

### Building from Source
1. Clone the repository:
   ```bash
   git clone https://github.com/FabioSilva11/Apk-Editor-PLus.git
   ```
2. Open the project in Android Studio.
3. Sync Project with Gradle Files.
4. Build and run the `app` module.

### Low-memory build (4 GB RAM)

On Windows PowerShell, use a single worker and a temporary Gradle daemon:

```powershell
$env:GRADLE_OPTS='-Xmx768m -XX:MaxMetaspaceSize=256m -Dfile.encoding=UTF-8'
.\gradlew.bat --no-daemon --max-workers=1 assembleDebug
```

The generated debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## 🤝 Contributing

We welcome contributions of all kinds! This project is a labor of love, and we'd love for you to help make it even better.

- **Found a bug?** Open an issue.
- **Have a feature idea?** Submit a proposal in the discussions.
- **Want to code?** Fork the repo and submit a Pull Request!

Please make sure to follow the existing code style and provide clear descriptions for your changes.

## 📄 License

This project is licensed under the [MIT License](LICENSE) (Placeholder - please update if you have a specific license in mind).

---
*Developed with ❤️ by the Apk Editor Plus Team.*
