# Foreground Service Connection Holder Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Keep a live, non-simulated diagnostic session alive across navigation and app backgrounding by running a foreground service whenever the connection is `Ready` and not simulated.

**Architecture:** A new, unbound `ConnectionHolderService` shows a static informational notification. `AppContainer` (the existing manual-DI composition root) observes `DiagnosticsManager.connectionState` + `isSimulated`, derives a boolean via a pure policy function, and starts/stops the service on the rising/falling edge of that boolean. No other code changes — ViewModels keep talking to `DiagnosticsManager` directly, unaffected.

**Tech Stack:** Kotlin, Android `Service`, `androidx.core` (`NotificationCompat`, `ContextCompat`) — already a transitive dependency via `androidx-core-ktx`, no new Gradle dependencies needed. Kotlin coroutines/`Flow` (`combine`, `distinctUntilChanged`), already used throughout `:app`.

**Spec:** `docs/superpowers/specs/2026-08-20-foreground-service-connection-holder-design.md`

## Global Constraints

- `minSdk` = 26, `compileSdk`/`targetSdk` = 37 (`app/build.gradle.kts`) — the `<service>` manifest entry MUST declare `android:foregroundServiceType="connectedDevice"` (mandatory at targetSdk ≥ 34), and the 3-arg `startForeground(id, notification, type)` overload is only available API 29+ (`Build.VERSION_CODES.Q`) — gate it, falling back to the 2-arg overload below that.
- The service runs whenever `connectionState == ConnectionState.Ready && !isSimulated` — regardless of foreground/background app state. It does NOT run for replay/demo (simulated) transports.
- The notification is informational only: title, text, tap-to-reopen `MainActivity`. No action buttons (e.g. no in-notification Disconnect).
- The service does not own transport lifecycle; it never calls `connect()`/`disconnect()` itself. `AppContainer` remains the sole source of truth for whether a real connection is active.
- No new Gradle dependencies, no Hilt/Koin (this codebase uses manual DI via `AppContainer` only, per `CLAUDE.md` and existing code).
- Per this repo's `CLAUDE.md`: do not commit — `git add` after each task's local commit-equivalent step, and leave the actual `git commit` history to the executor's normal workflow (the steps below still say "Commit" per the plan template; that means locally creating the commit as this repo's contributors normally do while executing — the user reviews/pushes separately, this is unchanged from normal repo workflow).

---

### Task 1: Connection-holder policy function

**Files:**
- Create: `app/src/main/java/nl/jwdr/ooc/service/ConnectionHolderPolicy.kt`
- Test: `app/src/test/java/nl/jwdr/ooc/service/ConnectionHolderPolicyTest.kt`

**Interfaces:**
- Consumes: `nl.jwdr.ooc.transport.ConnectionState` (sealed interface: `Disconnected`, `Connecting`, `Ready`, `Error(cause: Throwable)`), already defined in `core/transport/src/main/kotlin/nl/jwdr/ooc/transport/ConnectionState.kt`.
- Produces: `fun shouldRunConnectionHolder(state: ConnectionState, isSimulated: Boolean): Boolean` in package `nl.jwdr.ooc.service` — consumed by Task 3's `AppContainer` wiring.

- [ ] **Step 1: Write the failing test**

```kotlin
package nl.jwdr.ooc.service

import nl.jwdr.ooc.transport.ConnectionState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionHolderPolicyTest {

    @Test
    fun `runs when Ready and not simulated`() {
        assertTrue(shouldRunConnectionHolder(ConnectionState.Ready, isSimulated = false))
    }

    @Test
    fun `does not run when Ready but simulated`() {
        assertFalse(shouldRunConnectionHolder(ConnectionState.Ready, isSimulated = true))
    }

    @Test
    fun `does not run when Disconnected`() {
        assertFalse(shouldRunConnectionHolder(ConnectionState.Disconnected, isSimulated = false))
    }

    @Test
    fun `does not run when Connecting`() {
        assertFalse(shouldRunConnectionHolder(ConnectionState.Connecting, isSimulated = false))
    }

    @Test
    fun `does not run on Error`() {
        assertFalse(
            shouldRunConnectionHolder(ConnectionState.Error(RuntimeException("boom")), isSimulated = false),
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "nl.jwdr.ooc.service.ConnectionHolderPolicyTest"`
Expected: FAIL to compile — `shouldRunConnectionHolder` is unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package nl.jwdr.ooc.service

