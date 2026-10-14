# Android migration delivery — preliminary stage 8

**Status: software preparation verified; migration stage 8 remains open.** The user deferred physical-device stage 7 and explicitly authorized this preliminary delivery. Real Android recording comparison, iOS and MVP-A completion are not claimed. Prepared October 14, 2026 from base commit `48936f6` plus the candidate stage-8 build scripts/documentation.

## Delivered behavior and source ownership

`apps/sensor` contains the diagnostic RN screen and a local Expo module with contract version 3/core `stage0-777a33b-diagnostic-v3`. The native HandlerThread owns Journal/RecorderSession writes, stream handling and commit acknowledgment. JS provides commands/state UI, permissions and sharing. Neither screen unmount nor module detach finishes a started recorder. Database/ZIP schema 1, sandbox `com.veymur.sensor`, keys, ABI/toolchain versions and original Kotlin prototype are preserved. Stage 8 changes build/inspection commands and documentation only; it does not modify recorder logic.

`npm run build:android:offline` builds Gradle's release variant with embedded Expo/Hermes JS; `npm run verify:android:offline` runs the new `scripts/inspect-offline-apk.py`. The generated release signing configuration uses the Expo template debug key. These APKs are internal diagnostic builds, not production-signed/store releases. No secrets or production signing credentials were introduced. Release/local embedding follows [Expo local release guidance](https://docs.expo.dev/guides/local-app-production/) and the generated Expo `export:embed` Gradle configuration; production signing is a separate [Android/RN release requirement](https://reactnative.dev/docs/0.86/signed-apk-android).

Debug/Metro and autonomous APKs use the same applicationId and matching signing certificate and therefore replace one another; install with `-r` when signatures match. Do not resolve a signature mismatch by deleting user data. The Kotlin prototype `com.veymur.stage0` and dependency probe `com.veymur.sensor.bridgeprobe` remain separate. The debug APK is for development/JS reload checks; the non-debuggable autonomous APK is for offline/battery scenarios without active development tools.

## APK identity register

All current artifacts below contain core `stage0-777a33b-diagnostic-v3`, contract 3, min SDK 33/target 36, arm64-v8a/x86_64. Local artifact directories are ignored by Git.

| APK | SHA-256 | Scope |
|---|---|---|
| `artifacts/stage6/veymur-sensor-diagnostic-debug.apk` | `fcb551b4e3f92d41f06a59b3ab0e6561c18a94bc433875316be0e22f65c1d08b` | Historical stage-6 development build; requires Metro |
| `artifacts/stage8/veymur-sensor-development-clean.apk` | `d10983dfbf24755ef0ce4c9a48e685f180d0c2b370c097edefbb78f404756d15` | Regenerated development build and native tests |
| `artifacts/stage8/veymur-sensor-offline-main.apk` | `7394d19d447c960a4b97f54852d4497f7712191652a14902b03162726862691e` | Main checkout, embedded JS, emulator offline startup |
| **`artifacts/stage8/veymur-sensor-offline.apk`** | **`265190310f3b06f4c4d7763e253518041400714e6602cd3c5187a1ec84ab9c5f`** | **Final diagnostic artifact from clean checkout, embedded JS, emulator offline startup** |

Both autonomous builds contain the same 1,183,252-byte bundle, SHA-256 `12fdac1f7efa4783f4f154439f81d2358d85c25f58676dbad90c67a560d987e8`. Their verified signer certificate SHA-256 is `fac61745dc0903786fb9ede62a962b399f7348f0bb6f899b8332667591033b9c`, matching the existing development APK. Their whole-APK hashes differ: this verifies reproducible build procedure/behavior and identical bundled JS, not byte-for-byte deterministic APK packaging.

The older core identities (`stage0-777a33b-host-v1`, `stage0-777a33b-bridge-v2`) and original Kotlin source baseline `777a33b` remain historical in [recorder validation](recorder-validation.md), prior reports and retained artifacts. No previous project or baseline results were removed.

## Reproduction and results

Use the tools/commands in [sensor README](../apps/sensor/README.md): Node 22.22.2/npm 10.9.7, full JDK 21, SDK/build tools 36/36.0.0, NDK 27.1.12297006/CMake 3.22.1, Gradle 9.3.1 checksum pin, Kotlin 2.3.20/AGP 8.12.0. Expo 57.0.28/RN 0.86.3 and native dependencies are unchanged. No EAS account or server is required. Dependency acquisition/build requires network access when caches are absent; autonomous app startup does not.

From `apps/sensor`:

