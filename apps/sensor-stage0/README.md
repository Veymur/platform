# Stage 0 Android Prototype

Goal: validate our Polar Verity Sense recording on Galaxy A23 / Android 14. The native Kotlin prototype is isolated from the future React Native app; the service under test and its event contracts will form the basis of the recorder. React Native/Expo integration and iOS have not yet been verified.

## Installation and First Recording

1. Transfer the APK to Galaxy A23 and open it. If Android prompts you, allow installation from the selected file manager/browser.
2. Charge and wear Verity Sense. Enable heart rate transmission mode. Close the Polar app during testing so it does not occupy the connection.
3. Open “Veymur · recording validation”, tap “Find Verity Sense”, and grant Bluetooth permission.
4. Select your sensor by its ID. EAN `725882065014` is a product code, not a Bluetooth ID; the app will display the discovered device's ID.
5. Wait for a real heart rate reading. Leave the route disabled for the first test.
6. Tap “Start recording”. You can allow recording notifications; denying them does not block local recording.
7. After one minute, lock the phone for 10 minutes. Then open the app: the saved HR counter should have increased.
8. Turn the sensor off for 30 seconds, then turn it back on. Check that the current heart rate becomes unavailable and the connection recovers.
9. Tap “Finish” and confirm saving. Then tap “Export last session” and save/share the ZIP.

Next recording: enable the route and grant precise location permission “while using the app.” Test it outdoors, then test GPS and HR together while the phone is locked. If GPS/permission is unavailable at startup, HR is recorded independently.

Interruption test: start a separate recording, force-stop the app through Android settings, then open it again. The recovered-recordings counter should increase; the last-session export should contain the `interrupted` state. Recording does not continue after a force stop.

Do not change power-saving settings before the first test: first validate Galaxy A23 behavior under normal conditions.

## Implemented Features

- Verity Sense selection, HR, sensor battery level, and automatic reconnection through Polar SDK.
- A `connectedDevice` foreground service, with `location` added when GPS is enabled and available; a partial wake lock during recording.
- A single writer thread, SQLCipher, separate HR/GPS/events, and a commit for each observation; counters increase after a successful commit.
- The database key is wrapped using Android Keystore; access does not require reauthentication while the screen is locked.
- Pauses exclude active time and session observations. On recovery after process termination, the available portion is marked interrupted.
- Current HR becomes stale after 5 seconds. This diagnostic parameter still needs validation through testing.
- Warning/stopping when free storage falls below 20 MiB; storage errors stop recording and preserve the available portion for export.
- Last-session ZIP: `metadata.json` and `records.csv`. HR timestamps represent receipt time on the phone; GPS includes its own source timestamp.
- No backend or transfer of recording contents to a server; the app does not declare the INTERNET permission.

Diagnostic exports are not encrypted and include coordinates if recorded. Sharing occurs only through a user-pressed button. Cached export copies older than one day are cleaned up during the next export; copies shared with other apps are managed by those apps.

## Build

Requires JDK 21, Android SDK Platform 35, Build Tools 35.0.0, platform-tools, and access to Maven/JitPack. Specify the Android SDK through `ANDROID_HOME` or the ignored `local.properties` file.

```bash
cd platform/apps/sensor-stage0
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. The debug signing key is local; this is a test build for stage 0.

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Tests using actual SQLCipher, Keystore, recovery, and ZIP export run on an Android emulator:

```bash
./gradlew :app:connectedDebugAndroidTest
```

Test recordings are created in separate files prefixed with `instrumentation-`, without affecting the user journal. The emulator verifies storage and startup but does not validate BLE or operation on Galaxy A23.

Analyze an export on a computer:

```bash
python3 scripts/analyze_export.py /path/to/veymur-session.zip
```

Detailed protocol and current limitations: `../../docs/recorder-validation.md`; dependencies: `../../docs/compatibility.md`.
