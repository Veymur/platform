# Recorder Validation — Stage 0

Status: the user has installed the Android prototype; hardware exports from Galaxy A23 / Android 14 confirm local recording and completion, including HR/GPS persistence during a screen-off interval longer than 10 minutes, continued GPS during sensor loss, HR resumption after reconnection, preservation of an interrupted HR session after force stop/relaunch, HR pause/resume with paused time excluded, and completed HR recording/export under tester-reported offline conditions, and HR-only recording with phone location disabled before startup. HR receipt gaps are documented below. The full hardware scenario matrix has not yet been completed. Stage 0 remains incomplete. iOS has not been tested.

## Kotlin Migration Baseline — October 13, 2026

Migration stage 2 subsequently selected a local Expo Module using Expo SDK 57/RN 0.86.3. A separate `com.veymur.sensor.bridgeprobe` development APK built for arm64-v8a/x86_64 and returned PROBE_OK on an Android 14 x86_64 emulator: JS→Kotlin registration, Polar version API and a temporary SQLCipher encrypted round-trip. See the [bridge experiment report](react-native-bridge-validation.ru.md) and [compatibility matrix](compatibility.md). This did not connect the sensor, transfer the recorder/service, access Stage 0 data or verify physical-device/foreground recording; previous hardware evidence remains separate and Stage 0 stays open.

Migration decomposition stage 1 has a reproducible Kotlin baseline. Recorder sources and build configuration match `777a33b04954e6acf782e953984fe9b10a56167d`; application behavior and dependencies were not changed. See the [baseline report](migration-baseline/stage1-kotlin-baseline.ru.md) for exact tools, source comparison, APK hashes, commands, scenario matrix, and core limitations.

Repeated with `--rerun-tasks`: debug APK build, 2 unit tests, and 2 instrumentation tests passed on an API 34 / Android 14 x86_64 emulator. Lint reported 0 errors, 25 warnings and 1 hint. All 4 Python analyzer tests passed. All 14 existing local diagnostic ZIPs passed CRC and the independent analyzer; their hashes, counts, active times, GPS settings and HR gap summaries are in the [comparison inventory](migration-baseline/exports-2026-10-13.json). The inventory excludes HR values, coordinates, sensor IDs and absolute timestamps; raw ZIPs remain local and ignored by Git.

Rebuilt APK SHA-256: `273716cbb3a013780f9568d6d0b16074cadcd3c67baf5442d0693c02a3c1b40c`, preserved as `artifacts/veymur-stage0-baseline-2026-10-13-273716cb.apk`. The previous `artifacts/veymur-stage0-debug.apk` was preserved separately. Both passed signature verification. Existing hardware exports do not identify the installed APK hash; they must not be attributed to this rebuilt APK.

No physical Galaxy A23 was connected through ADB during this work. No new phone/sensor run or phone export was performed. Existing results below remain historical evidence, not new hardware verification. Stage 0 and the hardware tasks of migration stage 1 remain incomplete. Next independent task: migration stage 2 dependency/bridge validation; when the phone is available, repeat baseline scenarios with APK/core identity, OS/firmware, settings and timed actions.

## Nearby Devices Permission Denial and Retry — Tester Verification

The user reports successfully completing the sequence: deny Veymur “Nearby devices” permission → attempt sensor discovery and observe a message → restore permission → repeat discovery and connect. This verifies the permission-denial/retry UI flow on the current Galaxy A23 / Android 14 test setup by tester observation. The exact message, permission restoration path, and installed APK build were not supplied. No ZIP was requested for this UI-only scenario; this result does not independently establish persistence during permission revocation or recovery of an already-active recording.

Verification: tester-reported successful flow; no new automated or hardware-journal evidence for this scenario. `git diff --check` — passed for the documentation update. Location and notification denial have separate recording/export evidence documented below. The three planned permission-denial cases are now covered for their tested scopes. Stage 0 remains incomplete: outdoor GPS behavior, GPS pause/interruption scenarios, long-session battery measurements, and the wider compatibility matrix remain pending. Next short indoor task is complete; remaining indoor recording work includes a 1–2-hour HR run and controlled technical loss-window measurements.

## Phone Bluetooth Off/On — Automatic HR Reconnection

Archive: `diagnostic-exports/veymur-08499a67-9dc9-4a11-b609-91e2da848bd7.zip`. The user reports switching phone Bluetooth off during recording, then enabling it and waiting for recovery; no manual sensor connection was needed. Samsung SM-A235F / Android 14, app 0.0.1, Polar SDK 8.4.0; exact APK build is not recorded. Times below are relative to start; HR values and private identifiers are omitted.

