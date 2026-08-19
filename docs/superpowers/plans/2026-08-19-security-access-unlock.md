# SecurityAccess Unlock Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a pluggable `SeedKeyAlgorithm` interface and a `DiagnosticSession.unlock()` orchestration on top of the existing KWP2000 SecurityAccess (0x27) codec, so higher layers (issue #18) can unlock an ECU without this repo ever containing a concrete key algorithm.

**Architecture:** `DiagnosticSession.unlock(level, algorithm)` drives the existing `SecurityAccess.RequestSeed` / `SecurityAccess.SendKey` requests through the existing `execute()` primitive (same locking/retry/keep-alive as every other request), calls the caller-supplied `SeedKeyAlgorithm` to compute the key, and returns a typed `UnlockOutcome` or throws a new `SessionException.UnlockFailed`.

**Tech Stack:** Kotlin/JVM, `:core:protocol` module, JUnit4, kotlinx-coroutines-test (`runTest`, `backgroundScope`), the existing `FakeEcuTransport` test double.

**Spec:** `docs/superpowers/specs/2026-08-19-security-access-unlock-design.md`

## Global Constraints

- Protocol layer only: no `DiagnosticsManager` facade method, no runtime plugin-registration mechanism, no UI. Those are issue #18.
- No concrete/vendor `SeedKeyAlgorithm` implementation is ever committed — only the interface, and synthetic test algorithms in test source.
- No auto-retry of a failed unlock and no automatic waiting-out of `RequiredTimeDelayNotExpired` — surface it, don't handle it.
- Only the `SendKey` negative response is remapped to `UnlockFailed`; a negative response to the seed request itself stays a plain `SessionException.NegativeResponse`.

---

## Reference: existing code these tasks build on

`core/protocol/src/main/kotlin/nl/jwdr/ooc/protocol/kwp2000/SecurityAccess.kt`:
```kotlin
object SecurityAccess {
    data class RequestSeed(val accessMode: Int) : KwpRequest<RequestSeed.Response> {
        class Response(val seed: ByteArray) {
            val alreadyUnlocked: Boolean get() = seed.all { it == 0.toByte() }
        }
    }
    class SendKey(val accessMode: Int, val key: ByteArray) : KwpRequest<Unit>
}
```

`core/protocol/src/main/kotlin/nl/jwdr/ooc/protocol/session/SessionException.kt`:
```kotlin
sealed class SessionException(message: String) : Exception(message) {
    class ResponseTimeout(val serviceId: Int) : SessionException(...)
    class NegativeResponse(val serviceId: Int, val error: KwpError) : SessionException(...)
    class TransportLost : SessionException(...)
    class SessionClosed : SessionException(...)
}
```

`core/protocol/src/main/kotlin/nl/jwdr/ooc/protocol/session/DiagnosticSession.kt` already has:
```kotlin
enum class SessionState { Idle, Active, Lost, Closed }

class DiagnosticSession(...) {
    val state: StateFlow<SessionState> = _state
    suspend fun open(diagnosticMode: Int): StartDiagnosticSession.Response
    suspend fun <R> execute(request: KwpRequest<R>): R  // throws SessionException
    ...
}
```

`core/protocol/src/test/kotlin/nl/jwdr/ooc/protocol/session/DiagnosticSessionTest.kt` establishes the test pattern this plan follows: `FakeEcuTransport` scripted with `onFrame(...).respondWith(...)`, small local `bytes()`/`request()`/`response()`/`padded()` helpers, `runTest { ... }` with `backgroundScope`.

`core/transport/.../FakeEcuTransport.kt` scripting API used below:
```kotlin
class FakeEcuTransport(scope: CoroutineScope) : ObdTransport {
    val sentFrames: List<CanFrame>
    fun onFrame(request: CanFrame): ResponseBuilder   // .respondWith(vararg frames: CanFrame)
    suspend fun connect()
}
```

---

## Task 1: `SeedKeyAlgorithm` interface + `DiagnosticSession.unlock` happy paths

**Files:**
- Create: `core/protocol/src/main/kotlin/nl/jwdr/ooc/protocol/kwp2000/SeedKeyAlgorithm.kt`
- Modify: `core/protocol/src/main/kotlin/nl/jwdr/ooc/protocol/session/DiagnosticSession.kt`
- Test: `core/protocol/src/test/kotlin/nl/jwdr/ooc/protocol/session/DiagnosticSessionUnlockTest.kt`

