# SecurityAccess unlock with pluggable seed/key algorithm

Design for issue #17. Scope: `:core:protocol` only — no facade method, no
plugin registry, no UI. Those land in #18 (coding), the actual consumer of
the unlock.

## Context

The service 0x27 wire codec already exists and is tested:
`kwp2000/SecurityAccess.kt` (`RequestSeed`, `SendKey`) and the relevant
negative-response codes are already in `kwp2000/KwpError.kt`
(`SecurityAccessDenied`, `InvalidKey`, `ExceededNumberOfAttempts`,
`RequiredTimeDelayNotExpired`). What's missing is:

1. A pluggable interface for computing a key from a seed — concrete
   algorithms are proprietary and are never committed to this public repo
   (see the no-vendor-data policy in `CLAUDE.md`); they are user-supplied,
   like decoded catalogs.
2. The unlock orchestration itself: request seed → detect already-unlocked
   → compute key → send key → typed outcome/failure.

## `SeedKeyAlgorithm`

New file `kwp2000/SeedKeyAlgorithm.kt`:

```kotlin
fun interface SeedKeyAlgorithm {
    /**
     * Computes the key for [seed] at security [level] (the odd access mode
     * used in the seed request). Pure and synchronous — no I/O, no
     * suspension; callers may need to bridge blocking crypto themselves.
     */
    fun computeKey(seed: ByteArray, level: Int): ByteArray
}
```

A `fun interface` so a caller can supply one as a lambda. `level` is passed
through because a single ECU may use a different transform per access
level; the algorithm only transforms bytes, it does not know about ECU
identity or transport.

## `DiagnosticSession.unlock`

Added as a method on `DiagnosticSession` (`session/DiagnosticSession.kt`),
alongside `open()`, reusing `execute()` for both requests so it gets the
same locking, retry, and keep-alive-reset behavior as every other request:

```kotlin
suspend fun unlock(level: Int, algorithm: SeedKeyAlgorithm): UnlockOutcome {
    require(level % 2 == 1) { "security level must be an odd access mode, got $level" }
    val seed = execute(SecurityAccess.RequestSeed(level))
    if (seed.alreadyUnlocked) return UnlockOutcome.AlreadyUnlocked
    val key = algorithm.computeKey(seed.seed, level)
    try {
        execute(SecurityAccess.SendKey(level + 1, key))
    } catch (e: SessionException.NegativeResponse) {
        throw SessionException.UnlockFailed(e.error)
    }
    return UnlockOutcome.Unlocked
}
```

`UnlockOutcome` (new file `session/UnlockOutcome.kt` or alongside
`SecurityAccess.kt` in `kwp2000` — implementation detail, not load-bearing):

```kotlin
sealed interface UnlockOutcome {
    data object Unlocked : UnlockOutcome
    data object AlreadyUnlocked : UnlockOutcome
}
```

## Error modeling

A new case is added to the existing `SessionException` sealed hierarchy in
`session/SessionException.kt`:

```kotlin
class UnlockFailed(val error: KwpError) :
    SessionException("security access denied: %s".format(error))
```

Only the `SendKey` negative response is remapped to `UnlockFailed` — a
negative response to the seed *request* itself (e.g.
`ServiceNotSupportedInActiveSession`) surfaces as the ordinary
`SessionException.NegativeResponse`, since that's a session-mode problem,
not a failed unlock attempt. This lets a caller catch `UnlockFailed`
specifically to distinguish "the unlock itself was rejected" (wrong key,
too many attempts, cooldown not expired) from other session failures, and
branch on `.error` for the three specific NRCs without inventing a
parallel enum.

No auto-retry and no automatic wait-out of
`RequiredTimeDelayNotExpired` — that's a UI-level decision for #18.

## Testing

New test file `session/DiagnosticSessionUnlockTest.kt`
(`core/protocol/src/test/kotlin/...`), following the existing
`DiagnosticSessionTest` pattern: a `FakeEcuTransport` scripted with
`onMatch`, and a synthetic in-test `SeedKeyAlgorithm` (e.g. seed bytes each
+1) — never a real algorithm.

Cases:
- Happy path: seed → correct key accepted → `Unlocked`.
- All-zero seed → `AlreadyUnlocked`, and no `SendKey` frame is sent
  (assert on `transport.sentFrames`).
- Wrong key → `SendKey` rejected with `InvalidKey` → `unlock` throws
  `SessionException.UnlockFailed` carrying `KwpError.InvalidKey`.
- `ExceededNumberOfAttempts` and `RequiredTimeDelayNotExpired` on `SendKey`
  surface the same way, confirming no special-casing swallows them.
- `unlock(level = 2, ...)` (even) throws `IllegalArgumentException` before
  any frame is sent.

## Out of scope

- `DiagnosticsManager` facade method exposing `unlock` to the app.
- Any mechanism for a user to supply/register a concrete
  `SeedKeyAlgorithm` at runtime (APK build-time DI, plugin loading, etc.).
- Expert-mode gating / UI.
- Any concrete algorithm implementation.

These are #18's concern, which consumes `DiagnosticSession.unlock` for the
coding read/write flow.
