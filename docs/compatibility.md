# Stage 0 Matrix

Initial hardware target: Samsung Galaxy A23 SM-A235F, Android 14 (API 34), Polar Verity Sense, EAN 725882065014. Existing recorder exports and tester observations are documented in [recorder-validation.md](recorder-validation.md). Earlier runs report firmware characteristic 00002a26 = 0.1.6 and software characteristic 00002a28 = 3.0.16; the current sensor firmware has not been read again. Physical-device migration validation remains pending.

| Component | Pinned Version |
|---|---|
| Prototype language | Kotlin 2.1.20 |
| JDK / bytecode | 21 |
| Gradle | 8.13 |
| Android Gradle Plugin | 8.10.1 |
| compile / target / min SDK | 35 / 35 / 33 |
| Polar BLE SDK | 8.4.0 |
| kotlinx-coroutines-android | 1.10.2 |
| SQLCipher Android | 4.6.1 |
| androidx.sqlite | 2.4.0 |
| androidx.core | 1.16.0 |

Repositories: Google Maven, Maven Central, and JitPack only for the Polar group. Dependencies are pinned through Gradle lockfiles. The wrapper pins the Gradle distribution and checksum.

Official sources checked during implementation on October 7, 2026:

- Polar SDK: https://github.com/polarofficial/polar-ble-sdk/tree/8.4.0 — HR callbacks, discovery via coroutine Flow, and automatic reconnection. The README specifies minSdk 33; the library source has a less restrictive minimum, but the prototype retains 33.
- SQLCipher: https://github.com/sqlcipher/sqlcipher-android/tree/v4.6.1 — database opening API and native library.
- Android foreground service types: https://developer.android.com/develop/background-work/services/fgs/service-types — connectedDevice and location, corresponding permissions, and starting from a visible Activity.

The pinned SQLCipher version was selected for Android 14; compatibility with other Android versions/page sizes and a library upgrade will be checked separately before the MVP release.

React Native/Expo, MapLibre, and iOS are not included in this diagnostic APK. Full-stack compatibility remains an open stage 0 task; a successful Kotlin build does not confirm it.

## Android migration dependency experiment — October 13, 2026

The selected bridge is a **local Expo Module**, tested in the separate [sensor-bridge-probe](../apps/sensor-bridge-probe/README.md). It uses an Expo development client, prebuild/CNG and the New Architecture. A second Turbo Native Module adapter was not created. Version requirements below are sourced from official documentation; actual build/runtime evidence is recorded separately in [the experiment report](stage-0/react-native-bridge-validation.ru.md).