**Interfaces:**
- Produces: `fun interface SeedKeyAlgorithm { fun computeKey(seed: ByteArray, level: Int): ByteArray }` (package `nl.jwdr.ooc.protocol.kwp2000`)
- Produces: `sealed interface UnlockOutcome { data object Unlocked : UnlockOutcome; data object AlreadyUnlocked : UnlockOutcome }` (package `nl.jwdr.ooc.protocol.session`, defined top-level in `DiagnosticSession.kt` next to `SessionState`)
- Produces: `suspend fun DiagnosticSession.unlock(level: Int, algorithm: SeedKeyAlgorithm): UnlockOutcome`

- [ ] **Step 1: Create the `SeedKeyAlgorithm` interface**

```kotlin
package nl.jwdr.ooc.protocol.kwp2000

/**
 * Computes the key for a SecurityAccess seed. Concrete algorithms are
 * proprietary per-ECU and are never committed to this repository — callers
 * supply their own, like imported catalogs.
 */
fun interface SeedKeyAlgorithm {
    /**
     * Computes the key for [seed] at security [level] (the odd access mode
     * used in the seed request). Pure and synchronous — no I/O, no
     * suspension.
     */
    fun computeKey(seed: ByteArray, level: Int): ByteArray
}
```

- [ ] **Step 2: Write the failing tests for the happy paths**

Create `core/protocol/src/test/kotlin/nl/jwdr/ooc/protocol/session/DiagnosticSessionUnlockTest.kt`:

```kotlin
package nl.jwdr.ooc.protocol.session

import kotlinx.coroutines.test.runTest
import nl.jwdr.ooc.protocol.isotp.IsoTpAddress
import nl.jwdr.ooc.protocol.kwp2000.SeedKeyAlgorithm
import nl.jwdr.ooc.transport.CanFrame
import nl.jwdr.ooc.transport.FakeEcuTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticSessionUnlockTest {

    private val address = IsoTpAddress(requestId = 0x241, responseId = 0x641)
    private val pad = 0xAA.toByte()

    /** Adds 1 to each seed byte -- a synthetic stand-in, never a real algorithm. */
    private val incrementAlgorithm = SeedKeyAlgorithm { seed, _ ->
        ByteArray(seed.size) { (seed[it] + 1).toByte() }
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun request(vararg values: Int) = CanFrame(0x241, padded(bytes(*values)))

    private fun response(vararg values: Int) = CanFrame(0x641, padded(bytes(*values)))

    private fun padded(data: ByteArray) =
        if (data.size < 8) data + ByteArray(8 - data.size) { pad } else data

    @Test
    fun `unlock sends the seed request then the computed key`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        transport.onFrame(request(0x02, 0x27, 0x01)).respondWith(response(0x04, 0x67, 0x01, 0xDE, 0xAD))
        transport.onFrame(request(0x04, 0x27, 0x02, 0xDF, 0xAE)).respondWith(response(0x02, 0x67, 0x02))
        transport.connect()
        val session = DiagnosticSession(transport, address, scope = backgroundScope)

        val outcome = session.unlock(level = 0x01, algorithm = incrementAlgorithm)

        assertEquals(UnlockOutcome.Unlocked, outcome)
    }

    @Test
    fun `an all-zero seed means already unlocked and sends no key`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        transport.onFrame(request(0x02, 0x27, 0x01)).respondWith(response(0x04, 0x67, 0x01, 0x00, 0x00))
        transport.connect()
        val session = DiagnosticSession(transport, address, scope = backgroundScope)

        val outcome = session.unlock(level = 0x01, algorithm = incrementAlgorithm)

        assertEquals(UnlockOutcome.AlreadyUnlocked, outcome)
        assertTrue(transport.sentFrames.none { it.data[1] == 0x27.toByte() && it.data[2] == 0x02.toByte() })
    }

    @Test
    fun `unlock rejects an even level before sending any frame`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        transport.connect()
        val session = DiagnosticSession(transport, address, scope = backgroundScope)

        val e = runCatching { session.unlock(level = 0x02, algorithm = incrementAlgorithm) }.exceptionOrNull()

        assertTrue("expected IllegalArgumentException, got $e", e is IllegalArgumentException)
        assertTrue(transport.sentFrames.isEmpty())
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./gradlew :core:protocol:test --tests "nl.jwdr.ooc.protocol.session.DiagnosticSessionUnlockTest"`
Expected: FAIL to compile — `unlock` and `UnlockOutcome` are unresolved references.