- State `completed`, finish event present; active_ms 101,883 (1 min 41.883 s). There are 46 HR rows; GPS was disabled and no GPS rows were saved.
- Bluetooth powered=false at 15.861 s; powered=true at 88.065 s: adapter disabled for 72.204 s. Two disconnected callbacks at 15.893/15.910 s describe one observed loss episode.
- Connected at 92.305 s, 4.240 s after Bluetooth enabled. First recovered HR at 95.425 s, 3.120 s after connected and 7.360 s after Bluetooth enabled. Fourteen recovered HR rows were persisted in the same session. Automatic recovery is supported by these events and the tester's report of no manual connection action.
- Last pre-loss HR at 15.496 s, first recovered HR at 95.425 s: 79.929-second receipt gap, with no HR rows inside it. This is the only interval over the diagnostic 5-second threshold. There are no pause, interruption, screen-off, or activity-background events.
- ZIP CRC passed; metadata/CSV and payloads parse successfully. All 69 rows have consecutive sequence numbers 5,338–5,406. Phone battery changed from 88% to 87%; this short run does not establish recorder consumption.

Checks: ran the existing analyzer's `analyze()` on this archive; independently checked CRC, sequences, Bluetooth and connection event ordering, HR gap, and post-recovery counts with Python standard-library parsers. `python3 -m unittest discover -s apps/sensor-stage0/scripts -v` — all 4 tests passed; `git diff --check` — passed. Reproduce analyzer output locally with:

```bash
python3 apps/sensor-stage0/scripts/analyze_export.py diagnostic-exports/veymur-08499a67-9dc9-4a11-b609-91e2da848bd7.zip
```

Phone Bluetooth off/on with automatic reconnection and resumed HR persistence is confirmed for this run. This is separate from denying the app Bluetooth permissions, which remains pending. Next indoor UI test: deny “Nearby devices” permission in Android app settings, attempt discovery, report the error/action shown, then restore permission and verify discovery/connection can be repeated. Stage 0 remains incomplete.

## Sensor Reconnection While Phone Locked

Archive: `diagnostic-exports/veymur-5bf1887e-2108-4d84-a4ab-e8e8ebda5b10.zip`. The user reports powering the sensor off/on while the phone remained locked, then waiting before unlocking. Samsung SM-A235F / Android 14, app 0.0.1, Polar SDK 8.4.0; exact APK build is not recorded. Times below are relative to start; private identifiers, exact UTC timestamps, and HR values are omitted.

- State `completed`, finish event present; active_ms 191,364 (3 min 11.364 s). There are 200 HR rows; GPS was disabled and no GPS rows were saved.
- Activity background at 3.226 s, screen off at 3.731 s. Disconnected callbacks at 22.559/22.580 s represent one observed loss episode. Connected at 105.083 s, first recovered HR at 108.098 s (3.015 s later).
- Screen on only at 185.235 s; Activity foreground at 187.657 s. Therefore connection and HR persistence resumed before both screen-on and returning to the app. There are **156 recovered HR rows before screen-on**, covering 77.137 s from the first recovered HR until screen-on. This confirms reconnection without Activity interaction during the screen-off interval, with lock context supplied by the tester.
- Last pre-loss HR at 16.044 s, first recovered HR at 108.098 s: 92.054-second receipt gap, with no HR rows inside it. This is the only gap over the diagnostic 5-second threshold. Exact sensor power-on/off times are not recorded; although the tester reports waiting about two minutes after turning it on, the observed recovered-HR-before-screen-on period is 1 min 17.137 s. A full two-minute recovered-HR window is not required to establish that recovery happened while locked.
- ZIP CRC passed; metadata/CSV and payloads parse successfully. All 225 rows have consecutive sequence numbers 5,113–5,337. No pause or interruption events. Phone battery is 88% at start and finish; no consumption conclusion is supported.

Checks: ran the existing analyzer's `analyze()` on this archive; independently verified CRC, sequences, lifecycle ordering, receipt gap, and recovered HR counts before screen-on using Python standard-library parsers. `python3 -m unittest discover -s apps/sensor-stage0/scripts -v` — all 4 tests passed; `git diff --check` — passed. Reproduce analyzer output locally with:

```bash
python3 apps/sensor-stage0/scripts/analyze_export.py diagnostic-exports/veymur-5bf1887e-2108-4d84-a4ab-e8e8ebda5b10.zip
```

Sensor reconnection and resumed HR persistence while locked are confirmed for this run. This does not test switching the phone's Bluetooth adapter off/on. Next indoor scenario: during an active HR recording, disable phone Bluetooth for about one minute, enable it, wait for reconnection, finish, and export; report any manual reconnection action needed. Stage 0 remains incomplete.

## Live HR Unavailability and Reconnection — Tester Observation and Export

Archive: `diagnostic-exports/veymur-72cff27d-6947-419f-86e6-7d37dd0d51f2.zip`. This is a separate session from the earlier pause test; the archive is named after its metadata session_id. The user reports testing sensor power-off with the app open: the pulse disappeared after approximately 3 seconds and the status changed to SDK connection recovery. This is a tester estimate of UI behavior, not a measured staleness timeout; the archive does not timestamp UI updates or the power-off action.