```bash
npm ci
npm run typecheck
npm run lint
npm test
npm run prebuild:android
npm run build:android:offline
npm run verify:android:offline
android/gradlew -p android :app:assembleDebug :app:assembleDebugAndroidTest \
  :veymur-recorder:testDebugUnitTest :veymur-recorder:lintDebug
```

Main typecheck/lint and all 19 TS tests passed. Main release build succeeded in 1m45s (544 tasks, 504 executed). To verify clean generation before committing, an isolated detached worktree at `48936f6` received the candidate app source, excluding root generated android/ios, node_modules and build/cache outputs. `npm ci`, typecheck/lint/19 tests, prebuild and release build succeeded there: **544/544 tasks executed** in 1m22s. Subsequent clean debug/test builds succeeded: 417 tasks, 377 executed; **2 JVM tests passed**, native lint **0 errors/7 warnings**. This uses shared dependency downloads/toolchain caches, not source/build outputs from the main checkout.

The APK inspection command passed on both autonomous builds: ZIP CRC, applicationId, min/target SDK, non-debuggable flag, service/provider/core metadata, non-empty bundle, both ABIs and signature verification. Inspection outputs are retained in `artifacts/stage8/main-apk.json` and `clean-apk.json`. Inspection alone does not prove runtime behavior.

For actual offline cold starts on the dedicated API-34/x86_64 emulator: the project's Metro process was stopped, port 8081 had no listener, ADB reverse was removed and Wi-Fi/mobile data disabled (both settings 0). Each APK was installed with `-r`, force-stopped and launched through `com.veymur.sensor/.MainActivity`. Both rendered “Veymur · recording validation”, “Session: preparation · Storage: ready” and “No current heart rate” without a developer launcher/server connection. Initial package replacement preserved the existing empty diagnostic database and reopened it. XML/screenshots are in `artifacts/stage8/`; prior emulator network state was restored after verification. No sensor-backed recording was performed.

The regenerated debug/test APKs were then installed on a freshly cleared **dedicated emulator only**, without Metro/JS subscription, and run with:

```bash
adb -s emulator-5554 shell am instrument -w -r \
  com.veymur.sensor.test/androidx.test.runner.AndroidJUnitRunner
```

**14 instrumentation tests passed**: actual SQLCipher/Keystore, reopening/recovery/export/provider/manifest, atomic transitions with fault injection, native FGS survival across unsubscribe/unbind/rebind, stale HR/revisions, native Activity events and route-denied foreground type. Fixtures use isolated DBs and explicitly synthetic sensor state, not a real Polar stream. The fresh completed/interrupted synthetic ZIPs from this regenerated APK passed the unchanged Python analyzer; **4 analyzer unit tests passed**. Completed fixture: 2 HR/2 GPS, active_ms=2000; interrupted fixture: 1 HR/1 GPS, active_ms=1500. This does not establish sensor timing or phone recording reliability.

Build/test/inspection evidence and the final APK were copied out before removing the temporary worktree; its generated files are not a source dependency. Fresh checkout instructions after committing are in the README.

## Exit criteria and deferred work

| Criterion | Verified evidence | Remaining work |
|---|---|---|
| RN commands/state use native recorder | Real UI/native-ready state, TS contract/controller tests | Sensor-backed UI command flow on Galaxy A23 |
| JS lifecycle does not own persistence | Native started-FGS unbind/rebind and controller remount tests | Actual JS reload/remount while a real stream records |
| Independent readable ZIP | Regenerated synthetic native exports + Python analyzer | New RN phone exports for hardware scenarios |
| Configuration regenerates | Clean npm/prebuild, debug and autonomous builds | Repeat runtime checks on target hardware |
| No-network operation | Embedded bundle and emulator cold launch with network/Metro off | Real offline HR/GPS recording and finish/export |

Stage 7 remains deferred except its independent clean-generation/build task, now verified. Stage 8's documentation/build/identity work is prepared, but its final recorder criteria and overall Android migration exit remain **open** until physical-device comparison. Stage 0, iOS and MVP-A are not completed by these tests.

Pending: real Verity Sense/phone HR+optional GPS; locked/background recording; actual JS reload; BLE/GPS loss and recovery; real pause/resume/finish; permission denial/retry (including notifications with a connected sensor); abrupt interruption/recovery; offline session/export; battery and uncommitted loss window. Debug/ABI/store limitations inherited from the compatibility report remain; API-34 x86_64 runtime does not prove arm64/16 KB or Swift/iOS support. Production signing/release approval are separate.

Following the agreed order, the next independent work is the main development plan's project foundation: shared packages, runnable web/API, local environment and CI. The hardware verification block remains visible rather than being marked complete.
