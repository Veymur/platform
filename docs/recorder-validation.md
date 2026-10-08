# Recorder Validation — Stage 0

Status: the user has installed the Android prototype; the first hardware export from Galaxy A23 / Android 14 confirms a short HR/GPS recording and session completion. The full hardware scenario matrix has not yet been completed. Stage 0 remains incomplete. iOS has not been tested.

## First Hardware Export — October 7, 2026

The user reported installing the APK and provided `veymur-session.zip`. Metadata: `samsung SM-A235F`, Android 14, app `0.0.1`, Polar SDK `8.4.0`, sensor ID withheld. The exact APK build is not recorded in the export. In device information, firmware characteristic `00002a26` has the value `0.1.6`; software characteristic `00002a28` is `3.0.16`.

- Exact recording timestamps are withheld. State: `completed`; active time: 56,652 ms; the `finish` event is present.
- 114 HR records; measured heart rate values are withheld. The first and last records are 55,990 ms apart; the maximum receipt interval is 1,076 ms, with no intervals longer than 5 seconds. The median interval is 24 ms: records arrive in groups, so the row count cannot be treated as the hardware measurement frequency. HR timestamps represent receipt on the phone.
- The route was enabled through explicit configuration; 3 GPS points were saved with separate source timestamps and accuracy of 59.4–117.2 m. The first point appeared approximately 50.1 seconds after startup. Such a short recording with low accuracy does not confirm route or distance usability; coordinates are not copied into this protocol.
- There are `activity_background` and `activity_foreground` events 6,741 ms apart; 12 HR records were saved in that interval. The user clarified that the screen was not locked and they did not switch to another app. The cause of these lifecycle events is unknown; they do not confirm the background-operation hardware scenario. The 10-minute screen-lock scenario remains unverified.
- Record sequence 122–243 has no gaps or duplicates; the global sequence does not have to start at 1. CSV/JSON are readable, and ZIP file CRCs are valid. This recording contains no pauses, recovery after termination, or sensor-disconnection events.
- Phone battery level was 100% in the configuration and at completion; 57 seconds is insufficient to measure battery consumption.

Checks from the `platform` root (the archive filename below is a placeholder; substitute the actual local filename):

```bash
python3 apps/sensor-stage0/scripts/analyze_export.py diagnostic-exports/veymur-session.zip
python3 -m unittest discover -s apps/sensor-stage0/scripts -v
```

Result: the analyzer completed successfully, with no HR gaps longer than 5 seconds; all 4 analyzer tests passed. CRCs, sequence continuity, HR intervals, and lifecycle events were also checked independently using the standard-library modules `zipfile`, `csv`, and `json`.

Persistence of real HR and GPS in a single short completed session and export readability are confirmed. Operation during prolonged screen locking, BLE reconnection, pause/resume, force stop/recovery, lack of network access, permission denial, and battery consumption remain unverified. The next scenario is recording with the screen locked for 10 minutes according to the README, followed by turning the sensor off/on while noting the times of these actions. The installation and full hardware-scenario task in AGENTS.md remains incomplete.

## Real-Phone Scenarios

For each scenario, record the date, APK version, phone model, OS, sensor firmware, whether GPS is enabled, initial/final battery level, and exported ZIP filename.