| Component | Exact selected version / requirement | Source checked October 13, 2026 |
|---|---|---|
| Expo SDK | 57.0.28 | [SDK 57 compatibility](https://docs.expo.dev/versions/v57.0.0/); exact patch checked against the Expo npm package |
| React Native / React | 0.86.3 / 19.2.3 | [Expo SDK matrix](https://docs.expo.dev/versions/v57.0.0/); Expo's installed bundledNativeModules.json |
| Expo development client | 57.0.20 | [Development builds](https://docs.expo.dev/develop/development-builds/introduction/); installed SDK compatibility map |
| Expo Modules Core | 57.0.22, npm lockfile | [Local modules](https://docs.expo.dev/modules/get-started/), [autolinking](https://docs.expo.dev/modules/autolinking/) |
| Expo build-properties | 57.0.22 | [BuildProperties](https://docs.expo.dev/versions/v57.0.0/sdk/build-properties/) |
| Node / npm | 22.22.2 / 10.9.7; SDK requires Node >=22.13.x | [Expo matrix](https://docs.expo.dev/versions/v57.0.0/); .nvmrc, packageManager, npm lockfile v3 |
| TypeScript / @types/react | 6.0.3 / 19.2.18 | SDK-selected TypeScript; exact installed declarations in package-lock.json |
| Kotlin compiler / stdlib | 2.3.20 | [Kotlin Gradle compatibility](https://kotlinlang.org/docs/gradle-configure-project.html); explicit host classpath pin and build-properties |
| Gradle | 9.3.1, Expo-generated wrapper, pinned SHA-256 | [Gradle Java matrix](https://docs.gradle.org/9.3.1/userguide/compatibility.html); [official distribution checksum](https://services.gradle.org/distributions/gradle-9.3.1-bin.zip.sha256) |
| Android Gradle Plugin | 8.12.0, RN Gradle plugin catalog | [AGP 8.12](https://developer.android.com/build/releases/agp-8-12-0-release-notes): supports API 36, Gradle >=8.13, JDK >=17 |
| Build JDK / probe bytecode | Temurin 21.0.12.1+1 / Java 17 (class major 61, javap-verified) | [Gradle matrix](https://docs.gradle.org/9.3.1/userguide/compatibility.html): Java 21 can run Gradle >=8.5; JDK 17 is RN's recommended baseline in [environment setup](https://reactnative.dev/docs/set-up-your-environment) |
| Android SDK | min 33 / compile 36 / target 36, Build Tools 36.0.0 | Expo's defaults are min 24/compile 36/target 36; [Polar 8.4.0 README](https://github.com/polarofficial/polar-ble-sdk/blob/8.4.0/README.md) requires min 33; host min explicitly raised |
| NDK / CMake | 27.1.12297006 / 3.22.1 | Installed RN Gradle catalog and native build; [16 KB build guidance](https://developer.android.com/guide/practices/page-sizes) |
| Polar BLE SDK | 8.4.0 retained from baseline | [Tagged README/API](https://github.com/polarofficial/polar-ble-sdk/tree/8.4.0): coroutine Flow, Bluetooth scan/connect permissions, Verity Sense support |
| SQLCipher Android / SQLite API | net.zetetic:sqlcipher-android 4.6.1 / androidx.sqlite 2.4.0 retained from baseline | [Tagged source](https://github.com/sqlcipher/sqlcipher-android/tree/v4.6.1), [Zetetic 16 KB support](https://www.zetetic.net/blog/2025/06/26/sqlcipher-for-android-16kb-page-size-support/) |
| Android ABIs under test | arm64-v8a (target-device binary), x86_64 (emulator) | APK contents and build evidence; phone runtime requires a separate physical-device test |
| iOS | Expo SDK 57 requirement: iOS >=16.4, Xcode >=26.4; full recorder combination **unverified** | [Expo matrix](https://docs.expo.dev/versions/v57.0.0/); no Swift/Xcode build on this Linux machine |

Do not copy the Kotlin prototype's JDK bytecode-21 setting into the RN host without a new test. Build-JDK version and module bytecode targets are separate. Kotlin's published table lists Gradle support through 9.3.0 for KGP 2.3.20; this experiment uses Expo's 9.3.1 patch and records the real build result rather than claiming an explicitly listed combination. RN/Expo build plugins have their own bundled compiler versions; the host compiler is explicitly pinned because changing only android.kotlinVersion left KGP 2.1.20 on the classpath and caused incompatible Kotlin metadata errors.

SQLCipher 4.6.1 is the modern `sqlcipher-android` package, not the retired `android-database-sqlcipher`. Zetetic documents 16 KB page support beginning with 4.6.1. APK/ELF checks and x86_64 library loading do not replace a real 16 KB runtime test. Polar is an Android bytecode library; its presence/version API does not demonstrate BLE functionality or behavior on every CPU/device.

No new library versions are pinned for MapLibre, cloud/web, server, Swift or iOS SQLCipher. Those integrations remain outside this experiment. npm audit findings and native toolchain warnings are recorded in the experiment report; this is not a release-ready dependency audit.

## Minimal RN application — migration stage 3

Migration stage 3 now provides [apps/sensor](../apps/sensor/README.md) with the same native stack and the separate applicationId `com.veymur.sensor`. Main and clean-checkout Android builds for arm64-v8a/x86_64 both passed; both installed APKs returned the `VeymurRecorder.getModuleInfo` DTO through JS on API 34. Recorder implementation remains false, no BLE/service/database calls occur in this handshake, and iOS explicitly rejects it. See the [stage 3 report](stage-0/react-native-minimal-app-validation.ru.md).

New lint-only dependencies, checked October 13, 2026: ESLint 10.12.0, @eslint/js 10.0.1, typescript-eslint 8.71.2. [ESLint's Node requirements](https://eslint.org/docs/latest/use/getting-started) and [typescript-eslint dependency ranges](https://typescript-eslint.io/users/dependency-versions/) plus registry peers were checked before pinning. Node 22.22.2 / TypeScript 6.0.3 are supported; typecheck and ESLint passed in both checkouts. npm audit findings remain at 22 (7 moderate, 15 high) in the inherited tooling graph. These build results do not close physical-device, recording or iOS compatibility checks.

## Foreground recording requirements for target SDK 36

These requirements were checked against the current [foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types), [launch rules](https://developer.android.com/develop/background-work/services/fgs/launch), [background-start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start), [Bluetooth permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions), and [notification permission](https://developer.android.com/develop/ui/compose/notifications/notification-permission). The probe has no recorder service: implementation and runtime verification belong to migration stages 4–7.

- Declare FOREGROUND_SERVICE and FOREGROUND_SERVICE_CONNECTED_DEVICE; declare service type connectedDevice. Request BLUETOOTH_SCAN/BLUETOOTH_CONNECT through explicit user actions; granted Bluetooth runtime permissions satisfy the connected-device prerequisite. Polar recommends neverForLocation on scan.
- For a phone route, also declare FOREGROUND_SERVICE_LOCATION and service type location, request COARSE/FINE location together, and recheck precise permission and enabled provider before start. Enable the location service type only for an actually permitted route; keep HR available after location denial.
- Start/promote recording from the visible Activity after user action. While-in-use location restricts creating a location FGS in the background; the MVP does not require an automatic background start or ACCESS_BACKGROUND_LOCATION. A started service may continue after UI/JS detaches, subject to OS behavior.
- Supply a foreground notification and channel. Android 13+ POST_NOTIFICATIONS denial does not prevent launching an FGS; the mandatory service notification still exists with system-limited visibility.
- Check both configured target SDK and actual runtime OS. Target 36 does not upgrade Galaxy A23 from API 34. On Android 16, foreground-service jobs are subject to job quotas ([behavior changes](https://developer.android.com/about/versions/16/behavior-changes-16)); do not treat an FGS as unlimited execution for future job scheduling.

Force stop/reboot continuity, BLE recovery, phone locking, storage access, battery cost and recording loss windows remain hardware/core checks. Expo module registration alone makes none of these guarantees.
