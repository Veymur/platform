# Veymur Sensor — Android diagnostic recorder

Migration stages 3–6 provide the Android host, migrated Kotlin core, command/state bridge and React Native diagnostic screen. `getModuleInfo` reports contractVersion=6/recorderImplemented=true; the screen supports discovery, explicit device selection, optional route, start/pause/resume/confirmed finish, session ZIP sharing and permission settings. Bluetooth is requested on search, location on enabling the route, and notification denial does not block recording. iOS remains unsupported. See the [diagnostic UI report](../../docs/recorder/recorder-diagnostic-ui.md) and [bridge contract](../../docs/recorder/recorder-bridge-contract.md).


Polar diagnostic stages D1–D2 now add explicit immutable SDK input events before recorder processing, separate receipt/worker timing, all 16 base callback signatures and unfiltered discovery results. D3 provides the native encrypted journal and D4 adds explicit capture controls; D5 adds capture export and deletion: the current Export last session button still produces the existing session ZIP. The native test sink is disabled by default. See the [input contract](../../docs/diagnostic/polar-diagnostic-contract.md) and [implementation checks](../../docs/diagnostic/polar-diagnostic-implementation.md). Rebuild native after these changes; existing stage-8 APKs do not contain this boundary.

D3 adds `DiagnosticJournal`/`DiagnosticCapture` with a separate SQLCipher database, Keystore wrapping key and writer thread, bounded non-blocking offers, atomic checkpoints, explicit losses/limits, interrupted recovery and native deletion. Capture remains off until explicitly enabled. D4 exposes `startDiagnostic`/`stopDiagnostic` and a compact status through bridge v5; JS never receives raw callbacks. Capture owns the foreground service even without a session. Finish keeps capture active; stopping capture leaves a session active. Stop acknowledges the drained, committed queue or returns `DIAGNOSTIC_ERROR`. Capture does not start discovery or initialize Polar. Reopening detects interrupted captures without resuming them. See [D4 checks and limits](../../docs/diagnostic/polar-diagnostic-d4-implementation.md). The provisional 64-record/250-ms commit policy has emulator measurements; Galaxy A23 measurements remain pending. See [D3 behavior, commands, measurements and limitations](../../docs/diagnostic/polar-diagnostic-d3-implementation.md).

D5 adds a native streaming capture ZIP exporter, capture list/selection, confirmed deletion and a 24-hour cache export TTL. Stop active capture explicitly before export. The ZIP is **unencrypted** and contains HR/device identifiers; use the system share dialog to choose where to save it. This is separate from Export last session. Rebuild for bridge v5/core `stage0-777a33b-polar-replay-d7`. See [D5 behavior, verification and limits](../../docs/diagnostic/polar-diagnostic-d5-implementation.md).