- [ ] **Step 4: Implement `UnlockOutcome` and `unlock()`**

In `core/protocol/src/main/kotlin/nl/jwdr/ooc/protocol/session/DiagnosticSession.kt`:

Add these two imports next to the existing `nl.jwdr.ooc.protocol.kwp2000.*` imports:
```kotlin
import nl.jwdr.ooc.protocol.kwp2000.SecurityAccess
import nl.jwdr.ooc.protocol.kwp2000.SeedKeyAlgorithm
```

Add this top-level declaration next to `enum class SessionState`:
```kotlin
/** Result of [DiagnosticSession.unlock]. */
sealed interface UnlockOutcome {
    /** The computed key was accepted. */
    data object Unlocked : UnlockOutcome

    /** The seed came back all-zero: the ECU was already unlocked, no key was sent. */
    data object AlreadyUnlocked : UnlockOutcome
}
```

Add this method inside `class DiagnosticSession`, next to `open`:
```kotlin
/**
 * Unlocks security access at the odd access mode [level]: requests the
 * seed, and unless the ECU reports it is already unlocked, computes the
 * key via [algorithm] and sends it at [level] + 1.
 *
 * @throws SessionException
 */
suspend fun unlock(level: Int, algorithm: SeedKeyAlgorithm): UnlockOutcome {
    require(level % 2 == 1) { "security level must be an odd access mode, got $level" }
    val seed = execute(SecurityAccess.RequestSeed(level))
    if (seed.alreadyUnlocked) return UnlockOutcome.AlreadyUnlocked
    val key = algorithm.computeKey(seed.seed, level)
    execute(SecurityAccess.SendKey(level + 1, key))
    return UnlockOutcome.Unlocked
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :core:protocol:test --tests "nl.jwdr.ooc.protocol.session.DiagnosticSessionUnlockTest"`
Expected: PASS (all 3 tests)

- [ ] **Step 6: Commit**

```bash
git add core/protocol/src/main/kotlin/nl/jwdr/ooc/protocol/kwp2000/SeedKeyAlgorithm.kt \
        core/protocol/src/main/kotlin/nl/jwdr/ooc/protocol/session/DiagnosticSession.kt \
        core/protocol/src/test/kotlin/nl/jwdr/ooc/protocol/session/DiagnosticSessionUnlockTest.kt
git commit -m "Add SeedKeyAlgorithm and DiagnosticSession.unlock happy paths (#17)"
```

---

## Task 2: Typed unlock failure (`SessionException.UnlockFailed`)

**Files:**
- Modify: `core/protocol/src/main/kotlin/nl/jwdr/ooc/protocol/session/SessionException.kt`
- Modify: `core/protocol/src/main/kotlin/nl/jwdr/ooc/protocol/session/DiagnosticSession.kt`
- Test: `core/protocol/src/test/kotlin/nl/jwdr/ooc/protocol/session/DiagnosticSessionUnlockTest.kt`

**Interfaces:**
- Consumes: `suspend fun DiagnosticSession.unlock(level: Int, algorithm: SeedKeyAlgorithm): UnlockOutcome` (Task 1), `SessionException.NegativeResponse(serviceId: Int, error: KwpError)` (existing)
- Produces: `SessionException.UnlockFailed(val error: KwpError)`

- [ ] **Step 1: Write the failing tests for unlock failures**

Add to `DiagnosticSessionUnlockTest.kt` (needs `KwpError` import: `import nl.jwdr.ooc.protocol.kwp2000.KwpError`):

