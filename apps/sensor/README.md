# Veymur Sensor — minimal React Native app

Migration stages 3–4 provide the Android host, a local Expo Module and the migrated Kotlin recording core. The development screen still verifies only a typed JS → Kotlin response: **recording commands are not exposed to JS yet**. `getModuleInfo` does not open a database or start the service; recorderImplemented=false describes this intermediate public API. iOS has an explicit unsupported-platform message and rejects module calls before loading Android code.

The Stage 0 recorder and stage 2 dependency probe remain separately installed/testable:

| App | Android applicationId |
|---|---|
| Kotlin recorder | `com.veymur.stage0` |
| Dependency probe | `com.veymur.sensor.bridgeprobe` |
| This RN app | `com.veymur.sensor` |

Their databases, preferences and Keystore scopes are separate. No automatic data migration or ZIP import is included.

## Tools and installation

Node **22.22.2**, npm **10.9.7**, a full JDK **21** (with `javac`), Android SDK Platform **36**, Build Tools **36.0.0**, platform-tools, NDK **27.1.12297006**, CMake **3.22.1**. The selected Gradle is **9.3.1**, Kotlin compiler **2.3.20**, AGP **8.12.0**; the recorder module targets Java 17 bytecode. npm/Maven/Google/JitPack and Gradle downloads need network access. No Expo/EAS account is required for these local builds.

Versions inherited from the stage 2 probe: Expo **57.0.28**, RN **0.86.3**, React **19.2.3**, expo-dev-client **57.0.20**, expo-build-properties **57.0.22**. Native dependencies Polar **8.4.0**, SQLCipher **4.6.1** and androidx.sqlite **2.4.0** are included for the next core migration. Effective Android minimum is **API 33 / Android 13**; compile/target SDK is 36. Checked build ABIs are arm64-v8a and x86_64. See [compatibility](../../docs/compatibility.md) for sources and remaining platform limitations.

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

`lint` checks TS/TSX and JS/config/tests with ESLint; `lint:android` checks our native module, not all Expo/RN dependencies. The four Node tests verify the public wrapper's platform and binary compatibility guards using mocked RN/Expo bindings; they do not simulate an iPhone or execute Kotlin. The actual Kotlin/JS integration is verified by installing the APK and inspecting its response.

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

The screen should show “Android native module connected” and “Recording is not available in this build.” The JS log must contain:

```text
RECORDER_MODULE_OK {"moduleName":"VeymurRecorder","platform":"android","contractVersion":1,"recorderImplemented":false}
```

An outdated/incompatible native response produces an error asking to rebuild/install the development binary. Expo Go cannot load `VeymurRecorder`. Native Kotlin, dependencies, module registration, permissions or plugin/config changes require prebuild, a new APK and installation. JS reload updates only the JS code. This development APK loads its JS through Metro; it is not the offline recording build required by later stages.

## Source ownership and clean regeneration

- `app.config.ts` defines the host identity and SDK settings.
- `modules/veymur-recorder/expo-module.config.json` declares Android module registration; `android/src/main/java/.../RecorderModule.kt` owns the native handshake.
- `modules/veymur-recorder/src/index.ts` exposes `getModuleInfo()` and validates the native DTO; `src/types.ts` defines its public type. Recording commands are a later contract.
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
npm run lint:android
```

Start with no generated host or build outputs. Inspect `node_modules/expo/android/build/generated/expo/src/main/java/expo/modules/ExpoModulesPackageList.kt` for `com.veymur.recorder.RecorderModule`, the generated Gradle compiler pin, wrapper checksum and the APK min/target SDK. Install the regenerated APK and repeat the JS→Kotlin handshake. `prebuild --clean` is permitted only in the isolated test checkout after checking that it contains no unsaved native edits; do not use it over user changes in the working project.

## Verification and scope

### Native core — migration stage 4

`modules/veymur-recorder/android/src/main/java/com/veymur/recorder/core/` contains `RecordingClock`, `Journal` and `RecorderService`. The service remains the production database writer: Polar/GPS callbacks enqueue work on its HandlerThread; JS is not involved in persistence. Core source imports no Expo/React APIs. Changes from the old core are limited to Kotlin package names and looking up the host launcher Intent instead of referring to the prototype MainActivity. Foreground types, notification channel, wake lock, stream handling and resource cleanup remain as in the baseline.

Database tables, user_version=1, key wrapping and diagnostic ZIP schema_version=1 are unchanged; no schema migration or old-data import was introduced. Keystore aliases and filenames remain scoped to the new applicationId. Session finalization/recovery still use multiple SQL operations; error handling, checkpoints and callback loss-window limitations from the baseline require separate work.

The module manifest declares the private service, Bluetooth/location/notification/foreground/wake-lock permissions and `${applicationId}.exports` FileProvider. XML resources preserve export scope and backup exclusions; app config/plugin disable backup and bind data-extraction rules to the host. Core identity is embedded in APK manifest metadata `com.veymur.recorder.CORE_VERSION=stage0-777a33b-host-v1`. ZIP metadata/CSV were not changed to add this identity.

Run storage/clock checks on a **dedicated test emulator** with the environment above:

```bash
npm run prebuild:android
npm run test:android:unit
npm run test:android:device
npm run lint:android
```

Clock JVM tests live in the local module's android/src/test. Host instrumentation source lives in `modules/veymur-recorder/tests/android/`; the config plugin copies it into generated `android/app/src/androidTest/java` and configures the runner/dependencies. This tests the actual `com.veymur.sensor` sandbox, not a library-only test app. Tests use separate instrumentation-prefixed databases/preferences, not the production journal. The tests do not bind/start RecorderService or connect a sensor.

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

Known stage 2 limitations remain: deprecated APIs in Expo/RN, npm audit findings in the inherited tooling graph, and the Linux RN DevTools SUID configuration issue. These do not constitute release approval. No physical Galaxy A23, BLE/GPS, foreground recording, offline bundle, battery, 16 KB runtime or Swift/iOS recorder validation is claimed here. Keystore/storage recovery has emulator evidence only. Next: migration stage 5, implement commands, commit acknowledgments and recorder snapshots at the JS/native boundary.
