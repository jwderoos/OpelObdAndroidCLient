# GMLAN DTC Read/Clear (issue #31) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make DTC read/clear work on Astra-H GMLAN body/chassis ECUs, which speak GMLAN `A9 81 12` (readDiagnosticInformation / reportDTCByStatusMask, replies as UUDT frames on the ECU's secondary CAN id) and OBD mode `04` for clearing, not KWP2000's `18`/`14` that `DiagnosticsManager` currently hardcodes for every CAN target.

**Architecture:** Add a GMLAN `readDiagnosticInformation` protocol capability to `:core:protocol/gmlan` (request encode, UUDT `81`-frame decoder, send+collect+timeout orchestration, plus the `ReturnToNormalMode` opener every recorded GMLAN session starts with). `DiagnosticsManager` picks this path per `EcuScanTarget` using the same "has a `secondaryId`" signal it already uses for GMLAN periodic data, falling back to the existing KWP2000 `18`/`14` path when a target has none. Clearing reuses the existing OBD-II `ClearEmissionData` (mode 04) request verbatim — the recorded logs show it produces the exact same `01 04` → `01 44` exchange on the physical id. Two call sites (`EcuListViewModel`, `FaultCodesViewModel`) currently drop `EcuAddress.Can.secondaryId` when building `EcuScanTarget`, which would silently defeat the new path — that wiring gap gets fixed too.

**Tech Stack:** Kotlin/JVM (`:core:protocol`, `:core:catalog`), Android/Compose (`:app`), JUnit4 + kotlinx-coroutines-test, `FakeEcuTransport` for scripted-transport tests, the `ooc-canlog` conformance suite for real-log replay.

