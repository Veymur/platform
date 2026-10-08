# Stage 0 Matrix

Initial hardware target: Samsung Galaxy A23, Android 14 (API 34), Polar Verity Sense, EAN 725882065014. The user has confirmed connection through the Polar app. The exact phone variant, sensor firmware, and results from our recorder have not yet been received.

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