- Samsung SM-A235F / Android 14, app 0.0.1, Polar SDK 8.4.0; exact APK build is not recorded. State `completed`, finish event present; active_ms 89,175 (1 min 29.175 s).
- 80 HR rows. Last HR before loss at 21.346 s relative to start; first HR after recovery at 71.459 s: a 50.113-second receipt gap, with no HR rows inside it. Two disconnected callbacks at 27.617/27.632 s are 15 ms apart and represent one observed loss episode. Connected event at 69.040 s; HR resumed 2.419 s later, with 36 subsequent HR rows saved in the same session.
- No pause, interrupted, activity-background, or screen-off events appear. GPS was disabled and no GPS rows were saved. The archive confirms persisted HR recovery but not whether the user took any manual reconnection action or how the recovered HR appeared on screen.
- ZIP CRC passed; metadata/CSV and payloads parse successfully. All 101 rows have consecutive sequence numbers 5,012–5,112. Phone battery is 88% at start and finish; no consumption conclusion is supported.

Checks: ran the existing analyzer's `analyze()` on this archive; independently checked CRC, sequences, loss/recovery events, the HR gap, and post-recovery HR counts with Python standard-library parsers. `python3 -m unittest discover -s apps/sensor-stage0/scripts -v` — all 4 tests passed; `git diff --check` — passed. Reproduce analyzer output locally with:

```bash
python3 apps/sensor-stage0/scripts/analyze_export.py diagnostic-exports/veymur-72cff27d-6947-419f-86e6-7d37dd0d51f2.zip
```

The live old HR becoming unavailable is confirmed by tester observation; sensor-loss gap and HR resumption within the same session are confirmed by the export. The actual receipt gap is approximately 50 seconds rather than a full minute; this still exercises loss/recovery. Next indoor scenario: reconnect after sensor power-off/on while leaving the phone locked, to verify recovery without returning to the Activity. Stage 0 remains incomplete.

## Notification Permission Denied — Screen-Locked HR Recording

Archive: `diagnostic-exports/veymur-b31d6c26-2454-444d-ad79-fdd7e0b646e4.zip`. The user reports denying Veymur notifications, recording HR with the phone locked, then finishing and exporting. Notification permission state is tester evidence: this diagnostic schema does not record it. Samsung SM-A235F / Android 14, app 0.0.1, Polar SDK 8.4.0; exact APK build is not recorded. Private identifiers, exact UTC timestamps, and HR values are omitted.

- State `completed`, finish event present; active_ms 213,181 (3 min 33.181 s). There are 426 HR rows, first/last receipt at 0.707/212.581 s relative to start.
- Screen off at 7.152 s and on at 204.421 s: 197.269 s (3 min 17.269 s). During this interval, 394 HR rows were saved; maximum adjacent receipt interval within it is 1.106 s. The user supplies the lock context; events measure display state.
- Maximum adjacent HR receipt interval across the session is 1.110 s; no intervals exceed the diagnostic 5-second threshold. No pause, disconnection, or interruption events occur.
- Route requested/enabled and gps_requested are false; no GPS rows. GPS recording under notification denial is not established by this run.
- ZIP CRC passed; metadata/CSV and payloads parse successfully. All 433 rows have consecutive sequence numbers 4,579–5,011, without missing or duplicate values. Phone battery is 88% at start and finish; this short run does not establish consumption.

Checks: ran the existing analyzer's `analyze()` on this archive and independently checked CRC, sequence continuity, completion, screen-off interval, HR counts, and receipt intervals with Python standard-library parsers. `python3 -m unittest discover -s apps/sensor-stage0/scripts -v` — all 4 tests passed; `git diff --check` — passed. Reproduce analyzer output locally with:

```bash
python3 apps/sensor-stage0/scripts/analyze_export.py diagnostic-exports/veymur-b31d6c26-2454-444d-ad79-fdd7e0b646e4.zip
```

Notification denial did not block local HR recording, persistence while locked, completion, or export in this tester-reported scenario. Bluetooth permission denial remains pending. Next short indoor test: observe the live HR display while powering the sensor off for about one minute, then back on; verify the old value becomes unavailable and a fresh value returns. Export afterward and report the observed screen behavior. Stage 0 remains incomplete.

## Location Permission Denial — UI and Recording Verification

The user reports enabling phone location after the previous test, then selecting “Record phone route.” The checkbox immediately cleared, a message explained that recording without location was possible, and Start remained available. No system permission dialog appeared again after the earlier “Don't allow” choice. This confirms the UI fallback under tester-reported location denial. The follow-up archive below confirms HR persistence and export under those reported conditions; permission state itself is not recorded in the diagnostic schema.

Follow-up archive: `diagnostic-exports/veymur-5559e1c2-a3b4-420a-ad0a-6aeb012ca30a.zip`, submitted to complete the denied-location-permission test. Samsung SM-A235F / Android 14, app 0.0.1, Polar SDK 8.4.0; exact APK build is not recorded.