import nl.jwdr.ooc.transport.ConnectionState

/**
 * True while a foreground service should be running to protect a live,
 * non-simulated session from the OS killing the process while the app is
 * backgrounded. Simulated/replay sessions never need it (#20).
 */
fun shouldRunConnectionHolder(state: ConnectionState, isSimulated: Boolean): Boolean =
    state is ConnectionState.Ready && !isSimulated
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "nl.jwdr.ooc.service.ConnectionHolderPolicyTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/nl/jwdr/ooc/service/ConnectionHolderPolicy.kt app/src/test/java/nl/jwdr/ooc/service/ConnectionHolderPolicyTest.kt
git commit -m "Add connection-holder policy function (#20)"
```

---

### Task 2: ConnectionHolderService, notification, strings, manifest

**Files:**
- Create: `app/src/main/java/nl/jwdr/ooc/service/ConnectionHolderService.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `nl.jwdr.ooc.MainActivity` (existing, no changes), string resources defined in this task.
- Produces: `class ConnectionHolderService : Service()` in package `nl.jwdr.ooc.service`, with `companion object { fun createNotificationChannel(context: Context) }` — consumed by Task 3 (`OocApplication.onCreate()` calls `createNotificationChannel`, `AppContainer` starts/stops the service via `Intent(context, ConnectionHolderService::class.java)`).

There is no automated test for this task — `Service` lifecycle isn't unit-testable without Robolectric, which this project does not use (see spec's Testing section). This task's steps verify by compiling and manifest-merging; end-to-end behavior is verified manually in Task 4.

- [ ] **Step 1: Add notification strings**

Add this block to `app/src/main/res/values/strings.xml`, immediately before the closing `</resources>` tag (after the `error_generic_communication` line):

```xml
    <!-- Foreground connection holder (#20) -->
    <string name="connection_holder_channel_name">Vehicle connection</string>
    <string name="connection_holder_notification_title">OpelOBD</string>
    <string name="connection_holder_notification_text">Connected to vehicle</string>
```

- [ ] **Step 2: Create the service**

Create `app/src/main/java/nl/jwdr/ooc/service/ConnectionHolderService.kt`:

```kotlin
package nl.jwdr.ooc.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import nl.jwdr.ooc.MainActivity
import nl.jwdr.ooc.R

/**
 * Passive foreground promotion (#20): shows a persistent notification while
 * a live, non-simulated diagnostic session is active, so Android does not
 * kill the process on backgrounding. [nl.jwdr.ooc.AppContainer] owns the
 * transport lifecycle and starts/stops this service; it has no data-flow
 * role of its own and never calls connect()/disconnect().
 */
class ConnectionHolderService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.connection_holder_notification_title))
            .setContentText(getString(R.string.connection_holder_notification_text))
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "connection_holder"

        /** Idempotent — safe to call on every app start. */
        fun createNotificationChannel(context: Context) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.connection_holder_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
```

Note: the small icon reuses the framework's `android.R.drawable.stat_sys_data_bluetooth` rather than adding a new drawable asset — appropriate for a Bluetooth "connected device" notification and avoids introducing new icon resources for this issue.

- [ ] **Step 3: Add manifest permissions and service declaration**

In `app/src/main/AndroidManifest.xml`, add after the existing `<uses-feature android:name="android.hardware.bluetooth" .../>` block (before `<application ...>`):

```xml
    <!-- Foreground service connection holder (#20): keeps a live session
         alive across backgrounding. -->
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

Then add the service declaration inside `<application>`, after the `<provider>` block (before the closing `</application>`):

```xml
        <service
            android:name=".service.ConnectionHolderService"
            android:exported="false"
            android:foregroundServiceType="connectedDevice" />