```kotlin
    @Test
    fun `a rejected key surfaces as UnlockFailed with InvalidKey`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        transport.onFrame(request(0x02, 0x27, 0x01)).respondWith(response(0x04, 0x67, 0x01, 0xDE, 0xAD))
        transport.onFrame(request(0x04, 0x27, 0x02, 0xDF, 0xAE)).respondWith(response(0x03, 0x7F, 0x27, 0x35))
        transport.connect()
        val session = DiagnosticSession(transport, address, scope = backgroundScope)

        val e = runCatching { session.unlock(level = 0x01, algorithm = incrementAlgorithm) }.exceptionOrNull()

        assertTrue("expected UnlockFailed, got $e", e is SessionException.UnlockFailed)
        assertEquals(KwpError.InvalidKey, (e as SessionException.UnlockFailed).error)
    }

    @Test
    fun `too many attempts surfaces as UnlockFailed with ExceededNumberOfAttempts`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        transport.onFrame(request(0x02, 0x27, 0x01)).respondWith(response(0x04, 0x67, 0x01, 0xDE, 0xAD))
        transport.onFrame(request(0x04, 0x27, 0x02, 0xDF, 0xAE)).respondWith(response(0x03, 0x7F, 0x27, 0x36))
        transport.connect()
        val session = DiagnosticSession(transport, address, scope = backgroundScope)

        val e = runCatching { session.unlock(level = 0x01, algorithm = incrementAlgorithm) }.exceptionOrNull()

        assertTrue("expected UnlockFailed, got $e", e is SessionException.UnlockFailed)
        assertEquals(KwpError.ExceededNumberOfAttempts, (e as SessionException.UnlockFailed).error)
    }

    @Test
    fun `an unexpired cooldown surfaces as UnlockFailed with RequiredTimeDelayNotExpired`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        transport.onFrame(request(0x02, 0x27, 0x01)).respondWith(response(0x04, 0x67, 0x01, 0xDE, 0xAD))
        transport.onFrame(request(0x04, 0x27, 0x02, 0xDF, 0xAE)).respondWith(response(0x03, 0x7F, 0x27, 0x37))
        transport.connect()
        val session = DiagnosticSession(transport, address, scope = backgroundScope)

        val e = runCatching { session.unlock(level = 0x01, algorithm = incrementAlgorithm) }.exceptionOrNull()

        assertTrue("expected UnlockFailed, got $e", e is SessionException.UnlockFailed)
        assertEquals(KwpError.RequiredTimeDelayNotExpired, (e as SessionException.UnlockFailed).error)
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core:protocol:test --tests "nl.jwdr.ooc.protocol.session.DiagnosticSessionUnlockTest"`
Expected: FAIL — the new tests expect `SessionException.UnlockFailed`, which does not exist yet (compile error), and even once it exists, `unlock()` still throws the un-remapped `SessionException.NegativeResponse` for these cases.

- [ ] **Step 3: Add `SessionException.UnlockFailed`**

In `core/protocol/src/main/kotlin/nl/jwdr/ooc/protocol/session/SessionException.kt`, add a new case inside the sealed class, next to `NegativeResponse`:

```kotlin
    /**
     * A SecurityAccess key submission was rejected: [error] is one of
     * [KwpError.InvalidKey], [KwpError.ExceededNumberOfAttempts], or
     * [KwpError.RequiredTimeDelayNotExpired].
     */
    class UnlockFailed(val error: KwpError) :
        SessionException("security access denied: %s".format(error))
```

- [ ] **Step 4: Remap the `SendKey` negative response in `unlock()`**

In `DiagnosticSession.kt`, change the `unlock()` body's key-sending line from:
```kotlin
    execute(SecurityAccess.SendKey(level + 1, key))
```
to:
```kotlin
    try {
        execute(SecurityAccess.SendKey(level + 1, key))
    } catch (e: SessionException.NegativeResponse) {
        throw SessionException.UnlockFailed(e.error)
    }
```

- [ ] **Step 5: Run the unlock test file to verify all tests pass**

Run: `./gradlew :core:protocol:test --tests "nl.jwdr.ooc.protocol.session.DiagnosticSessionUnlockTest"`
Expected: PASS (all 6 tests)

- [ ] **Step 6: Run the full `:core:protocol` test suite to check for regressions**

Run: `./gradlew :core:protocol:test`
Expected: PASS (no regressions in `SecurityAccessTest`, `DiagnosticSessionTest`, or any other existing test)

- [ ] **Step 7: Commit**

```bash
git add core/protocol/src/main/kotlin/nl/jwdr/ooc/protocol/session/SessionException.kt \
        core/protocol/src/main/kotlin/nl/jwdr/ooc/protocol/session/DiagnosticSession.kt \
        core/protocol/src/test/kotlin/nl/jwdr/ooc/protocol/session/DiagnosticSessionUnlockTest.kt
git commit -m "Remap SecurityAccess key rejection to SessionException.UnlockFailed (#17)"
```