| Scenario | Expected Result | Actual Result |
|---|---|---|
| Discovery and explicit sensor selection | Connection, fresh HR, selected device ID | Awaiting test |
| 1-minute recording with the screen on | HR is persisted; export is readable | Awaiting test |
| Screen locked for 10 minutes | New HR continues to be persisted; intervals are checked | Awaiting test |
| Another app / background | The service continues local recording | Awaiting test |
| No Wi-Fi/mobile network | Recording and export remain available | Awaiting test |
| Turn the sensor off for 30 seconds | HR becomes stale; the gap is visible; no data is fabricated | Awaiting test |
| Turn the sensor back on | Connection recovers; a new portion is persisted | Awaiting test |
| GPS + HR outdoors | Two independent streams in the export | Awaiting test |
| GPS disabled before startup | HR continues without a route | Awaiting test |
| GPS disabled during recording | HR continues; the GPS gap is visible | Awaiting test |
| Bluetooth / location / notification permission denial | Clear status; HR does not require GPS or notifications | Awaiting test |
| Pause for 30 s → resume | Pause is excluded from active_ms and streams | Awaiting test |
| Process termination → relaunch | The saved portion is detected as interrupted | Awaiting test |
| Screen locking and reopening | SQLCipher/Keystore do not prevent persistence | Awaiting test |
| Long session of 1–2 hours | Battery consumption, gaps, and stability are measured | Awaiting test |

## Reading the Export

`metadata.json` describes the phone, Android, SDK, session, and session state. `records.csv` contains the sequence of committed events, UTC receipt time, elapsed realtime, and JSON payloads. GPS source time and accuracy are stored separately. HR has no hardware timestamp: phone receipt timestamps are used.

`scripts/analyze_export.py` checks structure, sequence, HR/GPS counts, pause events, and potential HR gaps. The 5-second threshold is diagnostic. The utility does not equate coverage with sensor accuracy or draw medical conclusions.

Compare the journal with the tester's actions: when the phone was locked, the sensor was turned off, and recording was paused. Interrupted recovery does not reconstruct recording while the process was absent; active_ms comes from the last saved checkpoint.

## Limitations

- Continuity after force stop/reboot is not promised; the service uses START_NOT_STICKY.
- A partial wake lock is deliberately used to validate reliability; its battery consumption still needs measurement.
- Each observation is committed, and active time is checkpointed once per second. Actual cost and loss of the last uncommitted callback are measured on the phone.
- Only the last session is exported; full history/session selection belongs to a later stage.
- Final HR aggregates, a route on a map, and GPX belong to later MVP‑A tasks; this prototype exports a diagnostic journal.
- The native Android prototype does not confirm React Native/Expo or iOS compatibility.

## Development-Environment Checks

Completed on October 7, 2026:

- `:app:assembleDebug` — successful; an installable debug APK was created.
- `:app:testDebugUnitTest` — 2 active-time tests passed.
- `:app:lintDebug` — no errors. Warnings primarily concern available dependency updates and diagnostic-interface localization; this does not confirm hardware reliability.
- `:app:connectedDebugAndroidTest` — 2 tests passed on an API 34 emulator: SQLCipher/Keystore, reopening, interrupted recovery, a completed session, and ZIP contents.
- `python3 -m unittest discover -s scripts -v` — 4 analyzer tests passed: HR gaps, pause exclusion, rejection of duplicate sequence values, and recovery after monotonic time resets on phone reboot.
- The APK was installed and MainActivity launched on an Android 14 emulator; storage and the screen are ready, with no fabricated HR.
- The debug APK was checked with `apksigner verify`; the phone copy is at `platform/artifacts/veymur-stage0-debug.apk` relative to the workspace root.

Build and verification command from `apps/sensor-stage0`:

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:connectedDebugAndroidTest
```

This environment used Gradle 8.13, JDK 21, and Android SDK installed in `$HOME/.cache/veymur-stage0`; a wrapper for the same version is included in the project. The hardware scenarios above remain unverified.

## Interface Translation — October 8, 2026

The README, app name in AndroidManifest, buttons, dialogs, recorder statuses, and system notification were translated into English. Recording behavior was not changed.

Checks: `rg -n '[А-Яа-яЁё]' apps` — no matches; `./gradlew :app:assembleDebug :app:lintDebug` from `apps/sensor-stage0` — BUILD SUCCESSFUL. Updated APK copy: `artifacts/veymur-stage0-debug.apk`. Display verification on the real phone after reinstallation remains incomplete; previous hardware results apply to the previously installed build.