D7 adds deterministic native replay tests using the live Polar processor and session control, virtual UTC/monotonic time, an in-memory transaction adapter and separately authored expectations. Capture now includes `start/pause/resume/finish` command acceptance and terminal results. Bridge remains v5; rebuild for core `stage0-777a33b-polar-replay-d7`. Old captures retain their incomplete replay context; see [replay commands and limits](diagnostics/fixtures/README.md#native-deterministic-replay) and [D7 verification](../../docs/diagnostic/polar-diagnostic-d7-implementation.md).

Validate a copied capture ZIP independently on your computer with Python (standard library only):

```bash
python3 scripts/validate_polar_diagnostic.py /path/to/capture.zip
npm run test:diagnostics
```

Exit 0 means the archive is structurally valid; read `state`, losses and `unknown_tail` in the JSON report to determine completeness. `replay_ready:false` remains explicit until D6–D7. Unknown schema, checksum errors, broken references or malformed ZIP return exit 1. Raw data is not printed or uploaded.

D6 adds `scripts/prepare_polar_fixture.py` and a [reviewed synthetic fixture catalogue](diagnostics/fixtures/README.md), with source SHA-256, explicit prefix context, consistent pseudonyms, UTC policy and separately authored expectations. Hardware fixtures remain pending. The tool never changes the original ZIP, overwrites output or stages data in Git. Native code/bridge remain D5/v5.

```bash
python3 scripts/prepare_polar_fixture.py prepare /private/capture.zip --kind synthetic --scenario hr-gap --output /private/hr-gap.fixture.json
python3 scripts/prepare_polar_fixture.py check /private/hr-gap.fixture.json --source /private/capture.zip
```

D6 verifies fixture preparation; the native replay engine and its expected-result assertions remain D7. See [D6 commands, results and limits](../../docs/diagnostic/polar-diagnostic-d6-implementation.md).

The Stage 0 recorder and stage 2 dependency probe remain separately installed/testable:

| App | Android applicationId |
|---|---|
| Kotlin recorder | `com.veymur.stage0` |
| Dependency probe | `com.veymur.sensor.bridgeprobe` |
| This RN app | `com.veymur.sensor` |

Their databases, preferences and Keystore scopes are separate. No automatic data migration or ZIP import is included.

## Tools and installation

Node **22.22.2**, npm **10.9.7**, a full JDK **21** (with `javac`), Android SDK Platform **36**, Build Tools **36.0.0**, platform-tools, NDK **27.1.12297006**, CMake **3.22.1**. The selected Gradle is **9.3.1**, Kotlin compiler **2.3.20**, AGP **8.12.0**; the recorder module targets Java 17 bytecode. npm/Maven/Google/JitPack and Gradle downloads need network access. No Expo/EAS account is required for these local builds.

Versions inherited from the stage 2 probe: Expo **57.0.28**, RN **0.86.3**, React **19.2.3**, expo-dev-client **57.0.20**, expo-build-properties **57.0.22**. Native dependencies Polar **8.4.0**, SQLCipher **4.6.1** and androidx.sqlite **2.4.0** are used by the migrated native core. Effective Android minimum is **API 33 / Android 13**; compile/target SDK is 36. Checked build ABIs are arm64-v8a and x86_64. See [compatibility](../../docs/compatibility.md) for sources and remaining platform limitations.

Configure `JAVA_HOME` and `ANDROID_HOME`, and add platform-tools to PATH. On the validation machine:

```bash
export JAVA_HOME="$HOME/.cache/veymur-stage0/jdk-21"
export ANDROID_HOME="$HOME/.cache/veymur-stage0/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
```

From `apps/sensor`:

```bash
npm ci
npm run typecheck
npm run lint
npm test
npx --no-install expo install --check
npm run prebuild:android
npm run build:android
npm run lint:android
```

`lint` checks TS/TSX and JS/config/tests with ESLint; `lint:android` checks our native module, not all Expo/RN dependencies. The nineteen Node tests verify platform/binary guards, DTO validation, subscription ordering, commit waiting and error propagation using mocked bindings; they do not simulate an iPhone or execute Kotlin. The actual Kotlin/JS integration is verified by installing the APK and inspecting its response.

APK: `android/app/build/outputs/apk/debug/app-debug.apk`. The debug key is local and is not a production signing configuration. Native versions and toolchain settings are preserved in source; `package-lock.json` v3 pins the npm graph. Do not replace the lockfile with `npm update` as part of a routine build.

## Launch the development build

Start an Android emulator or an authorized phone; find its serial with `adb devices -l`. In another terminal, from `apps/sensor`:

```bash
npm run start -- --host localhost
```

Install/run on the API 34 test emulator (replace the serial for a different device):

```bash
adb -s emulator-5554 install -r android/app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 reverse tcp:8081 tcp:8081
adb -s emulator-5554 shell am start -W -a android.intent.action.VIEW \
  -d 'exp+veymur-sensor://expo-development-client/?url=http%3A%2F%2F127.0.0.1%3A8081' \
  com.veymur.sensor
adb -s emulator-5554 logcat -d -s ReactNativeJS:I '*:S'
```

The current screen is “Veymur · recording validation”: storage becomes ready, and a disconnected sensor shows “No current heart rate”. The typed handshake requires contractVersion=6/recorderImplemented=true. The earlier stage-3 placeholder and RECORDER_MODULE_OK console checks are historical, not current launch criteria. On the first development-client launch, dismiss Expo's developer-menu introduction to reach the app.

An incompatible native binary produces a rebuild/reinstall error. Expo Go cannot load `VeymurRecorder`. Native Kotlin, dependencies, registration, permissions or plugin/config changes require prebuild and a new installation. JS reload updates only JS. This **debug development APK** uses Metro; it supports the JS reload scenarios. It is separate from the autonomous diagnostic variant below.

## D8 hardware validation

The [H01–H07 protocol](../../docs/diagnostic/polar-diagnostic-d8-hardware-protocol.md) covers Galaxy A23 / Android 14 and Polar Verity Sense capture/session comparison, locking, JS detach, Bluetooth recovery, offline operation, interruption and paired battery/load measurements. The [D8 preparation report](../../docs/diagnostic/polar-diagnostic-d8-implementation.md) records the offline APK hash and actual results. Hardware validation is pending device access; emulator results do not close it. Preserve an existing installation and verify its signing certificate before replacing it.

## Autonomous diagnostic APK — embedded JS

After installing dependencies and generating Android, run:

```bash
npm run build:android:offline
npm run verify:android:offline
```

`build:android:offline` runs the **release Gradle variant** with NODE_ENV=production. Expo's generated `export:embed` task and Hermes compiler package `assets/index.android.bundle` inside the APK. Building requires dependency downloads/network access when caches are missing; “offline” describes app startup/runtime, not the build process. No running Metro, EAS account or backend is needed to launch this variant.

Output: `android/app/build/outputs/apk/release/app-release.apk`. This is an **internal diagnostic APK signed with the Expo template debug key**, not a production-signed/store release. It keeps `com.veymur.sensor`, the native core, SQL/ZIP schema and database sandbox. Before replacing an existing installation, verify the signing certificate matches; a mismatch is a reason to preserve/export data and resolve signing, not uninstall the app automatically. Debug and autonomous variants cannot be installed side by side with this applicationId; the original Kotlin prototype remains separately installed.

`verify:android:offline` uses Python 3, ANDROID_HOME/ANDROID_SDK_ROOT and the pinned Build Tools 36.0.0. It checks the APK ZIP CRC, non-empty bundle, native core/service/provider, applicationId, non-debuggable flag, API 33/36, both ABIs and cryptographic signature, and prints APK/bundle/certificate hashes. This is package inspection; actual offline startup is verified separately.

Install/launch on the dedicated emulator (substitute an authorized phone's serial when doing hardware validation):

```bash
adb -s emulator-5554 install -r android/app/build/outputs/apk/release/app-release.apk
adb -s emulator-5554 shell am start -W -n com.veymur.sensor/.MainActivity
```

For an emulator offline-start check: stop the Metro process started for this project, remove its ADB reverse with `adb -s emulator-5554 reverse --remove tcp:8081`, disable emulator Wi-Fi/mobile data, force-stop and launch through MainActivity. Verify the diagnostic screen, ready storage and native error handling; restore the prior emulator network state afterwards. Do not use force-stop/clear-data as a cleanup action on a phone with an active recording. Development tools and JS reload are unavailable in this autonomous variant, so reload tests use the development APK; offline/battery tests use this one.

## Recording, export and interrupted recovery

- Find Verity Sense, allow Bluetooth and select its device ID explicitly. Sensor connection is confirmed by the native status/ID, not command acceptance.
- Enable Record phone route only when wanted. Denying precise location permits HR-only recording; unavailable location/provider at start also degrades to HR-only.
- Start recording; optional notification denial does not block HR. Pause/resume use separate native commands. Finish requires confirmation and reports saved only after commit. A failed finish exposes retry saving and preserves its session ID.
- Export last session creates a schema-1 diagnostic ZIP and opens Android sharing with a scoped read URI. The chooser is not proof a recipient saved the file. The ZIP can include precise coordinates; choose the destination deliberately.
- After interrupted process termination, reopening the service marks the available unfinished portion interrupted and shows the recovered count. Export reads the latest available session. No continuity after force-stop/reboot, automatic stream resumption, old-prototype data import or full history UI is promised.

Analyze an exported ZIP from the platform root with `python3 apps/sensor-stage0/scripts/analyze_export.py /path/to/export.zip`. Counts, pauses, HR/GPS gaps and receipt timestamps are diagnostic facts; no zero filling or medical interpretation is added.

Current build identities, exact checks and open hardware criteria: [preliminary stage-8 report](../../docs/android-migration-delivery.md). Stage 7 is deferred by the user; stage 8 is not fully complete until the physical-device comparison is done.

## Source ownership and clean regeneration

- `app.config.ts` defines the host identity and SDK settings.
- `modules/veymur-recorder/expo-module.config.json` declares Android module registration; `android/src/main/java/.../RecorderModule.kt` owns native commands, binding and state events.
- `modules/veymur-recorder/src/index.ts` exposes native access and the versioned handshake; `src/types.ts` defines commands/DTOs. `src/recorder/RecorderClient.ts` validates snapshots and orders subscriptions.
- `modules/veymur-recorder/plugin/withVeymurRecorder.cjs` pins the Kotlin compiler classpath, Gradle checksum and ABIs. Kotlin must remain consistent with build-properties. The plugin fails when the expected template/wrapper changes, requiring renewed validation.
- Root `android/` and `ios/` are generated and ignored. The **local module's** `modules/veymur-recorder/android/` contains source and belongs in Git. Do not store the only copy of native changes in the generated host.

To test regeneration after committing the source, create a separate checkout from the platform root:

```bash
SENSOR_CHECKOUT="$(mktemp -d /tmp/veymur-sensor-checkout.XXXXXX)"
git worktree add --detach "$SENSOR_CHECKOUT" HEAD
cd "$SENSOR_CHECKOUT/apps/sensor"
npm ci
npm run typecheck
npm run lint
npm test
npm run prebuild:android
npm run build:android
npm run build:android:offline
npm run verify:android:offline
npm run lint:android
```

Start with no generated host or build outputs. Inspect `node_modules/expo/android/build/generated/expo/src/main/java/expo/modules/ExpoModulesPackageList.kt` for `com.veymur.recorder.RecorderModule`, the generated Gradle compiler pin, wrapper checksum and the APK min/target SDK. Install the regenerated debug APK and verify the current diagnostic screen/native-ready state. Install the regenerated autonomous APK and repeat the cold launch without Metro/network. `prebuild --clean` is permitted only in the isolated test checkout after checking that it contains no unsaved native edits; do not use it over user changes in the working project.

## Verification and scope

### Native core — migration stage 4

`modules/veymur-recorder/android/src/main/java/com/veymur/recorder/core/` contains `RecordingClock`, `Journal` and `RecorderService`. The service remains the production database writer: Polar/GPS callbacks enqueue work on its HandlerThread; JS is not involved in persistence. Core source imports no Expo/React APIs. At stage 4, changes from the old core were limited to Kotlin package names and looking up the host launcher Intent instead of referring to the prototype MainActivity. Foreground types, notification channel, wake lock, stream handling and resource cleanup remain as in the baseline.

At the original Android migration delivery, database tables, user_version=1, key wrapping and diagnostic ZIP schema_version=1 were unchanged; subsequent local-data work adds SQL migrations through v3 (see Local history API below). Keystore aliases and filenames remain scoped to the new applicationId. At stage 4, finalization/recovery used separate SQL operations; stage 5 makes transitions transactional (see the bridge contract). Checkpoint frequency and callback loss-window limitations still require hardware measurements.

The module manifest declares the private service, Bluetooth/location/notification/foreground/wake-lock permissions and `${applicationId}.exports` FileProvider. XML resources preserve export scope and backup exclusions; app config/plugin disable backup and bind data-extraction rules to the host. Core identity is embedded in APK manifest metadata `com.veymur.recorder.CORE_VERSION=stage0-777a33b-polar-replay-d7`; bridge contract is version 6. ZIP metadata/CSV were not changed to add this identity.

Run storage/clock checks on a **dedicated test emulator** with the environment above:

```bash
npm run prebuild:android
npm run test:android:unit
npm run test:android:device
npm run lint:android
```

Clock JVM tests live in the local module's android/src/test. Host instrumentation source lives in `modules/veymur-recorder/tests/android/`; the config plugin copies it into generated `android/app/src/androidTest/java` and configures the runner/dependencies. This tests the actual `com.veymur.sensor` sandbox, not a library-only test app. Tests use separate instrumentation-prefixed databases/preferences, not the production journal. Stage 5 service tests additionally bind/start a real foreground service with isolated storage and synthetic connected state; no sensor is connected. Run them on a fresh dedicated host without an existing RN subscription.

Verification on Android 14: 2 clock tests and 6 host instrumentation tests passed, covering SQLCipher header/Keystore reopen, completed/interrupted recovery, append rollback after injected SQL failure, synthetic HR/GPS with pause/resume/finish, ZIP readback, FileProvider scope, private service/permissions, core identity and backup protection. Synthetic active time uses a controlled clock; these tests do not validate real sensor callbacks, stream suppression on pause, lock-screen recording or abrupt process death. Native lint: 0 errors, 6 warnings.

The observed Gradle connected-test cleanup uninstalls the target app and removes its files. Use a test emulator rather than an installation containing recordings. To retain synthetic ZIPs for independent analysis, install/run the already-built test APK directly after the Gradle run:

```bash
adb -s emulator-5554 install -r android/app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w -r \
  com.veymur.sensor.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5554 pull /sdcard/Android/data/com.veymur.sensor/files/stage4-synthetic-completed.zip /tmp/stage4-completed.zip
adb -s emulator-5554 pull /sdcard/Android/data/com.veymur.sensor/files/stage4-synthetic-interrupted.zip /tmp/stage4-interrupted.zip
python3 ../sensor-stage0/scripts/analyze_export.py /tmp/stage4-completed.zip
python3 ../sensor-stage0/scripts/analyze_export.py /tmp/stage4-interrupted.zip
```

Both ZIPs passed the unchanged Python analyzer and CRC checks. Full stage 4 commands, main/clean APK hashes, synthetic results and limits: [native core report](../../docs/stage-0/react-native-core-migration-validation.ru.md).

Commands/results, APK identities and clean-checkout details are recorded in the [stage 3 report](../../docs/stage-0/react-native-minimal-app-validation.ru.md) and [recorder validation](../../docs/recorder-validation.md). Migration reports under `docs/stage-0/` and local APK/log artifacts are ignored by Git according to repository preferences.

Known stage 2 limitations remain: deprecated APIs in Expo/RN, npm audit findings in the inherited tooling graph, and the Linux RN DevTools SUID configuration issue. These do not constitute release approval. No physical Galaxy A23, real BLE/GPS, sustained foreground recording, offline bundle, battery, 16 KB runtime or Swift/iOS recorder validation is claimed here. Keystore/storage recovery has emulator evidence only. Stage 5 now implements commands, commit acknowledgments and snapshots; final checks are in [recorder validation](../../docs/recorder-validation.md). Stage 6 now provides diagnostic controls/permissions; [UI verification](../../docs/recorder/recorder-diagnostic-ui.md) documents scope. Next: migration stage 7, compare this RN recorder on the real Galaxy A23/Polar sensor, including a locally bundled offline APK.

### Local history API

`LocalDataClient` provides paginated `listSessions`, `getSessionDetails`, bounded `readStream`, `getPreferences`, revision-checked `saveSettings`/`saveRanges`, `deleteSession` and `pendingDeletions`. All commands use the native writer through bridge v6; no account/network or screen lifecycle is required. SQL schema v3 adds a durable file-cleanup queue. Details require explicit aggregate parameters; unavailable metrics stay null. Selected ranges are snapshotted at session start. Recording/paused/finishing sessions cannot be deleted. A pending cleanup is visible and retryable with the same session ID, including after restart. Shared copies remain outside this operation. Product history screens, CSV/GPX, and deletion confirmation UI remain separate work. See [local operations contract and verification](../../docs/local-data-operations.md).