- State `completed`, finish event present; active_ms 141,042 (2 min 21.042 s).
- 282 HR rows, no GPS rows. First/last HR receipt at 0.238/140.247 s relative to start; maximum adjacent interval 1.335 s; no HR receipt gaps over 5 seconds.
- Configuration route_requested=false, route_enabled=false; metadata gps_requested=false. The route selection was cleared before recording, consistent with the tester's UI observation. The ZIP alone does not establish the permission state or a route-requested-at-start fallback.
- ZIP CRC passed; metadata, CSV, and payloads parse successfully. All 285 rows have consecutive sequence numbers 4,294–4,578; no sequence gaps or duplicates within this session. No pause, interruption, or disconnection events. Phone battery is 89% at start and finish; no battery-consumption conclusion is supported.

Checks: ran the existing analyzer's `analyze()` on this archive; independently checked CRC, sequence continuity, completion, HR intervals, and absence of GPS using Python standard-library parsers. `python3 -m unittest discover -s apps/sensor-stage0/scripts -v` — all 4 tests passed; `git diff --check` — passed. Reproduce analyzer output locally with:

```bash
python3 apps/sensor-stage0/scripts/analyze_export.py diagnostic-exports/veymur-5559e1c2-a3b4-420a-ad0a-6aeb012ca30a.zip
```

Location-permission denial → HR-only recording → completion/export is confirmed using tester observations and this archive. Bluetooth and notification permission-denial tests remain pending. Next indoor test: deny Veymur notification permission, record HR with the screen locked for 2–3 minutes, finish, and export. Stage 0 remains incomplete.

Source inspection: the checkbox calls requestPermissions when precise location permission is absent; on denial, onRequestPermissionsResult clears it and displays the HR-only message. There is no implemented Settings shortcut for restoring permission when the system does not show a dialog. The precise Android permission state/reason for suppressing the dialog is not recorded and cannot be inferred from this observation alone. Restore access manually through Android Settings → Apps → Veymur → Permissions → Location, allowing access while using the app and precise location. A guided settings/retry action remains an interface limitation.

## Location Disabled Before Start — October 9, 2026

Archive: `diagnostic-exports/veymur-cd8a7002-a674-4a5f-b89f-cba5c13624ff.zip`. The user reports following the location-disabled-before-start procedure, keeping Bluetooth enabled and location disabled throughout. They observed that the route checkbox could not be selected again, the interface explained that recording without location was possible, and the Start button remained available. These UI/settings observations are tester evidence; they are not recorded in the ZIP.

- Samsung SM-A235F / Android 14, app 0.0.1, Polar SDK 8.4.0; exact APK build is not recorded. State `completed`, finish event present; active_ms 151,883 (2 min 31.883 s).
- 304 HR rows, no GPS rows. First/last HR receipt at 0.167/151.280 s relative to start; maximum adjacent interval 1.107 s; no HR receipt gaps over 5 seconds.
- Configuration has route_requested=false and route_enabled=false; metadata gps_requested=false. This confirms HR-only recording after the reported UI interaction, but does not establish a start command with route_requested=true falling back to HR. The attempted route selection and system location setting cannot be reconstructed from the archive.
- ZIP CRC passed; metadata/CSV and payloads parse successfully. All 307 rows have consecutive sequence numbers 3,976–4,282. No pause, disconnection, or interruption events. Phone battery changed 90% → 89%; this single short run does not establish recorder consumption.

Checks: ran the existing analyzer's `analyze()` on this archive; independently checked ZIP CRC, sequence continuity, configuration, stream counts, completion, and HR receipt intervals using Python standard-library parsers. `python3 -m unittest discover -s apps/sensor-stage0/scripts -v` — all 4 tests passed; `git diff --check` — passed. Reproduce analyzer output locally with:

```bash
python3 apps/sensor-stage0/scripts/analyze_export.py diagnostic-exports/veymur-cd8a7002-a674-4a5f-b89f-cba5c13624ff.zip
```

HR recording and export with location disabled before startup are confirmed using tester-reported settings and the saved session. The reported unavailable route checkbox is documented as observed behavior, without inferring its cause or whether it remained unavailable after location was re-enabled. Next indoor scenario: explicitly deny the app's location permission (a separate condition from switching off phone location), attempt route selection, and verify HR recording remains available. Stage 0 remains incomplete.

## Offline HR Recording and Export — October 9, 2026

Archive: `diagnostic-exports/veymur-01aa3286-d0ac-416a-b67c-066927f9a932.zip`. The user reports disabling Wi-Fi and mobile internet, leaving Bluetooth enabled, then recording and exporting. The archive confirms a readable completed HR session; network-disabled conditions are supplied by the tester, since this diagnostic schema does not record network state. Samsung SM-A235F / Android 14, app 0.0.1, Polar SDK 8.4.0; exact APK build is not recorded. Private identifiers, exact UTC timestamps, and HR values are omitted.

- State `completed`, finish event present; active_ms 310,094 (5 min 10.094 s). There are 620 HR rows, first/last receipt at 0.912/309.948 s relative to start.
- Maximum adjacent HR receipt interval is 4.934 s; no intervals exceed the diagnostic 5-second threshold. Median adjacent interval is 30 ms, reflecting batched receipt rather than hardware sampling frequency. These figures do not establish sensor accuracy or complete usable-time coverage.
- GPS was explicitly disabled (route requested/enabled and gps_requested are false); no GPS rows are present. Offline GPS acquisition and route recording remain unverified by this run.
- No pause, interruption, or disconnection events appear. Screen off/on at 300.973/301.674 s spans only 701 ms; this run does not add prolonged screen-lock evidence.
- ZIP CRC passed; metadata, CSV, and payloads parse successfully. All 635 rows have consecutive sequence numbers 3,341–3,975. Phone battery is 91% at start and finish; this short run does not establish battery consumption.

