# Voice Diary

Voice Diary is an Android app that continuously keeps a short, rolling recording from the phone's **microphone**. Save the recent audio when you want to keep it. Recordings stay in a folder you choose.

## Use the app

1. Tap **Start Recording** and choose a recordings folder the first time.
2. Choose how many recent minutes to keep available (5–60 minutes).
3. Start recording. Voice Diary writes one-minute audio chunks into `Audio Chunks` inside your folder.
4. Tap **Save Available Audio** to keep the recent continuous audio in `Your Saved Audio`.
5. **Clear Audio Chunks** removes temporary Voice Diary chunks only; it does not delete saved replays or unrelated files.

The app also has appearance controls and a first-launch spotlight guide. The update button is a placeholder: GitHub Releases checking and APK installation have not been connected yet.

## Build and test on Windows

Open PowerShell in this folder:

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:lintDebug
```

The debug APK is written to `app\build\outputs\apk\debug\app-debug.apk`.

## Where to look

```text
app/src/main/java/com/voicediary/app/
  ui/          Screens, dialogs, and UI interactions
  recording/   Foreground recording service, reboot resume, replay-window rules
  storage/     User-selected folder access, audio files, and replay creation
app/src/main/res/
  layout/      Screen layouts
  drawable/    Icons and button backgrounds
  values/      Text, colors, and themes
  xml/         Android backup and data-extraction rules
app/src/test/java/com/voicediary/app/recording/
  SegmentRetentionPolicyTest.kt
docs/          Plain-language map of how the app is built
```

For the full file-by-file map and the path an audio recording takes through the app, see [the project guide](docs/ARCHITECTURE.md).

## Important Android limitations

- Voice Diary records microphone input. Android does not allow ordinary apps to reliably capture phone calls or other apps' internal audio.
- A foreground service is used to continue recording when the app is swiped away. Android can still stop recording because of force-stop, permission changes, device policies, or hardware interruptions.
- After a reboot, Android requires the user to tap a notification before microphone recording can resume.
- Audio is saved locally. GitHub updates, in-app playback, and sharing are not configured.