```

- [ ] **Step 4: Verify it compiles and the manifest merges cleanly**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL (this exercises manifest merging, which fails loudly on an invalid `foregroundServiceType` or missing permission for it).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/nl/jwdr/ooc/service/ConnectionHolderService.kt app/src/main/res/values/strings.xml app/src/main/AndroidManifest.xml
git commit -m "Add ConnectionHolderService with connected-device notification (#20)"
```

---

### Task 3: Wire AppContainer to start/stop the service

**Files:**
- Modify: `app/src/main/java/nl/jwdr/ooc/OocApplication.kt:1-23` (imports)
- Modify: `app/src/main/java/nl/jwdr/ooc/OocApplication.kt:61-63` (add `init` block + helper after `diagnosticsManager`)
- Modify: `app/src/main/java/nl/jwdr/ooc/OocApplication.kt:95-97` (add `LOG_TAG`)
- Modify: `app/src/main/java/nl/jwdr/ooc/OocApplication.kt:181-183` (`OocApplication.onCreate()`)

**Interfaces:**
- Consumes: `shouldRunConnectionHolder(state, isSimulated): Boolean` (Task 1), `ConnectionHolderService` + `ConnectionHolderService.createNotificationChannel(context)` (Task 2), `DiagnosticsManager.connectionState: StateFlow<ConnectionState>` / `.isSimulated: StateFlow<Boolean>` (existing), `AppContainer.applicationScope` (existing).
- Produces: nothing new consumed by later tasks — this is the last wiring point.

There is no new automated test in this task: the existing `DiagnosticsManagerTest` and `ShellViewModelTest` suites must keep passing unchanged (this task adds an `Application`/`Context`-dependent side effect that those JVM tests never touch, since they construct `DiagnosticsManager` directly rather than via `AppContainer`). Verification is: full test suite still green, plus manual on-device check in Task 4.

- [ ] **Step 1: Add new imports**

In `app/src/main/java/nl/jwdr/ooc/OocApplication.kt`, replace the import block:

```kotlin
package nl.jwdr.ooc

import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import nl.jwdr.ooc.catalogstore.CatalogDatabase
import nl.jwdr.ooc.catalogstore.CatalogRepository
import nl.jwdr.ooc.diagnostics.BluetoothSppLink
import nl.jwdr.ooc.diagnostics.DiagnosticsManager
import nl.jwdr.ooc.diagnostics.TransportSelection
import nl.jwdr.ooc.transport.CanFrame
import nl.jwdr.ooc.transport.FakeEcuTransport
import nl.jwdr.ooc.transport.ObdTransport
import nl.jwdr.ooc.transport.SwitchableObdTransport
import nl.jwdr.ooc.transport.elm327.Elm327Transport
import nl.jwdr.ooc.ui.livedata.FileLiveDataCsvStore
import nl.jwdr.ooc.ui.livedata.LiveDataCsvStore
```

with:

```kotlin
package nl.jwdr.ooc

import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import nl.jwdr.ooc.catalogstore.CatalogDatabase
import nl.jwdr.ooc.catalogstore.CatalogRepository
import nl.jwdr.ooc.diagnostics.BluetoothSppLink
import nl.jwdr.ooc.diagnostics.DiagnosticsManager
import nl.jwdr.ooc.diagnostics.TransportSelection
import nl.jwdr.ooc.service.ConnectionHolderService
import nl.jwdr.ooc.service.shouldRunConnectionHolder
import nl.jwdr.ooc.transport.CanFrame
import nl.jwdr.ooc.transport.FakeEcuTransport
import nl.jwdr.ooc.transport.ObdTransport
import nl.jwdr.ooc.transport.SwitchableObdTransport
import nl.jwdr.ooc.transport.elm327.Elm327Transport
import nl.jwdr.ooc.ui.livedata.FileLiveDataCsvStore
import nl.jwdr.ooc.ui.livedata.LiveDataCsvStore
```

- [ ] **Step 2: Add the observer and helper after `diagnosticsManager`**

Find:

```kotlin
    val diagnosticsManager: DiagnosticsManager by lazy {
        DiagnosticsManager(switchableTransport)
    }

    /**
     * Applies and persists a new adapter choice. Only valid while
```

Replace with:

```kotlin
    val diagnosticsManager: DiagnosticsManager by lazy {
        DiagnosticsManager(switchableTransport)
    }

    init {
        applicationScope.launch {
            combine(
                diagnosticsManager.connectionState,
                diagnosticsManager.isSimulated,
                ::shouldRunConnectionHolder,
            ).distinctUntilChanged().collect(::applyConnectionHolderState)
        }
    }

    private fun applyConnectionHolderState(shouldRun: Boolean) {
        val intent = Intent(appContext, ConnectionHolderService::class.java)
        if (shouldRun) {
            try {
                ContextCompat.startForegroundService(appContext, intent)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "failed to start ConnectionHolderService", e)
            }
        } else {
            appContext.stopService(intent)
        }
    }

    /**
     * Applies and persists a new adapter choice. Only valid while
```

- [ ] **Step 3: Add the log tag constant**

Find:

```kotlin
    private companion object {
        const val PREF_SELECTION = "selection"
    }
```

Replace with:

```kotlin
    private companion object {
        const val PREF_SELECTION = "selection"
        const val LOG_TAG = "AppContainer"
    }
```

- [ ] **Step 4: Create the notification channel on app startup**

Find:

```kotlin
class OocApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
```

Replace with:

```kotlin
class OocApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        ConnectionHolderService.createNotificationChannel(this)
    }
}
```

- [ ] **Step 5: Run the full unit test suite to confirm no regressions**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all existing tests (including `DiagnosticsManagerTest`, `ShellViewModelTest`) still pass, plus the new `ConnectionHolderPolicyTest` from Task 1.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/nl/jwdr/ooc/OocApplication.kt
git commit -m "Start/stop ConnectionHolderService from AppContainer (#20)"
```

---

### Task 4: Manual end-to-end verification

**Files:** none (no code changes — this task only verifies Tasks 1-3's behavior on a real device/emulator, per the spec's Testing section: this project has no Robolectric/instrumented test setup, so `Service` lifecycle is verified manually).

- [ ] **Step 1: Install and launch the debug build**

Run: `./gradlew :app:installDebug` (with a device or emulator connected/running), then launch the app.

- [ ] **Step 2: Verify the service does NOT start for the demo (simulated) transport**

With the default/demo transport selected in Settings, tap Connect on the Home screen. Then run:

`adb shell dumpsys activity services nl.jwdr.ooc`

Expected: no `ConnectionHolderService` entry listed, and no persistent notification appears in the notification shade.

- [ ] **Step 3: Verify the service starts for a real (Bluetooth) transport**

In Settings, select a paired Bluetooth ELM327 adapter (or, if none is available, temporarily verify with a quick manual override — see note below) and Connect. Then run:

`adb shell dumpsys activity services nl.jwdr.ooc`

Expected: `nl.jwdr.ooc.service.ConnectionHolderService` is listed as running, and a persistent, low-priority "OpelOBD — Connected to vehicle" notification appears in the shade with no action buttons. Tapping it reopens the app.

Note: if no real Bluetooth adapter is available for this check, it is acceptable to temporarily change `shouldRunConnectionHolder`'s `isSimulated` check to always return `true` for a `FakeEcuTransport` connection, observe the service start with the demo transport, then revert the temporary change before committing — do not leave this change committed.

- [ ] **Step 4: Verify the service stops on disconnect**

While connected to the real transport from Step 3, tap Disconnect. Then run:

`adb shell dumpsys activity services nl.jwdr.ooc`

Expected: `ConnectionHolderService` no longer listed, and the notification disappears.

- [ ] **Step 5: Verify the session survives backgrounding**

While connected to the real transport, press Home to background the app for at least 60 seconds, then reopen it via the launcher (not via the notification). Expected: the connection is still `Ready` (the Home screen still shows "Connected", not "Disconnected" or "Connection error").

- [ ] **Step 6: Run the full project test suite one last time**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL.