Checks: ran the existing analyzer's `analyze()` on this archive; independently checked CRC, sequences, completion, and HR intervals using Python standard-library parsers. `python3 -m unittest discover -s apps/sensor-stage0/scripts -v` — all 4 tests passed; `git diff --check` — passed. Reproduce analyzer output locally with:

```bash
python3 apps/sensor-stage0/scripts/analyze_export.py diagnostic-exports/veymur-01aa3286-d0ac-416a-b67c-066927f9a932.zip
```

The local HR recording → completion → export scenario without network access is confirmed using tester-reported network conditions and the exported session. Stage 0 remains incomplete. Next: with location enabled and precise location permission granted, record HR/GPS outdoors, pause for approximately 30 seconds, resume, then finish/export. Verify both streams persist before and after pause, neither persists during pause, and paused time is excluded. Permission-denial scenarios, GPS-enabled interruption recovery, and long-session battery/loss-window measurements remain pending.

## Confirmed HR Pause and Resume — October 9, 2026

Archive: `diagnostic-exports/veymur-5d64abbd-b203-47d9-9538-f47fa4413fd5.zip`. The user submitted the recording → pause → resume → finish/export scenario. Samsung SM-A235F / Android 14, app 0.0.1, Polar SDK 8.4.0; exact APK build is not recorded. Metadata state is `completed`. Relative event times and counts below omit HR values and private identifiers.

- Start at 0 s, pause at 31.097 s, resume at 115.009 s, finish at 149.076 s. The observed pause is **83.912 s**, rather than approximately 30 s; this still exercises exclusion of a paused interval.
- There are 130 HR rows: 62 before pause and 68 after resume, with **zero HR rows during pause**. Last pre-pause HR receipt is at 30.642 s; first post-resume receipt is at 115.627 s, 618 ms after resume. The analyzer reports no HR receipt gaps over 5 seconds within the active segments; it correctly excludes the deliberate pause from gap detection.
- Metadata and finish payload both report active_ms 65,151 (1 min 5.151 s). Event-timestamp arithmetic gives 149,076 − 83,912 = 65,164 ms, 13 ms above the clock's value. Source inspection shows clock changes precede journal event timestamp capture; this small difference is consistent with separate clock/event capture instants. It does not indicate inclusion of the 83.912-second pause. The export does not contain the exact internal clock-transition timestamps, so equality to the millisecond cannot be independently reconstructed.
- GPS was explicitly disabled (route requested/enabled and gps_requested are false). No GPS rows are present; pause/resume of GPS remains unverified on hardware.
- ZIP CRC passed; metadata/CSV and payloads are readable. All 135 rows have consecutive sequence numbers 3,206–3,340, without gaps or duplicates. No interruption or disconnection events occur. Phone battery changed from 92% to 91%; this single short run does not establish recorder battery consumption.

Checks: ran the existing analyzer's `analyze()` on this archive and independently checked ZIP CRC, sequences, pause/resume ordering, absence of observations during pause, HR segment counts, and event-derived active time with Python standard-library parsers. `python3 -m unittest discover -s apps/sensor-stage0/scripts -v` — all 4 tests passed; `git diff --check` — passed. Reproduce analyzer output locally with:

```bash
python3 apps/sensor-stage0/scripts/analyze_export.py diagnostic-exports/veymur-5d64abbd-b203-47d9-9538-f47fa4413fd5.zip
```

HR pause/resume and exclusion of the paused duration are confirmed for this run. Stage 0 and its full hardware task remain incomplete. Next: record and export with Wi-Fi and mobile data disabled, while keeping Bluetooth enabled; this verifies the local flow without network access. GPS-enabled pause/recovery, permission denial, and long-session battery/loss-window measurements remain pending.

## Confirmed Force-Stop Recovery — October 9, 2026

Archive: `diagnostic-exports/veymur-99317042-8153-4c45-8016-9867759e6c95.zip`. The user reports repeating the specified active recording → Android Settings force stop → relaunch → immediate export procedure. This archive confirms recovery of the persisted portion on Samsung SM-A235F / Android 14: metadata state is `interrupted`, the final event is `interrupted` with `reason=process_ended`, and there is no `finish` event. App 0.0.1, Polar SDK 8.4.0; exact APK build is not recorded. Times below are relative to session start; HR values and private identifiers are omitted.

