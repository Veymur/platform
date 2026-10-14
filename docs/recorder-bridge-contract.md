# Android recorder bridge — migration stage 5

Implemented October 14, 2026. Contract version **2**, `recorderImplemented=true`, native core identity **stage0-777a33b-bridge-v2**. This identifies the Android command/state implementation, not hardware validation or a finished recording interface. iOS remains unsupported. The previous Kotlin app is unchanged and uses a separate sandbox.

## Public API and acknowledgments

Use `src/recorder/RecorderClient.ts`; DTOs and native signatures live in `modules/veymur-recorder/src/types.ts`, re-exported through `src/recorder/types.ts`. `getModuleInfo()` rejects an incompatible installed binary. Rebuild/reinstall after updating the native contract.

| Operation | Successful result means |
|---|---|
| `getSnapshot()` | Complete state read on the native writer; no JS clock calculation |
| `subscribe(listener, onError)` | Event subscription plus initial read; returned function only removes the listener |
| `search()`, `cancelSearch()` | `{accepted:true}`; command accepted, not a connected sensor |
| `connect(id)` | `{accepted:true}`; SDK accepted the connection request; await `connectedDeviceId` in a snapshot |
| `start({routeRequested})` | `{sessionId}` after the session/start/configuration transaction commits and native recording is enabled |
| `pause()`, `resume()` | `{sessionId,state}` after event and state/checkpoint commit; repeating the same action is a no-op |
| `finish()` | `{sessionId,state:'completed'}` after finish/state/checkpoint commit; repeat finish returns the same ID until the next start |
| `exportLatest()` | `{uri,mimeType:'application/zip'}` after native ZIP creation; URI is scoped by FileProvider |
| `shareExport(uri)` | `{chooserOpened:true}`; Android chooser opened, not proof the recipient saved the file |

`start` rejects a second active session; pause/resume/finish reject when no appropriate session exists. Search/connect do not run during recording/start. Finish exposes transient `finishing`, followed by `completed` only after commit. Failed storage operations reject with `STORAGE_ERROR`, stop recording resources and expose `interrupted`; a failed finish retains its session ID and can be retried. A failed start transaction leaves no half-created session. All production SQL writes still occur on RecorderService's HandlerThread. `RecorderSession` implements writer-owned transitions without Expo/React dependencies.

Start requires a visible host Activity and Bluetooth permissions. Actual location permission/provider availability are checked natively: a requested but unavailable route degrades to HR-only. Starting the service does not request permission automatically; explicit permission dialogs and diagnostic controls belong to stage 6. Polar initialization is lazy: a state read/subscription does not initialize discovery or access Bluetooth without permission.

## State and ordering

Snapshots contain revision, readiness/status, active session ID/state, connected device ID, optional battery, nullable HR/age/freshness, discovered device descriptors, committed HR/GPS counts, checkpointed active time/last commit UTC, GPS status, actual route enablement, recovered count and an optional error code. Session states are `preparation`, `recording`, `paused`, `finishing`, `completed`, `interrupted`. After successful finish the active `sessionId` is null; the finish result identifies the completed session. Recovery count reports unfinished sessions recovered on service creation; recovery does not resume recording automatically.

HR freshness uses native `SystemClock.elapsedRealtime()` and the inherited diagnostic 5-second threshold. Disconnected, missing and stale HR are null. Battery is null when disconnected. JS must not compare HR age against `Date.now()` or fill gaps with zero. HR observation timestamps in the journal remain phone receipt timestamps, not hardware measurement time. Active time is checkpointed once per second and can lag the current native clock; counts reflect successful observation writes.

A process-wide increasing revision orders native snapshots, including service recreation within the same process. Each JS subscription attaches before reading initial state and ignores equal/older revisions, so an event received before a delayed initial read remains authoritative. Unsubscribe also suppresses pending initial replies. Snapshots are complete and replaceable; UI can coalesce them. Every HR/GPS observation is written independently of UI delivery. Keys, database paths, full streams and native SDK objects are excluded from the public DTO; export returns a content URI rather than its private path. Storage-error status omits SQL exception messages.

## Lifecycle and errors

Binding waits up to 10 seconds or explicitly rejects; storage not ready produces `NOT_READY`. There are no nullable-reference command drops. An idle, only-bound service may be destroyed when JS detaches. Recording promotes it to a started foreground service before enabling recording. Removing a listener or destroying the Expo module only detaches/unbinds; it does not finish the session, stop the service, shut down Polar or close the active journal. Explicit finish/storage failure performs recording-resource cleanup.

`RecorderClientError.code` uses these fixed codes:

- `UNSUPPORTED_PLATFORM`, `INCOMPATIBLE_BINARY`: use Android/rebuild the matching native binary.
- `SERVICE_UNAVAILABLE`, `NOT_READY`: retry binding/readiness; no successful operation is implied.
- `NO_ACTIVITY`, `PERMISSION_DENIED`: reopen the visible app or grant the needed permission before retrying.
- `INVALID_STATE`, `INVALID_ARGUMENT`, `NOT_CONNECTED`: correct the command/session/device selection.
- `CONNECTION_ERROR`, `DISCOVERY_ERROR`: retry sensor connection/discovery; asynchronous discovery failure is also visible in state.
- `STORAGE_ERROR`, `NO_SESSIONS`: inspect saved/recovered state; retry saving/export only when possible.

Unexpected command failures are normalized to `SERVICE_UNAVAILABLE`; known native codes are preserved. Sharing checks Activity availability, URI authority and readability and grants temporary read access. The module does not claim permission-dialog functionality in this stage.

## Verification procedure and scope

From `apps/sensor` with JDK 21 and the Android SDK configured:

```bash
npm run typecheck
npm run lint
npm test
npm run prebuild:android
android/gradlew -p android :app:assembleDebug :app:assembleDebugAndroidTest \
  :veymur-recorder:testDebugUnitTest :veymur-recorder:lintDebug
```

Run instrumentation on a fresh **dedicated emulator**. Service tests launch the host, grant Bluetooth permissions, substitute explicitly synthetic connected state, and replace its empty journal on the writer with isolated test databases. They refuse a host that already has recordings; the foreground fixture must open the development launcher without an existing JS subscription. Use a newly installed/cleared test host. Never clear an installation containing user recordings. Gradle connected-test cleanup also removes app data.

```bash
adb -s emulator-5554 install -r android/app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w -r \
  com.veymur.sensor.test/androidx.test.runner.AndroidJUnitRunner
```

The native tests exercise actual service binding, a started foreground service across listener removal/unbind/rebind, readiness/stopped-service/missing-Activity errors, monotonic revisions/staleness, repeated transitions, SQL-trigger fault injection and transaction rollback. TS tests cover subscription/read ordering, unsubscribe/remount, invalid DTOs/null HR, commit waiting and error preservation. The minimal development screen additionally reads/subscribes through the actual Expo bridge and logs only handshake fields/readiness/revision.

The final command results and limitations are recorded in [recorder validation](recorder-validation.md). Stage 6 provides the recording/permission interface. Real BLE/GPS, sustained lock-screen recording, actual JS reload while recording, permission dialogs, chooser interactions, phone battery/loss window and iOS remain separate hardware/UI checks; synthetic fixtures do not validate them.
