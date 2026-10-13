# Android native bridge probe (migration stage 2)

This is a disposable dependency/registration experiment, separate from the Stage 0 recorder and the future `apps/sensor`. It uses a local **Expo Module**, Expo development client and the New Architecture. It does not connect a sensor, request recording permissions, run a foreground service, or access the Stage 0 journal.

The test screen calls Kotlin through JS. Kotlin loads Polar's version API and the native SQLCipher library, writes/reads a synthetic value in a temporary encrypted database, checks the file header, closes/deletes the database and returns serializable facts to JS. JS validates the result and displays/logs `PROBE_OK`. A failure displays/logs `PROBE_FAILED`; iOS is explicitly unsupported.

## Prerequisites

Node 22.22.2 and npm 10.9.7; a full JDK 21 with `javac`; Android SDK Platform 36 and Build Tools 36.0.0; platform-tools; NDK 27.1.12297006 and CMake (Gradle can install these using already accepted SDK licenses). Internet access is needed for npm, Google/Maven Central/JitPack and the Gradle distribution. No Expo account or EAS is required. See [compatibility](../../docs/compatibility.md) for the selected versions and official sources.

Set `JAVA_HOME` to the JDK, `ANDROID_HOME` to the SDK and add `$ANDROID_HOME/platform-tools` to `PATH`. On the validation machine:

```bash
export JAVA_HOME="$HOME/.cache/veymur-stage0/jdk-21"
export ANDROID_HOME="$HOME/.cache/veymur-stage0/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
```

From this directory:

```bash
npm ci
npm run typecheck
npx --no-install expo install --check
npm run prebuild:android
npm run build:android
```

`android/` is generated and ignored. The local module/config plugins are the source of truth. The config pins min/compile/target SDK, Kotlin compiler, the Gradle distribution checksum and the two tested build ABIs (`arm64-v8a,x86_64`). Package versions are exact, npm lockfile v3 pins the JS graph. Kotlin must be pinned both in build-properties and the host compiler classpath: changing only the former produced a compiler/metadata mismatch in the experiment. Keep them in sync when upgrading. `plugins/withProbeBuild.cjs` fails if the expected template/wrapper changes, requiring renewed validation.

APK: `android/app/build/outputs/apk/debug/app-debug.apk`; applicationId `com.veymur.sensor.bridgeprobe`. It is distinct from `com.veymur.stage0`; installing the probe does not replace the recorder. The debug signing key is local.

## Run the registration check

Start an Android emulator or connect an authorized phone. Use an explicit serial if several devices are connected. In a separate terminal:

```bash
npm run start -- --host localhost
```

Then, from this directory (replace the serial for a physical device):

```bash
adb -s emulator-5554 install -r android/app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 reverse tcp:8081 tcp:8081
adb -s emulator-5554 shell am start -W -a android.intent.action.VIEW \
  -d 'exp+veymur-sensor-bridge-probe://expo-development-client/?url=http%3A%2F%2F127.0.0.1%3A8081' \
  com.veymur.sensor.bridgeprobe
adb -s emulator-5554 logcat -d -s ReactNativeJS:I '*:S'
```

Check `PROBE_OK`, module `VeymurBridgeProbe`, Polar `8.4.0`, SQLCipher `4.6.1 community`, encryptedRoundTrip `true`, and the actual ABI/API. Native registration is also visible in Gradle's `Using expo modules` output and the generated `node_modules/expo/android/build/generated/expo/src/main/java/expo/modules/ExpoModulesPackageList.kt`.

Native dependencies, Kotlin files, module registration, permissions, SDK/toolchain configuration and config plugins require a new prebuild/build and APK installation. Restarting Metro or JS reload cannot update native code. JS-only edits use Metro. **Expo Go cannot run this module.**

This development client depends on Metro for JS. Successful launch is not an offline-product test, not BLE/foreground recording validation, and not confirmation of arm64 device operation or 16 KB runtime support. Do not add recorder functionality to this probe: stage 3 will move the verified setup into `apps/sensor`, and stages 4–7 will integrate/test recording. [Validation report](../../docs/react-native-bridge-validation.ru.md).
