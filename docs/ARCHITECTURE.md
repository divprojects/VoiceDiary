# Voice Diary project guide

This guide explains what each part of the project does and how one recording moves through the app. Start with the root [README](../README.md) for user steps and build commands.

## How a recording moves through the app

1. **`ui/MainActivity.kt`** handles the screen, permissions, the selected replay length, folder selection, and button taps. It asks Android for microphone permission and sends start, stop, and save requests to the recording service.
2. **`recording/RecordingService.kt`** runs as an Android microphone foreground service. It writes one-minute AAC audio chunks, keeps the rolling window tidy, and stays active when the app task is swiped away.
3. **`storage/StorageManager.kt`** reads and writes in the folder selected by the user. It creates `Audio Chunks` and `Your Saved Audio`, lists temporary chunks, and joins/trims recent chunks into a saved replay.
4. **`recording/SegmentRetentionPolicy.kt`** contains the time-window rules used to decide which audio is still available and which chunks have expired.
5. **`storage/SegmentFileMetadata.kt`** creates and reads the timestamps in Voice Diary audio filenames.

When the user taps **Save Available Audio**, the service asks storage to find the newest continuous audio window, then saves it in `Your Saved Audio`. A real gap in the chunks shortens the replay rather than being silently filled.

After a device restart, **`recording/BootResumeReceiver.kt`** checks whether recording had been active and posts a notification. The user must tap it to resume; Android does not permit this app to silently start microphone recording during boot.

## Folder map

```text
VoiceDiary/
  app/
    build.gradle.kts                 Android app SDK, dependencies, and build settings
    src/
      main/
        AndroidManifest.xml          App entry point, permissions, service, and receiver
        java/com/voicediary/app/
          ui/
            MainActivity.kt          Main screen and user interactions
            CoachMarkOverlayView.kt  Spotlight shown by the first-launch guide
          recording/
            RecordingService.kt      Microphone recording and replay requests
            BootResumeReceiver.kt    Resume notification after device restart
            SegmentRetentionPolicy.kt Pure time-window and retention rules
          storage/
            StorageManager.kt        Selected-folder access and audio file operations
            SegmentFileMetadata.kt   Audio chunk filename timestamps
        res/
          layout/                    XML screen layouts
          drawable/                  Icons and drawable button backgrounds
          mipmap-anydpi/              Adaptive launcher icon
          values/                    UI strings, colors, and themes
          xml/                       Android backup/data extraction policy
      test/
        java/com/voicediary/app/recording/
          SegmentRetentionPolicyTest.kt
  docs/
    ARCHITECTURE.md                  This guide
  gradle/wrapper/                    Pinned Gradle wrapper version
  build.gradle.kts                   Android/Kotlin plugin versions
  settings.gradle.kts                Project name and app modules
  gradle.properties                  Gradle and Kotlin build settings
  gradlew.bat                        Windows Gradle launcher
  README.md                          Start here
```

Gradle's `.gradle`, `.kotlin`, `build`, and `app/build` folders, as well as `.idea`, are local caches, generated build output, or IDE settings. They are not app source and should not be edited as part of normal feature work.

## Important behavior and boundaries

- The user must choose a writable folder before recording. Voice Diary remembers access to that folder using Android's Storage Access Framework.
- Temporary chunks go under `Audio Chunks`; saved replays go under `Your Saved Audio`. Cleanup only targets audio files recognized as Voice Diary temporary chunks.
- A microphone foreground service and partial wake lock support screen-off recording. They cannot override Android force-stop, permissions, hardware privacy controls, or device-specific power management.
- The update button queries `https://api.github.com/repos/divprojects/VoiceDiary/releases/latest` to check for newer APK releases and prompts the user to download and install updates.
- The app records microphone input only. Android controls whether a Bluetooth microphone is routed to it.

## Change and verify code

- Change screen behavior in `ui/MainActivity.kt`; screen structure and static resources live under `app/src/main/res`.
- Change microphone lifecycle or notifications in `recording/RecordingService.kt`.
- Change folder access, chunk listing, cleanup, or replay file creation in `storage/StorageManager.kt`.
- Keep retention-rule changes covered by `SegmentRetentionPolicyTest.kt`.
- Run `.\gradlew.bat :app:testDebugUnitTest` for unit tests. Run `.\gradlew.bat :app:assembleDebug` to build the APK and `.\gradlew.bat :app:lintDebug` to check Android code and resources.
