# Foreground Service Connection Holder — Design

Date: 2026-08-20
Status: approved outline design
Closes: #20

## Purpose

Keep a live diagnostic session alive across navigation and app backgrounding
by running a foreground service whenever the app holds a real (non-simulated)
connection to a vehicle. Without this, Android can kill the process while the
user switches apps mid-session, silently dropping the connection. Replay mode
never needs this — there is no real hardware link to protect.

## Context

- `DiagnosticsManager` (`app/src/main/java/nl/jwdr/ooc/diagnostics/DiagnosticsManager.kt`)
  is a plain facade over `ObdTransport`, instantiated once as an
  Application-scoped `by lazy` singleton inside `AppContainer`
  (`app/src/main/java/nl/jwdr/ooc/OocApplication.kt`). It exposes
  `connectionState: StateFlow<ConnectionState>` and
  `isSimulated: StateFlow<Boolean>`.
- `ConnectionState` (`core/transport/.../ConnectionState.kt`) is a sealed
  interface: `Disconnected`, `Connecting`, `Ready`, `Error(cause)`.
- There is no dependency-injection framework (no Hilt/Koin) — DI is manual via
  `AppContainer`, which already owns `applicationScope`
  (`SupervisorJob() + Dispatchers.Default`) and an `Application` `Context`.
- No `Service` class exists anywhere in the app today; this is greenfield.
- `compileSdk`/`targetSdk` = 37, `minSdk` = 26. Because targetSdk ≥ 34, any
  foreground service must declare a `foregroundServiceType`.
- The existing hardware transport is Bluetooth (manifest already declares
  `BLUETOOTH_CONNECT`), so `connectedDevice` is the correct foreground service
  type.

## Decisions

- **Trigger:** the service runs whenever the connection is `Ready` and
  `isSimulated` is false — regardless of whether the app is foregrounded or
  backgrounded. Simpler and more robust than trying to react to app
  backgrounding specifically, and correct either way since the notification
  is unobtrusive.
- **Notification:** informational only — no action buttons. Tapping it
  reopens `MainActivity`. Disconnecting always happens from within the app
  UI, never from the notification.
- **Ownership:** the service does not own the transport or drive
  connect/disconnect. `AppContainer` remains the single source of truth for
  whether a real connection is active; the service is a passive foreground
  promotion that `AppContainer` starts and stops.

## Architecture

A new `ConnectionHolderService` (plain, unbound `Service`) lives in `:app`.
`AppContainer` launches a coroutine in its existing `applicationScope` that
combines `diagnosticsManager.connectionState` and
`diagnosticsManager.isSimulated`, derives a boolean via a pure function
`shouldRunConnectionHolder(state, isSimulated)`, and calls
`ContextCompat.startForegroundService()` / `context.stopService()` only on
the rising/falling edge of that boolean (not on every flow emission).

ViewModels and the rest of the UI are unaffected — they continue to reach
`DiagnosticsManager` directly via `AppContainer`. The service is not bound to
anything and has no data-flow role; it exists solely to hold Android's
foreground-service lifetime guarantee.

## Components

- **`ConnectionHolderService : Service()`**
  (`app/src/main/java/nl/jwdr/ooc/service/ConnectionHolderService.kt`):
  `onStartCommand` immediately calls
  `startForeground(NOTIFICATION_ID, notification, FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)`
  and returns `START_NOT_STICKY` — `AppContainer` decides whether the service
  should exist; if the process dies, both die together, so there's no need
  for the OS to resurrect a bare service with nothing to hold alive.
  `onBind` returns null (unbound).
- **Notification:** a single `IMPORTANCE_LOW` channel created once in
  `OocApplication.onCreate()`. Content: "OpelOBD" / "Connected to vehicle",
  `PendingIntent` opening `MainActivity` on tap, no actions, `ongoing = true`.
- **`shouldRunConnectionHolder(state: ConnectionState, isSimulated: Boolean): Boolean`**
  — a pure function (`state is ConnectionState.Ready && !isSimulated`),
  colocated with `AppContainer` or as a top-level function in the same file,
  so it can be unit-tested without Android dependencies.
- **`AppContainer` changes:** a coroutine launched in `applicationScope` that
  collects `combine(diagnosticsManager.connectionState, diagnosticsManager.isSimulated, ::shouldRunConnectionHolder)`,
  tracks the previous boolean, and calls
  `ContextCompat.startForegroundService(context, Intent(context, ConnectionHolderService::class.java))`
  on `false -> true`, and `context.stopService(...)` on `true -> false`. The
  `startForegroundService` call is wrapped in try/catch, logging on failure
  rather than crashing.
- **Manifest additions:**
  - `<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>`
  - `<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE"/>`
  - `<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>`
  - `<service android:name=".service.ConnectionHolderService" android:foregroundServiceType="connectedDevice" android:exported="false"/>`

## Data flow

1. User initiates `connect()` from the (foregrounded) UI.
2. Transport reaches `Ready`; `isSimulated` is false.
3. `AppContainer`'s observer sees the `false -> true` edge and starts
   `ConnectionHolderService`.
4. The service immediately self-promotes to foreground with the static
   notification.
5. On `disconnect()` (user-initiated) or a transport error, state leaves
   `Ready`; the observer sees the `true -> false` edge and stops the service;
   the notification disappears.

## Error handling

- If `POST_NOTIFICATIONS` is not granted (Android 13+), the foreground
  service still starts and runs — the notification simply isn't shown to the
  user. No crash, no functional loss. Adding a runtime permission request
  flow for this is out of scope for this issue; it's a minor UX gap, not a
  correctness gap, and can be a follow-up.
- Because the service is only ever started while the app is in the
  foreground (immediately after a user-initiated `connect()`), the Android
  12+ `ForegroundServiceStartNotAllowedException` path should not be
  reachable in practice. The `startForegroundService` call is still
  defensively wrapped in try/catch and logged, since a crash here would be
  strictly worse than a missing notification.

## Testing

- `:core:transport` and `:core:protocol` are untouched by this issue.
- `shouldRunConnectionHolder` is a pure function — covered by a plain JVM
  unit test exercising all four `ConnectionState` variants crossed with
  `isSimulated`.
- The project has no Robolectric or instrumented test setup today, and
  adding one for a single service is out of scope (YAGNI). The
  `ConnectionHolderService` itself, and the end-to-end start/stop behavior,
  are verified manually on-device/emulator: `adb shell dumpsys activity
  services` to confirm the service starts and stops with connect/disconnect,
  and visual confirmation that the notification appears and disappears.
