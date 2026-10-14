# React Native diagnostic recorder — migration stage 6

Implemented October 14, 2026. This is the recording-validation interface ported from the Kotlin prototype, not the final MVP screen design. It uses the existing Android native writer and diagnostic ZIP format. The original `apps/sensor-stage0` is preserved.

## Behavior and sources

`App.tsx` now opens `src/screens/DiagnosticRecorderScreen.tsx`. `DeviceList.tsx` exposes explicit device selection by ID; `RecorderStatus.tsx` renders native readiness, connection, nullable fresh/stale/missing HR, battery, checkpointed active time, committed HR/GPS counts, last commit, GPS status and interrupted recovery. No UI timer computes recording duration or HR freshness. Status is text-based, not distinguished only by color.

`useRecorder.ts` subscribes through `RecorderController.ts` and `RecorderClient.ts`. Mount only checks the binary contract and subscribes; cleanup only detaches. Remount restores native state and actual route enablement without a second start/pause/finish. A pending command immediately blocks repeated taps. The UI acknowledges start/finish only after the existing native commit Promise succeeds. Failed finish retains a visible retry-saving action for its interrupted session. Search/connect acceptance does not fabricate a connected device. Finish requires confirmation; export opens the native scoped ZIP chooser and reports chooser opening, not recipient saving. Command errors/messages remain visible below the scrollable screen. App permissions opens Android settings.

The controls include search/cancel, connect, optional phone route, start, explicit pause/resume, confirmed finish, retry saving, export/share and refresh. A disconnected sensor does not disable finishing an active session. An unavailable route does not prevent HR-only recording. An iOS placeholder explicitly rejects recording support and never mounts the Android hook.

## Permissions and host lifecycle

`permissions.ts` uses [React Native PermissionsAndroid](https://reactnative.dev/docs/0.86/permissionsandroid); no new dependency was added. Bluetooth scan/connect are requested together only after the user chooses Find Verity Sense and continues the explanation. Denial or permanent denial leaves a retry/settings message. Device connection/start checks existing Bluetooth permission rather than prompting on mount.

Enabling the route requests fine/coarse location together; approximate-only or denied location leaves the route off and permits HR-only recording. Start offers the ongoing-notification permission; the explicit Continue without notifications choice and OS denial do not block recording. Native start still rechecks current Bluetooth/location/provider availability and applies the location foreground-service type only to an actually enabled route. Native `ensureActivity()` checks visible Activity availability before permission/confirmation dialogs; native start/share also check Activity. Permission failures are shown and can be retried from the visible app.

The bridge contract is now **version 3**, core identity **stage0-777a33b-diagnostic-v3**. The added `ensureActivity` method requires rebuilding/reinstalling the native binary; the old version-2 APK is deliberately rejected. Snapshot fields and commit semantics are unchanged from [stage 5](recorder-bridge-contract.md). Database/ZIP schema 1, sandbox and keys are unchanged.

RecorderModule forwards [Expo native Activity foreground/background events](https://docs.expo.dev/modules/module-api/) to RecorderService, including an initial known visibility on binding. Unknown window focus is not interpreted as an actual background transition. Duplicate visibility changes are ignored. React screen unmount does not send Activity events or `screen_off`; actual screen on/off remains the service's Android broadcast responsibility. Generated RN MainActivity is retained; no prototype Activity replaces it, and no edits exist only in generated host files.

## Verification

From `apps/sensor`, with JDK 21 and the configured Android SDK:

```bash
npm run typecheck
npm run lint
npm test
npm run prebuild:android
android/gradlew -p android :app:assembleDebug :app:assembleDebugAndroidTest \
  :veymur-recorder:testDebugUnitTest :veymur-recorder:lintDebug
```

Run the native test APK on a fresh dedicated Android emulator following the [stage 5 procedure](recorder-bridge-contract.md#verification-procedure-and-scope). Never clear or run destructive test cleanup on a phone containing recordings. Service tests use isolated databases and explicitly synthetic connected state; their host must have no existing recordings or RN subscription.

The controller/permission tests cover mount/remount, Bluetooth denial/retry, approximate/denied route permission, notification denial/continue, pending commands/double taps, finish confirmation/failure, chooser wording, missing Activity, and unmount during a permission dialog. Native tests additionally exercise actual foreground-service type fallback without location permission and Activity-event de-duplication independently of screen broadcasts.

Actual emulator UI checks include native-ready/HR-missing state, absence of startup permission requests, Bluetooth explanation → OS denial → retry/grant → discovery/cancel, route OS denial → switch off, and empty-export error. XML/screenshot evidence and build/test logs are retained locally under ignored `artifacts/stage6/`.

Final test counts, APK identity and known limits are recorded in [recorder validation](recorder-validation.md). Full sensor-backed UI start/pause/resume/finish/sharing, the live notification dialog during sensor-connected start, actual JS reload while recording, lock-screen BLE/GPS behavior and battery remain physical-device stage-7 checks. Synthetic/emulator checks do not mark those complete. iOS remains unimplemented; the debug development build uses Metro for JS startup, so the offline bundled-APK scenario also remains stage 7.