- Saved active-time checkpoint: 16,009 ms. There are 32 HR rows, first/last receipt at 0.984/15.996 s; maximum adjacent interval 1.071 s, with no intervals over 5 seconds in the saved HR portion.
- Recovery event at 27.204 s, 11.208 s after the last saved HR. This is detection time on relaunch, not the exact force-stop time and not extra active recording time. No observations appear in that interval or after the recovery event in this export. Recording while the process was absent is not reconstructed.
- Route requested/enabled and metadata gps_requested are false; no GPS rows were expected. This test confirms HR recovery only, not recovery of a GPS-enabled session.
- Background events at 3.961 and 7.853 s and a foreground event at 5.713 s precede recovery. The force-stop action is supplied by the tester; the recovery event establishes an unfinished session was detected.
- ZIP CRC passed; metadata, CSV, and payloads parse successfully. All 38 rows have consecutive sequence numbers 3,168–3,205. This confirms readability and preservation of the available committed portion, but does not measure the loss window for any callback not committed before termination.

Checks: ran the existing analyzer's `analyze()` on this archive and independently verified CRC, sequences, final event, active-time checkpoint, and HR intervals using Python standard-library parsers. `python3 -m unittest discover -s apps/sensor-stage0/scripts -v` — all 4 tests passed; `git diff --check` — passed. Reproduce analyzer output locally with:

```bash
python3 apps/sensor-stage0/scripts/analyze_export.py diagnostic-exports/veymur-99317042-8153-4c45-8016-9867759e6c95.zip
```

The force-stop/relaunch recovery scenario is confirmed for this run. Stage 0 remains incomplete; the full hardware task in AGENTS.md is not marked complete. Next hardware scenario: record HR, pause for approximately 30 seconds, resume, then finish and export. Verify absence of HR/GPS observations during pause and exclusion of pause time from active_ms. Remaining work includes GPS-enabled interruption recovery, offline/permission scenarios, and long-session battery/loss-window measurements.

## Submitted Force-Stop Test — October 9, 2026

Archive: `diagnostic-exports/veymur-f240c2b8-76fd-4f4c-aaa9-aa8690af711d.zip`. The user submitted this as the force-stop → relaunch → export scenario. The exported evidence does **not** confirm interrupted recovery: metadata state is `completed`, a `finish` event is present, and there is no `interrupted` event. The exact sequence of user actions cannot be reconstructed from this file; it may be a completed session exported instead of the intended interrupted one. The hardware recovery scenario remains unverified.

- Samsung SM-A235F / Android 14, app 0.0.1, Polar SDK 8.4.0; exact APK build is not recorded.
- Active time: 167,873 ms (2 min 47.873 s); finish event at 167.881 s relative to start. There are 336 HR rows, with first/last receipt at 0.772/167.851 s, maximum adjacent interval 1.286 s, and no receipt gaps over 5 seconds.
- Two background/foreground pairs at 146.696/150.920 s and 158.214/164.485 s do not establish process termination. There are no screen-off, disconnected, pause, or interrupted events.
- Route requested/enabled are true, but no GPS rows were saved. The export does not explain the absence of location observations.
- ZIP CRC passed; JSON/CSV and payloads are readable. All 343 rows have consecutive sequence numbers 2,825–3,167. Phone battery is 92% at start and finish; no battery-consumption conclusion is supported.

Checks: ran the existing analyzer's `analyze()` on this archive; independently verified CRC, sequences, event timing, and HR intervals with Python standard-library parsers. `python3 -m unittest discover -s apps/sensor-stage0/scripts -v` — all 4 tests passed. Analyzer reproduction command:

```bash
python3 apps/sensor-stage0/scripts/analyze_export.py diagnostic-exports/veymur-f240c2b8-76fd-4f4c-aaa9-aa8690af711d.zip
```

Source inspection confirms that `finishOnWorker()` writes `finish` and marks a session `completed`; `Journal.recoverInterrupted()` appends `interrupted` and marks unfinished sessions accordingly. Export selects the latest session, so starting a new session after recovery can change which session is exported. Repeat the test with recording still active: use Android Settings → Apps → Veymur → Force stop, relaunch, wait for storage initialization, note the recovered-recordings counter, and immediately export without starting a new recording or pressing Finish. Removing the app from Recents alone is not the specified force-stop procedure. Do not mark the hardware recovery task complete on this archive.

## Ten-Minute Screen Lock and Sensor Reconnection — October 9, 2026

Archive: `diagnostic-exports/veymur-52e94749-9048-41d5-8ae8-88124469ead8.zip`. The user reports one 10-minute phone lock, then powering the sensor off for approximately one minute and checking reconnection. Samsung SM-A235F / Android 14, app 0.0.1, Polar SDK 8.4.0; firmware characteristic 00002a26: 0.1.6, software characteristic 00002a28: 3.0.16. The exact APK build is not recorded. All times below are relative to start; private identifiers, exact UTC timestamps, HR values, and coordinates are omitted.

