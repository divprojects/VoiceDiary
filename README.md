# Voice Diary

Voice Diary is an Android app that continuously keeps a short, rolling recording from the phone's **microphone**. Save the recent audio when you want to keep it. Recordings stay in a folder you choose.

## Use the app

1. Tap **Start Recording** and choose a recordings folder the first time.
2. Choose how many recent minutes to keep available (5–60 minutes).
3. Start recording. Voice Diary writes one-minute audio chunks into `Audio Chunks` inside your folder.
4. Tap **Save Available Audio** to keep the recent continuous audio in `Your Saved Audio`.
5. **Clear Audio Chunks** removes temporary Voice Diary chunks only; it does not delete saved replays or unrelated files.

The app also has appearance controls, a first-launch spotlight guide, and live update checking via GitHub Releases. Saved recordings can be played, shared through Android's share sheet, or deleted in the app. The update button checks `divprojects/VoiceDiary` and opens the release page in your browser; APK installation is handled by Android.

## Build and test on Windows

Open PowerShell in this folder:

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:lintDebug
```

The debug APK is written to `app\build\outputs\apk\debug\app-debug.apk`.

## Create a signed release APK

The APKs attached to earlier releases were signed with Android's debug certificate. Debug-signed APKs are for testing, can trigger Play Protect warnings, and are not suitable for a public release. Create a private release key in PowerShell with `keytool -genkeypair -v -keystore release-key.jks -keyalg RSA -keysize 2048 -validity 10000 -alias voice-diary` (it will prompt for passwords), or use Android Studio's **Build → Generate Signed Bundle / APK**. Keep the keystore and its passwords backed up securely. Never commit or share them.

For Gradle release builds, create an untracked `keystore.properties` file in the project root:

```properties
storeFile=release-key.jks
storePassword=YOUR_KEYSTORE_PASSWORD
keyAlias=YOUR_KEY_ALIAS
keyPassword=YOUR_KEY_PASSWORD
```

Keep the keystore file private too. Build with `.\gradlew.bat :app:assembleRelease`; the signed APK is written to `app\build\outputs\apk\release\app-release.apk`. Release builds intentionally fail when signing is not configured.

Android requires updates to be signed with the same key as the installed app. Because previous Voice Diary APKs used the debug key, moving to a new private release key means uninstalling the old app before installing this release. Back up recordings as needed first. Play Protect may still show a warning for an app distributed outside Google Play; signing improves publisher identity but does not guarantee Google will immediately trust a new app. For the smoothest public distribution, publish through Google Play and submit any mistaken Play Protect detection to Google for review.

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
- Audio is saved locally. Sharing is initiated only when you choose Share; the app does not sync recordings to a cloud service.