**Spec:** GitHub issue [#31](https://github.com/jwderoos/OpelObdAndroidCLient/issues/31)

## Global Constraints

- No-vendor-data policy: never commit real capture bytes. All test fixtures in this plan use the DTC example bytes already published verbatim in issue #31's body (`81 93 25 03 92`, `81 D1 12 00 10`, `81 00 00 00 92`), not values read from local `/logs/`.
- Depends on issue #30 (OP-COM bus-select + RX-filter so the dongle passes 0x5xx frames at all) — already implemented (see commit `a749746`); no work needed here.
- DTC decoding to display text stays catalog-driven (`ErrorCodes`/`textFor`) — this plan only changes what code+symptom numbers reach that lookup, never the lookup itself.
- Destructive clear stays behind the existing explicit-confirmation gate in `FaultCodesViewModel` — this plan does not touch that gate.
- Do not commit: stage changes with `git add` and end with a proposed commit message, per this repo's `CLAUDE.md`.

---

## Task 1: GMLAN DTC decode + request types (`:core:protocol/gmlan`)

**Files:**
- Modify: `core/protocol/src/main/kotlin/nl/jwdr/ooc/protocol/gmlan/GmlanServices.kt`
- Create: `core/protocol/src/main/kotlin/nl/jwdr/ooc/protocol/gmlan/GmlanDiagnosticInformation.kt`
- Create: `core/protocol/src/main/kotlin/nl/jwdr/ooc/protocol/gmlan/ReturnToNormalMode.kt`
- Test: `core/protocol/src/test/kotlin/nl/jwdr/ooc/protocol/gmlan/GmlanDiagnosticInformationMonitorTest.kt`
- Test: `core/protocol/src/test/kotlin/nl/jwdr/ooc/protocol/gmlan/GmlanDtcRequestsTest.kt`

**Interfaces:**
- Produces: `GmlanServices.READ_DIAGNOSTIC_INFORMATION: Int` (0xA9), `GmlanServices.REPORT_DTC_BY_STATUS_MASK: Int` (0x81) — consumed by Task 2 and Task 4.
- Produces: `data class GmlanDtc(val code: Int, val failureType: Int, val status: Int)` — consumed by Task 2, Task 3, Task 4.
- Produces: `class GmlanDiagnosticInformationMonitor(transport: ObdTransport, canId: Int) { val dtcs: Flow<GmlanDtc> }` — consumed by Task 2, Task 3.
- Produces: `data class ReadDiagnosticInformation(val statusMask: Int) { fun encode(): ByteArray }` — consumed by Task 2, Task 4.
- Produces: `object ReturnToNormalMode : KwpRequest<Unit>` — consumed by Task 4.

- [ ] **Step 1: Add the GMLAN service constants**

Edit `core/protocol/src/main/kotlin/nl/jwdr/ooc/protocol/gmlan/GmlanServices.kt`:

```kotlin
package nl.jwdr.ooc.protocol.gmlan

/** GMLAN service ids the stack must recognize in catalog command records. */
object GmlanServices {
    /**
     * ReadDataByPacketIdentifier. Has no USDT positive response: its replies
     * arrive as raw UUDT frames on the ECU's secondary CAN id (see
     * [PeriodicDataMonitor]), so it must be sent without awaiting one.
     */
    const val READ_DATA_BY_PACKET_IDENTIFIER = 0xAA

    /**
     * ReadDiagnosticInformation. Like [READ_DATA_BY_PACKET_IDENTIFIER], its
     * DTC list has no USDT positive response: it arrives as UUDT frames on
     * the ECU's secondary CAN id (see [GmlanDiagnosticInformationMonitor]),
     * so it must be sent without awaiting one.
     */
    const val READ_DIAGNOSTIC_INFORMATION = 0xA9

    /**
     * reportDTCByStatusMask sub-function of [READ_DIAGNOSTIC_INFORMATION].
     * Also the marker byte (byte 0) every UUDT reply frame carries.
     */
    const val REPORT_DTC_BY_STATUS_MASK = 0x81
}
```

- [ ] **Step 2: Write the failing decode test**

Create `core/protocol/src/test/kotlin/nl/jwdr/ooc/protocol/gmlan/GmlanDiagnosticInformationMonitorTest.kt`:

```kotlin
package nl.jwdr.ooc.protocol.gmlan

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import nl.jwdr.ooc.transport.CanFrame
import nl.jwdr.ooc.transport.FakeEcuTransport
import org.junit.Assert.assertEquals
import org.junit.Test

class GmlanDiagnosticInformationMonitorTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun `decodes DTC frames and ignores other ids and markers`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        val trigger = CanFrame(0x249, bytes(0x03, 0xA9, 0x81, 0x12, 0x00, 0x00, 0x00, 0x00))
        transport.onFrame(trigger).respondWith(
            // Response-pending on the ISO-TP response id; not this monitor's concern.
            CanFrame(0x649, bytes(0x03, 0x7F, 0xA9, 0x78, 0x00, 0x00, 0x00, 0x00)),
            CanFrame(0x549, bytes(0x81, 0x93, 0x25, 0x03, 0x92, 0x00, 0x00, 0x00)),
            // A DPID broadcast sharing the id but not the 0x81 marker: ignored.
            CanFrame(0x549, bytes(0x10, 0x0C, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00)),
            CanFrame(0x549, bytes(0x81, 0xD1, 0x12, 0x00, 0x10, 0x00, 0x00, 0x00)),
            CanFrame(0x549, bytes(0x81, 0x00, 0x00, 0x00, 0x92, 0x00, 0x00, 0x00)),
        )
        transport.connect()

        val collected = mutableListOf<GmlanDtc>()
        val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            GmlanDiagnosticInformationMonitor(transport, 0x549).dtcs.collect { collected += it }
        }
        transport.send(trigger)
        testScheduler.runCurrent()
        collector.cancel()

        assertEquals(3, collected.size)
        assertEquals(GmlanDtc(code = 0x9325, failureType = 0x03, status = 0x92), collected[0])
        assertEquals(GmlanDtc(code = 0xD112, failureType = 0x00, status = 0x10), collected[1])
        assertEquals(GmlanDtc(code = 0x0000, failureType = 0x00, status = 0x92), collected[2])
    }
}
```

- [ ] **Step 3: Run it to confirm it fails to compile (the types don't exist yet)**

Run: `./gradlew :core:protocol:test --tests "nl.jwdr.ooc.protocol.gmlan.GmlanDiagnosticInformationMonitorTest"`
Expected: compile error, `GmlanDtc`/`GmlanDiagnosticInformationMonitor` unresolved.

- [ ] **Step 4: Implement the decode layer**

Create `core/protocol/src/main/kotlin/nl/jwdr/ooc/protocol/gmlan/GmlanDiagnosticInformation.kt`:

```kotlin
package nl.jwdr.ooc.protocol.gmlan

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import nl.jwdr.ooc.transport.ObdTransport

/**
 * One DTC reported by GMLAN readDiagnosticInformation/reportDTCByStatusMask
 * (0xA9/0x81). [code] `0x0000` is the end-of-list marker, not a real fault.
 */
data class GmlanDtc(val code: Int, val failureType: Int, val status: Int)

/**
 * Decodes GMLAN readDiagnosticInformation/reportDTCByStatusMask replies:
 * UUDT frames on the ECU's secondary CAN id, one DTC per frame (`81 | code
 * hi | code lo | failure type | status`). Not ISO-TP; like
 * [PeriodicDataMonitor], this observes [ObdTransport.incomingFrames]
 * directly and coexists with an active diagnostic session on the same
 * transport. The end-of-list marker (code `0x0000`) is emitted like any
 * other record — [readDiagnosticInformation] is what stops on it.
 */
class GmlanDiagnosticInformationMonitor(transport: ObdTransport, canId: Int) {

    val dtcs: Flow<GmlanDtc> = transport.incomingFrames
        .filter {
            it.id == canId && it.data.size >= 5 &&
                (it.data[0].toInt() and 0xFF) == GmlanServices.REPORT_DTC_BY_STATUS_MASK
        }
        .map {
            GmlanDtc(
                code = ((it.data[1].toInt() and 0xFF) shl 8) or (it.data[2].toInt() and 0xFF),
                failureType = it.data[3].toInt() and 0xFF,
                status = it.data[4].toInt() and 0xFF,
            )
        }
}

/**
 * readDiagnosticInformation (GMLAN 0xA9), reportDTCByStatusMask (0x81)
 * sub-function: request the DTCs matching [statusMask]. Its reply has no
 * USDT positive response — send it with
 * `DiagnosticSession.readDiagnosticInformation`, not
 * `DiagnosticSession.execute`.
 */
data class ReadDiagnosticInformation(val statusMask: Int) {

    fun encode() = byteArrayOf(
        GmlanServices.READ_DIAGNOSTIC_INFORMATION.toByte(),
        GmlanServices.REPORT_DTC_BY_STATUS_MASK.toByte(),
        statusMask.toByte(),
    )
}
```

- [ ] **Step 5: Run the decode test to confirm it passes**

Run: `./gradlew :core:protocol:test --tests "nl.jwdr.ooc.protocol.gmlan.GmlanDiagnosticInformationMonitorTest"`
Expected: PASS

- [ ] **Step 6: Write the failing request-encode tests**

Create `core/protocol/src/test/kotlin/nl/jwdr/ooc/protocol/gmlan/GmlanDtcRequestsTest.kt`:

```kotlin
package nl.jwdr.ooc.protocol.gmlan

import nl.jwdr.ooc.protocol.kwp2000.KwpNegativeResponseException
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class GmlanDtcRequestsTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun `ReadDiagnosticInformation encodes service, sub-function, and status mask`() {
        val request = ReadDiagnosticInformation(statusMask = 0x12)

        assertArrayEquals(bytes(0xA9, 0x81, 0x12), request.encode())
    }

    @Test
    fun `ReturnToNormalMode encodes the bare service id`() {
        assertArrayEquals(bytes(0x20), ReturnToNormalMode.encode())
    }

    @Test
    fun `ReturnToNormalMode accepts a positive response`() {
        ReturnToNormalMode.decodeResponse(bytes(0x60))
    }

    @Test(expected = KwpNegativeResponseException::class)
    fun `ReturnToNormalMode rejects a negative response`() {
        ReturnToNormalMode.decodeResponse(bytes(0x7F, 0x20, 0x11))
    }
}
```

- [ ] **Step 7: Run it to confirm `ReturnToNormalMode` fails to compile**

Run: `./gradlew :core:protocol:test --tests "nl.jwdr.ooc.protocol.gmlan.GmlanDtcRequestsTest"`
Expected: compile error, `ReturnToNormalMode` unresolved (the `ReadDiagnosticInformation` cases already pass from Step 4).

- [ ] **Step 8: Implement `ReturnToNormalMode`**

Create `core/protocol/src/main/kotlin/nl/jwdr/ooc/protocol/gmlan/ReturnToNormalMode.kt`:

```kotlin
package nl.jwdr.ooc.protocol.gmlan

import nl.jwdr.ooc.protocol.kwp2000.KwpRequest
import nl.jwdr.ooc.protocol.kwp2000.checkPositiveResponse

/**
 * returnToNormalMode (GMLAN 0x20): the opening request of every recorded
 * GMLAN session, sent before ECU identification or DTC reads.
 */
object ReturnToNormalMode : KwpRequest<Unit> {

    override fun encode() = byteArrayOf(0x20)

    override fun decodeResponse(payload: ByteArray) {
        checkPositiveResponse(0x20, payload)
    }
}
```

- [ ] **Step 9: Run both new test files to confirm everything passes**

Run: `./gradlew :core:protocol:test --tests "nl.jwdr.ooc.protocol.gmlan.GmlanDiagnosticInformationMonitorTest" --tests "nl.jwdr.ooc.protocol.gmlan.GmlanDtcRequestsTest"`
Expected: PASS (5 tests total)

- [ ] **Step 10: Commit**

```bash
git add core/protocol/src/main/kotlin/nl/jwdr/ooc/protocol/gmlan/GmlanServices.kt \
        core/protocol/src/main/kotlin/nl/jwdr/ooc/protocol/gmlan/GmlanDiagnosticInformation.kt \
        core/protocol/src/main/kotlin/nl/jwdr/ooc/protocol/gmlan/ReturnToNormalMode.kt \
        core/protocol/src/test/kotlin/nl/jwdr/ooc/protocol/gmlan/GmlanDiagnosticInformationMonitorTest.kt \
        core/protocol/src/test/kotlin/nl/jwdr/ooc/protocol/gmlan/GmlanDtcRequestsTest.kt
git commit -m "protocol: add GMLAN readDiagnosticInformation decode + request types"
```

---

## Task 2: `DiagnosticSession.readDiagnosticInformation` orchestration

**Files:**
- Modify: `core/protocol/src/main/kotlin/nl/jwdr/ooc/protocol/gmlan/GmlanDiagnosticInformation.kt`
- Test: `core/protocol/src/test/kotlin/nl/jwdr/ooc/protocol/gmlan/ReadDiagnosticInformationTest.kt`

**Interfaces:**
- Consumes: `GmlanDiagnosticInformationMonitor`, `GmlanDtc`, `ReadDiagnosticInformation`, `GmlanServices.READ_DIAGNOSTIC_INFORMATION` (Task 1); `DiagnosticSession.sendWithoutResponse(payload: ByteArray)` and `SessionException.ResponseTimeout(serviceId: Int)` (existing `:core:protocol/session`).
- Produces: `suspend fun DiagnosticSession.readDiagnosticInformation(transport: ObdTransport, secondaryId: Int, request: ReadDiagnosticInformation, timeout: Duration): List<GmlanDtc>` — consumed by Task 3 (conformance, indirectly via the monitor it wraps) and Task 4 (`DiagnosticsManager`).

- [ ] **Step 1: Write the failing orchestration tests**

Create `core/protocol/src/test/kotlin/nl/jwdr/ooc/protocol/gmlan/ReadDiagnosticInformationTest.kt`:

```kotlin
package nl.jwdr.ooc.protocol.gmlan

import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import nl.jwdr.ooc.protocol.isotp.IsoTpAddress
import nl.jwdr.ooc.protocol.session.DiagnosticSession
import nl.jwdr.ooc.protocol.session.SessionConfig
import nl.jwdr.ooc.protocol.session.SessionException
import nl.jwdr.ooc.transport.CanFrame
import nl.jwdr.ooc.transport.FakeEcuTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadDiagnosticInformationTest {

    private val address = IsoTpAddress(requestId = 0x249, responseId = 0x649)
    private val secondaryId = 0x549
    private val pad = 0xAA.toByte()

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun padded(data: ByteArray) =
        if (data.size < 8) data + ByteArray(8 - data.size) { pad } else data

    private fun request(vararg values: Int) = CanFrame(0x249, padded(bytes(*values)))

    private fun dtcFrame(vararg values: Int) = CanFrame(secondaryId, padded(bytes(*values)))

    private fun session(transport: FakeEcuTransport, config: SessionConfig = SessionConfig()) =
        DiagnosticSession(transport, address, config = config, scope = backgroundScope)

    @Test
    fun `collects DTCs from the UUDT stream and stops at the end marker`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        transport.onFrame(request(0x03, 0xA9, 0x81, 0x12)).respondWith(
            dtcFrame(0x81, 0x93, 0x25, 0x03, 0x92),
            dtcFrame(0x81, 0xD1, 0x12, 0x00, 0x10),
            dtcFrame(0x81, 0x00, 0x00, 0x00, 0x92),
        )
        transport.connect()

        val dtcs = session(transport).readDiagnosticInformation(
            transport,
            secondaryId,
            ReadDiagnosticInformation(statusMask = 0x12),
            timeout = 1.seconds,
        )

        assertEquals(
            listOf(GmlanDtc(0x9325, 0x03, 0x92), GmlanDtc(0xD112, 0x00, 0x10)),
            dtcs,
        )
    }

    @Test
    fun `throws ResponseTimeout when the end marker never arrives`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        transport.onFrame(request(0x03, 0xA9, 0x81, 0x12)).respondNothing()
        transport.connect()

        val e = runCatching {
            session(transport).readDiagnosticInformation(
                transport,
                secondaryId,
                ReadDiagnosticInformation(statusMask = 0x12),
                timeout = 100.milliseconds,
            )
        }.exceptionOrNull()

        assertTrue("expected ResponseTimeout, got $e", e is SessionException.ResponseTimeout)
    }
}
```

- [ ] **Step 2: Run it to confirm it fails to compile**

Run: `./gradlew :core:protocol:test --tests "nl.jwdr.ooc.protocol.gmlan.ReadDiagnosticInformationTest"`
Expected: compile error, `readDiagnosticInformation` unresolved on `DiagnosticSession`.

- [ ] **Step 3: Implement the orchestration extension**

Edit `core/protocol/src/main/kotlin/nl/jwdr/ooc/protocol/gmlan/GmlanDiagnosticInformation.kt`: add these imports at the top —

```kotlin
import kotlin.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import nl.jwdr.ooc.protocol.session.DiagnosticSession
import nl.jwdr.ooc.protocol.session.SessionException
import nl.jwdr.ooc.transport.ObdTransport
```

— and append this function at the end of the file:

```kotlin
/**
 * Sends [request] fire-and-forget (its reply has no USDT positive response,
 * unlike KWP2000's readDTCByStatus) and collects the DTCs
 * [GmlanDiagnosticInformationMonitor] decodes on [secondaryId], stopping at
 * the DTC `0x0000` end-of-list marker (excluded from the result).
 * Subscribes before sending so no early frame is missed.
 *
 * Throws [SessionException.ResponseTimeout] if the end marker does not
 * arrive within [timeout] — including when the ECU rejects [request]
 * outright, since that would arrive as a negative response on the ISO-TP
 * channel this function never reads (same trade-off as
 * [GmlanServices.READ_DATA_BY_PACKET_IDENTIFIER]'s fire-and-forget send).
 */
suspend fun DiagnosticSession.readDiagnosticInformation(
    transport: ObdTransport,
    secondaryId: Int,
    request: ReadDiagnosticInformation,
    timeout: Duration,
): List<GmlanDtc> = coroutineScope {
    val dtcs = mutableListOf<GmlanDtc>()
    val endOfList = CompletableDeferred<Unit>()
    val collector = launch(start = CoroutineStart.UNDISPATCHED) {
        GmlanDiagnosticInformationMonitor(transport, secondaryId).dtcs.collect { dtc ->
            if (dtc.code == 0) {
                endOfList.complete(Unit)
            } else {
                dtcs += dtc
            }
        }
    }
    sendWithoutResponse(request.encode())
    val completed = withTimeoutOrNull(timeout) { endOfList.await() } != null
    collector.cancel()
    if (!completed) throw SessionException.ResponseTimeout(GmlanServices.READ_DIAGNOSTIC_INFORMATION)
    dtcs.toList()
}
```

- [ ] **Step 4: Run the new tests to confirm they pass**

Run: `./gradlew :core:protocol:test --tests "nl.jwdr.ooc.protocol.gmlan.ReadDiagnosticInformationTest"`
Expected: PASS (2 tests)

- [ ] **Step 5: Run the whole `:core:protocol:gmlan` package to confirm no regression**

Run: `./gradlew :core:protocol:test --tests "nl.jwdr.ooc.protocol.gmlan.*"`
Expected: PASS (all gmlan-package tests, including Task 1's and the pre-existing `PeriodicDataMonitorTest`)

- [ ] **Step 6: Commit**

```bash
git add core/protocol/src/main/kotlin/nl/jwdr/ooc/protocol/gmlan/GmlanDiagnosticInformation.kt \
        core/protocol/src/test/kotlin/nl/jwdr/ooc/protocol/gmlan/ReadDiagnosticInformationTest.kt
git commit -m "protocol: add DiagnosticSession.readDiagnosticInformation send+collect+timeout"
```

---

## Task 3: Conformance test against recorded GMLAN sessions

**Files:**
- Create: `core/protocol/src/test/kotlin/nl/jwdr/ooc/protocol/conformance/GmlanDiagnosticInformationConformanceTest.kt`

**Interfaces:**
- Consumes: `GmlanDiagnosticInformationMonitor`, `GmlanDtc`, `GmlanServices.REPORT_DTC_BY_STATUS_MASK` (Task 1); `driveConformance` (existing `core/protocol/src/test/kotlin/nl/jwdr/ooc/protocol/conformance/ConformanceDriver.kt`).
- Produces: nothing consumed by later tasks — this is a standalone verification suite, mirroring `PeriodicDataConformanceTest.kt`.

This test needs no new production code: it drives the existing `driveConformance` replay machinery (unchanged — GMLAN DTC UUDT frames are on the same 0x5xx id range as periodic data, which `reconstructTesterOps` already drops from the ISO-TP reconstruction) and Task 1's monitor. It skips cleanly with no local logs (CI) and validates byte-for-byte against this machine's local `/logs/` when present.

- [ ] **Step 1: Write the test**

Create `core/protocol/src/test/kotlin/nl/jwdr/ooc/protocol/conformance/GmlanDiagnosticInformationConformanceTest.kt`:

```kotlin
package nl.jwdr.ooc.protocol.conformance

import java.io.File
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import nl.jwdr.ooc.protocol.gmlan.GmlanDiagnosticInformationMonitor
import nl.jwdr.ooc.protocol.gmlan.GmlanDtc
import nl.jwdr.ooc.protocol.gmlan.GmlanServices
import nl.jwdr.ooc.transport.CanLog
import nl.jwdr.ooc.transport.Direction
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Conformance for GMLAN DTC reads (issue #31): while [driveConformance]
 * replays a recorded session's ISO-TP traffic, every recorded
 * readDiagnosticInformation/reportDTCByStatusMask reply on the secondary CAN
 * ids (request id + 0x300 for GMLAN 0x241..0x25F) must reach a
 * [GmlanDiagnosticInformationMonitor] and decode to the recorded DTC — the
 * two listeners share one transport without stealing frames. Skips logs
 * with no A9 traffic, and skips entirely without local logs (clean-room
 * pattern, like [RecordedLogConformanceTest]).
 */
@RunWith(Parameterized::class)
class GmlanDiagnosticInformationConformanceTest(private val logFile: File?) {

    @Test
    fun `recorded DTC UUDT frames reach a monitor alongside the ISO-TP replay`() = runTest {
        assumeTrue("no local logs in logs/ (clean-room skip)", logFile != null)
        val log = CanLog.parse(logFile!!.readText())
        val secondaryIds = log.frames
            .filter { it.direction == Direction.TX && it.frame.id in 0x241..0x25F }
            .map { it.frame.id + 0x300 }
            .distinct()
        val expected = log.frames.filter {
            it.direction == Direction.RX && it.frame.id in secondaryIds &&
                it.frame.data.size >= 5 &&
                (it.frame.data[0].toInt() and 0xFF) == GmlanServices.REPORT_DTC_BY_STATUS_MASK
        }
        assumeTrue("log has no GMLAN DTC traffic", expected.isNotEmpty())

        val collected = mutableListOf<Pair<Int, GmlanDtc>>()
        driveConformance(log, backgroundScope) { transport ->
            for (id in secondaryIds) {
                // UNDISPATCHED: subscribed before playback starts.
                backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    GmlanDiagnosticInformationMonitor(transport, id).dtcs.collect { collected += id to it }
                }
            }
        }
        testScheduler.runCurrent()

        assertEquals(expected.size, collected.size)
        val expectedById = expected.groupBy { it.frame.id }
        val collectedById = collected.groupBy({ it.first }, { it.second })
        for (id in secondaryIds) {
            val expectedForId = expectedById[id].orEmpty()
            val collectedForId = collectedById[id].orEmpty()
            assertEquals("count mismatch for id $id", expectedForId.size, collectedForId.size)
            expectedForId.zip(collectedForId).forEach { (entry, dtc) ->
                val code = ((entry.frame.data[1].toInt() and 0xFF) shl 8) or
                    (entry.frame.data[2].toInt() and 0xFF)
                assertEquals(code, dtc.code)
                assertEquals(entry.frame.data[3].toInt() and 0xFF, dtc.failureType)
                assertEquals(entry.frame.data[4].toInt() and 0xFF, dtc.status)
            }
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun localLogs(): List<Array<File?>> {
            val dir = System.getProperty("ooc.canlogDir")?.let(::File)
            val logs = dir?.listFiles { file -> file.name.endsWith(".canlog") }
                ?.sortedBy { it.name }
                .orEmpty()
            // Parameterized needs at least one entry; a null sentinel keeps
            // the suite visible as skipped when no local logs exist.
            return if (logs.isEmpty()) listOf(arrayOf(null)) else logs.map { arrayOf<File?>(it) }
        }
    }
}
```

- [ ] **Step 2: Run it and confirm the expected outcome for this checkout**

Run: `./gradlew :core:protocol:test --tests "nl.jwdr.ooc.protocol.conformance.GmlanDiagnosticInformationConformanceTest"`
Expected: this machine has local logs under `/logs/` (AHL, hardtop, REC, UEC and others), so the suite runs for real — PASS for every parameterized log. On a clean checkout without `/logs/` it would report SKIPPED instead; either outcome at this point is a pass, a hard FAIL is not.

- [ ] **Step 3: Commit**

```bash
git add core/protocol/src/test/kotlin/nl/jwdr/ooc/protocol/conformance/GmlanDiagnosticInformationConformanceTest.kt
git commit -m "protocol: add conformance suite for GMLAN DTC UUDT replies"
```

---

## Task 4: Wire the GMLAN DTC strategy into `DiagnosticsManager`

**Files:**
- Modify: `app/src/main/java/nl/jwdr/ooc/diagnostics/DiagnosticsManager.kt`
- Modify: `app/src/main/java/nl/jwdr/ooc/diagnostics/EcuScan.kt`
- Test: `app/src/test/java/nl/jwdr/ooc/diagnostics/GmlanDtcTest.kt`

**Interfaces:**
- Consumes: `ReadDiagnosticInformation`, `ReturnToNormalMode`, `GmlanDtc`, `DiagnosticSession.readDiagnosticInformation` (Tasks 1–2); existing `ClearEmissionData` (`:core:protocol/obd2`), `Dtc`/`ReadDTCByStatus`/`ClearDiagnosticInformation` (`:core:protocol/kwp2000`).
- Produces: `DiagnosticsManager.probe/readDtcs/clearDtcs` now branch on `EcuScanTarget.secondaryId` — consumed by Task 5's ViewModels (already wired; Task 5 only fixes two call sites that fail to *provide* a `secondaryId`).

- [ ] **Step 1: Write the failing DiagnosticsManager-level tests**

Create `app/src/test/java/nl/jwdr/ooc/diagnostics/GmlanDtcTest.kt`:

```kotlin
package nl.jwdr.ooc.diagnostics

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import nl.jwdr.ooc.protocol.kwp2000.Dtc
import nl.jwdr.ooc.transport.CanFrame
import nl.jwdr.ooc.transport.FakeEcuTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GMLAN DTC read/clear per the recorded OP-COM sessions (issue #31):
 * readDiagnosticInformation (0xA9, reportDTCByStatusMask 0x81) replies with
 * UUDT frames on the ECU's secondary CAN id, and clearing uses OBD mode 04 —
 * neither goes through KWP2000's readDTCByStatus (0x18) /
 * clearDiagnosticInformation (0x14), which stay in use for targets with no
 * secondary CAN id.
 */
class GmlanDtcTest {

    private val pad = 0xAA.toByte()

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun frame(id: Int, vararg values: Int): CanFrame {
        val data = bytes(*values)
        return CanFrame(id, if (data.size < 8) data + ByteArray(8 - data.size) { pad } else data)
    }

    private val gmlanEcu = EcuScanTarget(
        name = "AHL",
        requestId = 0x249,
        responseId = 0x649,
        secondaryId = 0x549,
    )

    private val returnToNormalRequest = frame(0x249, 0x01, 0x20)
    private val returnToNormalResponse = frame(0x649, 0x01, 0x60)
    private val readRequest = frame(0x249, 0x03, 0xA9, 0x81, 0x12)
    private val clearRequest = frame(0x249, 0x01, 0x04)
    private val clearResponse = frame(0x649, 0x01, 0x44)

    /** [dtcs] as `(code, failureType, status)`, followed by the end-of-list marker. */
    private fun dtcFrames(vararg dtcs: Triple<Int, Int, Int>): List<CanFrame> {
        val records = dtcs.map { (code, failureType, status) ->
            frame(0x549, 0x81, code shr 8, code and 0xFF, failureType, status)
        }
        return records + frame(0x549, 0x81, 0x00, 0x00, 0x00, 0x92)
    }

    @Test
    fun `readDtcs opens with ReturnToNormalMode then decodes the readDiagnosticInformation UUDT stream`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        transport.onFrame(returnToNormalRequest).respondWith(returnToNormalResponse)
        transport.onFrame(readRequest).respondWith(
            dtcFrames(Triple(0x9325, 0x03, 0x92), Triple(0xD112, 0x00, 0x10)),
        )
        transport.connect()
        val manager = DiagnosticsManager(transport)

        val dtcs = manager.readDtcs(gmlanEcu)

        assertEquals(listOf(Dtc(0x9325, 0x03), Dtc(0xD112, 0x00)), dtcs)
    }

    @Test
    fun `readDtcs on a target with no secondaryId still uses readDTCByStatus`() = runTest {
        val kwpEcu = gmlanEcu.copy(secondaryId = null)
        val transport = FakeEcuTransport(backgroundScope)
        transport.onFrame(frame(0x249, 0x04, 0x18, 0x02, 0xFF, 0x00))
            .respondWith(frame(0x649, 0x05, 0x58, 0x01, 0x93, 0x25, 0x03))
        transport.connect()
        val manager = DiagnosticsManager(transport)

        val dtcs = manager.readDtcs(kwpEcu)

        assertEquals(listOf(Dtc(0x9325, 0x03)), dtcs)
        assertTrue(transport.sentFrames.none { it == readRequest })
    }

    @Test
    fun `clearDtcs sends ReturnToNormalMode, mode 04, then re-reads via readDiagnosticInformation`() = runTest {
        var cleared = false
        val transport = FakeEcuTransport(backgroundScope)
        transport.onFrame(returnToNormalRequest).respondWith(returnToNormalResponse)
        transport.onFrame(clearRequest).respondBy {
            cleared = true
            listOf(clearResponse)
        }
        transport.onFrame(readRequest).respondBy {
            if (cleared) dtcFrames() else dtcFrames(Triple(0x9325, 0x03, 0x92))
        }
        transport.connect()
        val manager = DiagnosticsManager(transport)

        val remaining = manager.clearDtcs(gmlanEcu)

        assertEquals(emptyList<Dtc>(), remaining)
        assertTrue(transport.sentFrames.contains(clearRequest))
    }

    @Test
    fun `scanEcus reports a GMLAN ECU's DTC count via readDiagnosticInformation`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        transport.onFrame(returnToNormalRequest).respondWith(returnToNormalResponse)
        transport.onFrame(readRequest).respondWith(dtcFrames(Triple(0x9325, 0x03, 0x92)))
        transport.connect()
        val manager = DiagnosticsManager(transport)

        val result = manager.scanEcus(listOf(gmlanEcu)).toList()

        assertEquals(listOf(EcuScanResult(gmlanEcu, EcuScanStatus.Present(dtcCount = 1))), result)
    }

    @Test
    fun `scanEcus reports Absent for a silent GMLAN ECU`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        // Neither ReturnToNormalMode nor readDiagnosticInformation gets an answer.
        transport.connect()
        val manager = DiagnosticsManager(transport)

        val result = manager.scanEcus(listOf(gmlanEcu)).toList()

        assertEquals(listOf(EcuScanResult(gmlanEcu, EcuScanStatus.Absent)), result)
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "nl.jwdr.ooc.diagnostics.GmlanDtcTest"`
Expected: FAIL — `readDtcs`/`clearDtcs`/`scanEcus` still use the KWP2000-only path, so the GMLAN-targeted tests see no traffic on `readRequest`/`returnToNormalRequest` and time out or return the wrong DTCs (the KWP-fallback test already passes unchanged).

- [ ] **Step 3: Update the `secondaryId` doc comment on `EcuScanTarget`**

Edit `app/src/main/java/nl/jwdr/ooc/diagnostics/EcuScan.kt`:

```kotlin
    /**
     * UUDT broadcast id for GMLAN periodic data and DTC reads (issue #31);
     * null when unknown (OBD-II fallback).
     */
    val secondaryId: Int? = null,
```

replacing the old:

```kotlin
    /** UUDT broadcast id for GMLAN periodic data; null when unknown (OBD-II fallback). */
    val secondaryId: Int? = null,
```

- [ ] **Step 4: Wire the GMLAN branch into `DiagnosticsManager`**

Edit `app/src/main/java/nl/jwdr/ooc/diagnostics/DiagnosticsManager.kt`. Add these imports next to the existing `nl.jwdr.ooc.protocol.gmlan.*` imports:

```kotlin
import nl.jwdr.ooc.protocol.gmlan.ReadDiagnosticInformation
import nl.jwdr.ooc.protocol.gmlan.ReturnToNormalMode
import nl.jwdr.ooc.protocol.gmlan.readDiagnosticInformation
```

Replace the `probe`, `readDtcs`, and `clearDtcs` functions:

```kotlin
    private suspend fun probe(target: EcuScanTarget): EcuScanStatus {
        annotate("scanProbe", target)
        return withSession(target, SCAN_SESSION_CONFIG) { session ->
            try {
                val secondaryId = target.secondaryId
                val dtcCount = if (secondaryId != null) {
                    session.execute(ReturnToNormalMode)
                    session.readGmlanDtcs(secondaryId, SCAN_SESSION_CONFIG.responseTimeout).size
                } else {
                    session.execute(ReadDTCByStatus(DTC_STATUS_ALL, DTC_GROUP_ALL)).dtcs.size
                }
                EcuScanStatus.Present(dtcCount = dtcCount)
            } catch (e: SessionException.NegativeResponse) {
                // It answered, so it exists; it just won't report DTCs this way.
                EcuScanStatus.Present(dtcCount = null)
            } catch (e: SessionException.ResponseTimeout) {
                EcuScanStatus.Absent
            }
        }
    }

    /**
     * Reads the stored DTCs of one known-present ECU. Unlike a scan probe
     * this uses the conversational timeout/retry policy; failures (negative
     * response, timeout) propagate as [SessionException]s. GMLAN ECUs
     * (those with an [EcuScanTarget.secondaryId]) read via
     * readDiagnosticInformation (0xA9); KWP2000 ECUs, if any appear in the
     * catalog, keep readDTCByStatus (0x18) (issue #31).
     */
    suspend fun readDtcs(target: EcuScanTarget): List<Dtc> {
        annotate("readDtcs", target)
        val config = SessionConfig()
        return withSession(target, config) { session ->
            val secondaryId = target.secondaryId
            if (secondaryId != null) {
                session.execute(ReturnToNormalMode)
                session.readGmlanDtcs(secondaryId, config.pendingTimeout)
            } else {
                session.execute(ReadDTCByStatus(DTC_STATUS_ALL, DTC_GROUP_ALL)).dtcs
            }
        }
    }

    /**
     * Clears all stored DTC groups of one ECU, then reads back and returns
     * what it still stores (same session), so the UI shows the ECU's actual
     * state rather than an assumption. Destructive: callers must obtain
     * explicit user confirmation first (design spec safety rule). GMLAN
     * ECUs clear with OBD mode 04 ([ClearEmissionData]), never KWP2000's
     * clearDiagnosticInformation (0x14) (issue #31).
     */
    suspend fun clearDtcs(target: EcuScanTarget): List<Dtc> {
        annotate("clearDtcs", target)
        val config = SessionConfig()
        return withSession(target, config) { session ->
            val secondaryId = target.secondaryId
            if (secondaryId != null) {
                session.execute(ReturnToNormalMode)
                session.execute(ClearEmissionData)
                session.readGmlanDtcs(secondaryId, config.pendingTimeout)
            } else {
                session.execute(ClearDiagnosticInformation(DTC_GROUP_ALL))
                session.execute(ReadDTCByStatus(DTC_STATUS_ALL, DTC_GROUP_ALL)).dtcs
            }
        }
    }

    /**
     * Reads one GMLAN readDiagnosticInformation/reportDTCByStatusMask reply
     * on [secondaryId] and maps it to the shared [Dtc] shape (the GMLAN
     * reply's status byte has no KWP2000 counterpart and is dropped).
     */
    private suspend fun DiagnosticSession.readGmlanDtcs(secondaryId: Int, timeout: Duration): List<Dtc> =
        readDiagnosticInformation(
            transport,
            secondaryId,
            ReadDiagnosticInformation(DTC_STATUS_MASK_ALL),
            timeout,
        ).map { Dtc(code = it.code, symptom = it.failureType) }
```

Add the new mask constant to the existing `private companion object`, next to `DTC_STATUS_ALL`/`DTC_GROUP_ALL`:

```kotlin
        /** readDTCByStatus sub-function: all identified DTCs. */
        const val DTC_STATUS_ALL = 0x02

        /** groupOfDTC covering all groups. */
        const val DTC_GROUP_ALL = 0xFF00

        /** GMLAN reportDTCByStatusMask mask matching all DTCs, as sent by the vendor tool. */
        const val DTC_STATUS_MASK_ALL = 0x12
```

- [ ] **Step 5: Run the new tests to confirm they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "nl.jwdr.ooc.diagnostics.GmlanDtcTest"`
Expected: PASS (5 tests)

- [ ] **Step 6: Run the full diagnostics + live-data + output-test suites to confirm no regression**

Run: `./gradlew :app:testDebugUnitTest --tests "nl.jwdr.ooc.diagnostics.*"`
Expected: PASS (existing `DiagnosticsManagerTest`, `DiagnosticsManagerBusConfigTest`, `LiveDataTest`, `OutputTestRunTest`, `EcuScanTest`, plus the new `GmlanDtcTest`)

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/nl/jwdr/ooc/diagnostics/DiagnosticsManager.kt \
        app/src/main/java/nl/jwdr/ooc/diagnostics/EcuScan.kt \
        app/src/test/java/nl/jwdr/ooc/diagnostics/GmlanDtcTest.kt
git commit -m "diagnostics: read/clear DTCs via GMLAN A9 on targets with a secondaryId"
```

---

## Task 5: Fix the `secondaryId` wiring gap in `EcuListViewModel` / `FaultCodesViewModel`

**Context:** `EcuAddress.Can.secondaryId` (from the imported catalog) is always populated, but `EcuListViewModel.ecusState()` and both `EcuScanTarget`-construction sites in `FaultCodesViewModel` (`read()`, `confirmClear()`) currently drop it when building `EcuScanTarget` — unlike `LiveDataViewModel`/`OutputTestsViewModel`, which already carry it through with `.takeIf { it != 0 }`. Without this fix, Task 4's new GMLAN branch is unreachable from the ECU-list scan screen and the fault-codes screen: every target arrives with `secondaryId = null`, so `DiagnosticsManager` always falls back to the (wrong, for these ECUs) KWP2000 path — the exact bug issue #31 reports.

**Files:**
- Modify: `app/src/main/java/nl/jwdr/ooc/ui/ecus/EcuListViewModel.kt`
- Modify: `app/src/main/java/nl/jwdr/ooc/ui/faultcodes/FaultCodesViewModel.kt`
- Modify: `app/src/test/java/nl/jwdr/ooc/ui/ecus/EcuListViewModelTest.kt`
- Modify: `app/src/test/java/nl/jwdr/ooc/ui/faultcodes/FaultCodesViewModelTest.kt`

**Interfaces:**
- Consumes: `DiagnosticsManager.readDtcs/clearDtcs/scanEcus` (Task 4, already GMLAN-aware); `EcuAddress.Can.secondaryId: Int` (existing, `:core:catalog`).

- [ ] **Step 1: Write the failing regression tests**

Edit `app/src/test/java/nl/jwdr/ooc/ui/ecus/EcuListViewModelTest.kt`: change the `canEcu` helper to accept a `secondaryId`, defaulting to the existing `0`:

```kotlin
    private fun canEcu(name: String, requestId: Int, secondaryId: Int = 0) = EcuEntity(
        catalogId = CatalogEntity.SINGLETON_ID,
        modelYear = "2005",
        vehicle = "Astra-H",
        groupName = "Body",
        name = name,
        systemName = "$name system",
        protocol = "CAN",
        builtinFunction = null,
        catalogKey = null,
        addressType = "CAN",
        canBus = "HSCAN",
        bitRateTenthsKbps = 5000,
        requestId = requestId,
        secondaryId = secondaryId,
        responseId = requestId + 8,
        baudRate = null,
        klineAddress = null,
        initType = null,
        extra = null,
    )
```

Add a new test near `` `a scan connects if needed and reports presence and fault status per ECU` ``:

```kotlin
    @Test
    fun `a scan reports a GMLAN ECU's DTC count via readDiagnosticInformation`() = runTest(dispatcher) {
        storeCatalog(canEcu("AHL", 0x249, secondaryId = 0x549))
        val transport = FakeEcuTransport(backgroundScope)
        transport.onFrame(frame(0x249, 0x01, 0x20)).respondWith(frame(0x649, 0x01, 0x60))
        transport.onFrame(frame(0x249, 0x03, 0xA9, 0x81, 0x12)).respondWith(
            frame(0x549, 0x81, 0x93, 0x25, 0x03, 0x92),
            frame(0x549, 0x81, 0x00, 0x00, 0x00, 0x92),
        )
        val viewModel = viewModel(transport)
        dispatcher.scheduler.advanceUntilIdle()
        selectAstraH2005(viewModel)

        viewModel.startScan()
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.state.value as EcuListUiState.Ecus
        assertEquals(
            listOf(EcuRow("AHL", "AHL system", EcuRowStatus.Present(dtcCount = 1))),
            state.rows,
        )
    }
```

Edit `app/src/test/java/nl/jwdr/ooc/ui/faultcodes/FaultCodesViewModelTest.kt`: change the `canEcu` helper to accept a `secondaryId`, defaulting to the existing `0`:

```kotlin
    private fun canEcu(name: String, requestId: Int, catalogKey: String? = null, secondaryId: Int = 0) = EcuEntity(
        catalogId = CatalogEntity.SINGLETON_ID,
        modelYear = "2005",
        vehicle = "Astra-H",
        groupName = "Body",
        name = name,
        systemName = "$name system",
        protocol = "CAN",
        builtinFunction = null,
        catalogKey = catalogKey,
        addressType = "CAN",
        canBus = "HSCAN",
        bitRateTenthsKbps = 5000,
        requestId = requestId,
        secondaryId = secondaryId,
        responseId = requestId + 8,
        baudRate = null,
        klineAddress = null,
        initType = null,
        extra = null,
    )
```

Add a new test near `` `selecting an ECU reads its DTCs with catalog texts` ``:

```kotlin
    @Test
    fun `a GMLAN-addressed ECU reads its DTCs via readDiagnosticInformation`() = runTest(dispatcher) {
        storeCatalog(ecus = listOf(canEcu("AHL", 0x249, secondaryId = 0x549)))
        val transport = FakeEcuTransport(backgroundScope)
        transport.onFrame(frame(0x249, 0x01, 0x20)).respondWith(frame(0x649, 0x01, 0x60))
        transport.onFrame(frame(0x249, 0x03, 0xA9, 0x81, 0x12)).respondWith(
            frame(0x549, 0x81, 0x93, 0x25, 0x03, 0x92),
            frame(0x549, 0x81, 0x00, 0x00, 0x00, 0x92),
        )
        val viewModel = viewModel(transport)
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.selectEcu("AHL")
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.state.value as FaultCodesUiState.Faults
        // 0x9325 via SAE J2012 (DtcCode.format): top 2 bits '10' -> 'B', next 2 bits '01' -> '1'.
        assertEquals(listOf(FaultEntry("B1325", 3, text = null)), state.entries)
    }
```

- [ ] **Step 2: Run both new tests to confirm they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "nl.jwdr.ooc.ui.ecus.EcuListViewModelTest" --tests "nl.jwdr.ooc.ui.faultcodes.FaultCodesViewModelTest"`
Expected: the two new tests FAIL (no traffic reaches `readRequest`/`returnToNormalRequest`, so they time out); every pre-existing test in both files still passes.

- [ ] **Step 3: Wire `secondaryId` through in `EcuListViewModel`**

Edit `app/src/main/java/nl/jwdr/ooc/ui/ecus/EcuListViewModel.kt`, in `ecusState`:

```kotlin
    private suspend fun ecusState(vehicle: VehicleRef, group: String): EcuListUiState.Ecus {
        val definitions = repository.canEcusFor(vehicle, group)
        targets = definitions.mapNotNull { definition ->
            (definition.address as? EcuAddress.Can)?.let {
                EcuScanTarget(
                    definition.name,
                    it.requestId,
                    it.responseId,
                    // 0 in catalog records that carry no broadcast id.
                    secondaryId = it.secondaryId.takeIf { id -> id != 0 },
                    bus = it.bus,
                )
            }
        }
```

(leave the rest of the function body unchanged)

- [ ] **Step 4: Wire `secondaryId` through in `FaultCodesViewModel`**

Edit `app/src/main/java/nl/jwdr/ooc/ui/faultcodes/FaultCodesViewModel.kt`, in `confirmClear()`:

```kotlin
        clearWith(current) {
            val remaining = diagnosticsManager.clearDtcs(
                EcuScanTarget(
                    definition.name,
                    address.requestId,
                    address.responseId,
                    // 0 in catalog records that carry no broadcast id.
                    secondaryId = address.secondaryId.takeIf { it != 0 },
                    bus = address.bus,
                ),
            )
            faultEntries(definition, remaining)
        }
```

and in `read()`:

```kotlin
                val target = EcuScanTarget(
                    definition.name,
                    address.requestId,
                    address.responseId,
                    // 0 in catalog records that carry no broadcast id.
                    secondaryId = address.secondaryId.takeIf { it != 0 },
                    bus = address.bus,
                )
                val dtcs = diagnosticsManager.readDtcs(target)
```

- [ ] **Step 5: Run the two ViewModel test files to confirm everything passes**

Run: `./gradlew :app:testDebugUnitTest --tests "nl.jwdr.ooc.ui.ecus.EcuListViewModelTest" --tests "nl.jwdr.ooc.ui.faultcodes.FaultCodesViewModelTest"`
Expected: PASS (all tests, old and new)

- [ ] **Step 6: Run the full test suite**

Run: `./gradlew build`
Expected: PASS (all modules; `RecordedLogConformanceTest`/`PeriodicDataConformanceTest`/`GmlanDiagnosticInformationConformanceTest` will actually replay this machine's local `/logs/` rather than skip)

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/nl/jwdr/ooc/ui/ecus/EcuListViewModel.kt \
        app/src/main/java/nl/jwdr/ooc/ui/faultcodes/FaultCodesViewModel.kt \
        app/src/test/java/nl/jwdr/ooc/ui/ecus/EcuListViewModelTest.kt \
        app/src/test/java/nl/jwdr/ooc/ui/faultcodes/FaultCodesViewModelTest.kt
git commit -m "ui: pass the catalog secondaryId through to DiagnosticsManager for CAN ECUs"
```