- The session completed with a finish event and active time 702,396 ms (11 min 42.396 s). Export contains 1,266 HR rows and 318 GPS rows; route requested/enabled are both true. No pause or interruption events are present.
- Screen off at 12.424 s, screen on at 678.576 s: one interval of 666.152 s (11 min 6.152 s). Before the first disconnection at 622.833 s, recording persisted for 610.409 s (10 min 10.409 s) with the screen off, saving 1,210 HR rows. A total of 315 GPS rows were saved during screen-off. This confirms the ten-minute hardware scenario and encrypted-storage writes in this run. Screen events indicate display state; the user supplies the lock context.
- Last HR before loss: 617.083 s. Two disconnected callbacks were saved at 622.833 and 622.852 s (19 ms apart); these describe a single observed loss episode, not two separate outages. Connected event: 684.968 s, after the screen-on and app-foreground events. First HR after reconnection: 688.162 s (3.194 s after connected). The HR receipt gap is 71.079 s; 30 HR rows were saved after recovery. This matches the reported roughly one-minute sensor power-off. Exact power-on/off times and recovery while still locked are not established by the export.
- GPS remained independent: 34 GPS rows were saved between the last pre-loss HR and first post-recovery HR. GPS receipts span 48.695–683.675 s; the maximum adjacent receipt interval is 2.988 s. No GPS rows occur in the final 18.736 s before finish; the cause is unknown. Reported accuracy is 10.693–42.865 m (median 13.512 m). This confirms persistence alongside HR and through BLE loss, but does not establish route/distance accuracy or an outdoor route scenario.
- Seven additional HR receipt gaps over 5 seconds occur before sensor loss, all during screen-off: 18.091–23.879 s (5.788 s), 52.113–57.229 s (5.116 s), 115.114–120.585 s (5.471 s), 146.122–151.684 s (5.562 s), 159.075–164.468 s (5.393 s), 218.161–223.415 s (5.254 s), and 230.132–235.290 s (5.158 s). Their causes are unknown. HR receipts are batched (median adjacent interval 24 ms), so row count and receipt gaps do not directly establish hardware sampling frequency, measurement loss, or usable-time coverage. Gap-free HR delivery is not confirmed.
- No HR rows were saved between the last pre-loss and first recovered HR. The archive supports a visible data gap; it cannot prove that the live UI displayed stale HR as unavailable or that no stale value was shown.
- ZIP CRC check passed; metadata/CSV and all payloads parse successfully. All 1,611 rows have consecutive sequence numbers 1,214–2,824, with no missing or duplicate sequence values. Phone battery is 93% at start and finish; this short run does not establish battery consumption.

Verification: ran the existing analyzer's `analyze()` function on this archive and independently checked CRC, sequence continuity, lifecycle intervals, HR receipt gaps, and GPS intervals with Python standard-library parsers. `python3 -m unittest discover -s apps/sensor-stage0/scripts -v` — all 4 tests passed. Reproduce analyzer output locally with:

```bash
python3 apps/sensor-stage0/scripts/analyze_export.py diagnostic-exports/veymur-52e94749-9048-41d5-8ae8-88124469ead8.zip
```

Screen-off persistence beyond 10 minutes, sensor-loss data gap, reconnection followed by persisted HR, and independent GPS persistence during HR loss are confirmed for this run. Stage 0 remains incomplete: the remaining hardware matrix, recovery after force stop, battery measurements, and other target platforms are pending. Next: a separate force-stop → relaunch → export test; verify `interrupted` and preservation of the saved portion. Live stale-HR behavior should also be explicitly observed during a future sensor-loss test.

## Background and Screen-Off Hardware Export — October 8, 2026

Archive: `diagnostic-exports/veymur-2ef379f5-2341-4a3a-9573-f3d8d1b2afbd.zip`. The user reports starting recording, leaving the app in the background while using another app, returning, and locking the phone. Metadata: Samsung SM-A235F, Android 14, app 0.0.1, Polar SDK 8.4.0; firmware characteristic 00002a26: 0.1.6, software characteristic 00002a28: 3.0.16. The exact APK build is not recorded. Times below are relative to session start; HR values, coordinates, and device identifiers are withheld.

- State `completed`, with a finish event; active time 596,096 ms (9 min 56.096 s). There are 1,194 HR rows, no GPS rows, and no pause, interruption, or recorded disconnection events.
- The first background interval is 20.466–47.588 s. It contains 54 HR rows and confirms persistence while the user reports using another app. Lifecycle events alone do not identify the other app.
- Three screen-off intervals are 54.497–121.917 s, 152.958–410.577 s, and 423.136–588.731 s: 67.420, 257.619, and 165.595 s, totaling 490.634 s (8 min 10.634 s). Respectively 136, 514, and 330 HR rows were saved while the screen was off. This confirms real-device database writes during these intervals; it does not establish a single uninterrupted 10-minute locked-screen test. Screen events indicate display state, not a separate keyguard-unlock measurement.
- HR receipt gaps over the diagnostic 5-second threshold: 3.955–16.861 s (12.906 s, before the first background event), 21.949–27.791 s (5.842 s, during the first background interval), and 383.984–389.414 s (5.430 s, during the second screen-off interval). Their causes cannot be determined from this export. Background/screen-off recording is confirmed, but gap-free delivery is not.
- First/last HR receipt: 0.024/595.876 s. Median adjacent receipt interval: 28 ms, maximum: 12,906 ms. Batched receipt means row count is not the sensor sampling frequency, and receipt gaps alone do not prove loss of hardware measurements or establish usable-time coverage.
- Configuration has `route_requested=true`, `route_enabled=false`; metadata has `gps_requested=false`. The route was requested but not enabled for recording; the export does not explain why. GPS/background route behavior remains unverified.
- ZIP CRC check passed; JSON/CSV are readable. All 1,213 rows have consecutive sequence numbers 1–1,213. Phone battery is 61% at both start and finish; this short run and integer percentages do not establish battery consumption.

Verification from the platform root: the existing analyzer's `analyze()` function was run on this archive; independent standard-library `zipfile`, `csv`, `json`, and `statistics` checks verified CRCs, consecutive sequences, lifecycle intervals, HR counts, and receipt gaps. `python3 -m unittest discover -s apps/sensor-stage0/scripts -v` — all 4 tests passed. To reproduce the full analyzer output locally (it includes private metadata):

```bash
python3 apps/sensor-stage0/scripts/analyze_export.py diagnostic-exports/veymur-2ef379f5-2341-4a3a-9573-f3d8d1b2afbd.zip
```

Next: a single 10-minute locked-screen interval, followed by an explicitly timed 30-second sensor power-off and reconnection test. Check that HR becomes unavailable during loss and export the resulting gap. The full hardware task in AGENTS.md remains unfinished.

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

| Scenario                                              | Expected Result                                             | Actual Result                                                                                                                                                                                                                                                                                                                   |
| ----------------------------------------------------- | ----------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Discovery and explicit sensor selection               | Connection, fresh HR, selected device ID                    | Awaiting test                                                                                                                                                                                                                                                                                                                   |
| 1-minute recording with the screen on                 | HR is persisted; export is readable                         | Awaiting test                                                                                                                                                                                                                                                                                                                   |
| Screen locked for 10 minutes                          | New HR continues to be persisted; intervals are checked     | Confirmed October 9: 10 min 10.409 s screen-off before disconnection; HR receipt gaps documented above                                                                                                                                                                                                                          |
| Another app / background                              | The service continues local recording                       | Confirmed in October 8 export with reported use of another app; first 27.122 s interval contains 54 HR rows and a 5.842 s receipt gap                                                                                                                                                                                           |
| No Wi-Fi/mobile network                               | Recording and export remain available                       | Confirmed October 9 with tester-reported network disabled: completed 5 min 10.094 s HR session, readable export; GPS disabled                                                                                                                                                                                                   |
| Turn the sensor off for 30 seconds                    | HR becomes stale; the gap is visible; no data is fabricated | Confirmed: earlier 71.079 s gap; follow-up session 72cff27d has 50.113 s gap, and tester observed pulse disappear with SDK recovery message after about 3 s                                                                                                                                                                     |
| Turn the sensor back on                               | Connection recovers; a new portion is persisted             | Confirmed both after screen-on (52e94749) and while screen off (5bf1887e: 156 recovered HR rows before screen-on)                                                                                                                                                                                                               |
| GPS + HR outdoors                                     | Two independent streams in the export                       | Awaiting test                                                                                                                                                                                                                                                                                                                   |
| GPS disabled before startup                           | HR continues without a route                                | Confirmed October 9, cd8a7002: tester reports location off and HR-only message; completed HR session, no GPS; route_requested=false at start                                                                                                                                                                                    |
| GPS disabled during recording                         | HR continues; the GPS gap is visible                        | Awaiting test                                                                                                                                                                                                                                                                                                                   |
| Phone Bluetooth off/on during recording               | HR gap is preserved; connection and HR recover              | Confirmed in 08499a67: Bluetooth off 72.204 s; first recovered HR 7.360 s after enabled, no manual connection needed                                                                                                                                                                                                            |
| Bluetooth / location / notification permission denial | Clear status; HR does not require GPS or notifications      | Location denial confirmed with archive 5559e1c2 and tester observations; notification denial confirmed with b31d6c26 and tester report, including 3 min 17.269 s screen-off HR persistence. Bluetooth denial/retry confirmed by tester: deny Nearby devices → discovery message → restore permission → repeat discovery/connect |
| Pause for 30 s → resume                               | Pause is excluded from active_ms and streams                | Confirmed October 9 for HR: actual pause 83.912 s, zero HR rows during pause, active_ms 65,151; GPS disabled                                                                                                                                                                                                                    |
| Process termination → relaunch                        | The saved portion is detected as interrupted                | Confirmed October 9 in archive 99317042: interrupted/process_ended, 32 preserved HR rows, saved active_ms 16,009; GPS disabled                                                                                                                                                                                                  |
| Screen locking and reopening                          | SQLCipher/Keystore do not prevent persistence               | Confirmed October 9: HR/GPS persisted through screen-off beyond 10 minutes and after returning                                                                                                                                                                                                                                  |
| Long session of 1–2 hours                             | Battery consumption, gaps, and stability are measured       | Awaiting test                                                                                                                                                                                                                                                                                                                   |

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
