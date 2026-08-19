# Output-Test Display-Tag Readouts Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show live `**TAG**` display-tag values, decoded from GMLAN periodic-data (DPID) broadcasts on the secondary CAN ID, while a catalog output test runs — and fix the v1 bug where $AA records time out.

**Architecture:** `:core:protocol` gains a fire-and-forget send on `DiagnosticSession` and a `PeriodicDataMonitor` that maps raw secondary-ID frames to `DpidRecord`s. `:core:catalog` gains `DisplayTagBindings`, resolving a tag to (DPID, byte index, data row) from the MBF definitions. `DiagnosticsManager.startOutputTest` wires both into a `readouts: StateFlow<List<TagReadout>>` on `OutputTestRun`; the ViewModel and `RunPanel` display it.

**Tech Stack:** Kotlin/JVM (`:core:*`), Android + Compose (`:app` only), kotlinx-coroutines, JUnit 4, `FakeEcuTransport` / `ReplayTransport` test doubles.

**Spec:** `docs/superpowers/specs/2026-08-19-output-test-display-readouts-design.md`

## Global Constraints

- **Never `git commit`.** Project rule overrides the usual skill workflow: `git add` the files at each commit step; the session ends with a proposed commit message the user applies themselves.
- **No vendor data in committed fixtures.** Test fixtures must be synthetic (invented labels, DPID ids, values). Real recorded logs live only in git-ignored `/logs/` and are consumed by conformance tests that skip when absent.
- JDK 17. `:core:*` modules are pure Kotlin/JVM — no Android imports there.
- TDD: write the failing test first, watch it fail, implement, watch it pass.
- Protocol facts baked into this plan (from the spec): $AA = GMLAN ReadDataByPacketIdentifier, no USDT response; broadcasts are raw frames on the secondary CAN ID, byte 0 = DPID id, bytes 1..7 = data; `MEASDATA` = rate byte + DPID ids; enabled rows spread 7-per-DPID in order; GMLAN request ids 0x241..0x25F pair with secondary ids +0x300 (0x541..0x55F).

---

### Task 1: `PeriodicDataMonitor` + `GmlanServices` (`:core:protocol`)

**Files:**
- Create: `core/protocol/src/main/kotlin/nl/jwdr/ooc/protocol/gmlan/GmlanServices.kt`
- Create: `core/protocol/src/main/kotlin/nl/jwdr/ooc/protocol/gmlan/PeriodicDataMonitor.kt`
- Test: `core/protocol/src/test/kotlin/nl/jwdr/ooc/protocol/gmlan/PeriodicDataMonitorTest.kt`

**Interfaces:**
- Consumes: `ObdTransport.incomingFrames: Flow<CanFrame>`, `CanFrame(id: Int, data: ByteArray)` from `:core:transport`.
- Produces: `GmlanServices.READ_DATA_BY_PACKET_IDENTIFIER: Int` (= 0xAA); `class DpidRecord(val dpid: Int, val data: ByteArray)`; `class PeriodicDataMonitor(transport: ObdTransport, canId: Int)` with `val records: Flow<DpidRecord>`. Tasks 4, 5, and 8 use exactly these names.

- [ ] **Step 1: Write the failing test**

```kotlin
package nl.jwdr.ooc.protocol.gmlan

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import nl.jwdr.ooc.transport.CanFrame
import nl.jwdr.ooc.transport.FakeEcuTransport
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PeriodicDataMonitorTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun `maps secondary-id frames to dpid records and ignores other ids`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        val trigger = CanFrame(0x241, bytes(0x05, 0xAA, 0x03, 0x10, 0x11, 0x00, 0x00, 0x00))
        transport.onFrame(trigger).respondWith(
            CanFrame(0x541, bytes(0x10, 0x0C, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00)),
            CanFrame(0x641, bytes(0x01, 0x7E, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)),
            CanFrame(0x541, bytes(0x11, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)),
        )
        transport.connect()

        val collected = mutableListOf<DpidRecord>()
        val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            PeriodicDataMonitor(transport, 0x541).records.collect { collected += it }
        }
        transport.send(trigger)
        testScheduler.advanceUntilIdle()
        collector.cancel()

        assertEquals(2, collected.size)
        assertEquals(0x10, collected[0].dpid)
        assertArrayEquals(bytes(0x0C, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00), collected[0].data)
        assertEquals(0x11, collected[1].dpid)
        assertArrayEquals(bytes(0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00), collected[1].data)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:protocol:test --tests "nl.jwdr.ooc.protocol.gmlan.PeriodicDataMonitorTest"`
Expected: FAIL to compile — `PeriodicDataMonitor` and `DpidRecord` unresolved.

- [ ] **Step 3: Write the implementation**

`GmlanServices.kt`:

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
}
```

`PeriodicDataMonitor.kt`:

```kotlin
package nl.jwdr.ooc.protocol.gmlan

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import nl.jwdr.ooc.transport.ObdTransport

/** One GMLAN UUDT periodic-data frame: DPID number plus its data bytes. */
class DpidRecord(val dpid: Int, val data: ByteArray)

/**
 * Decodes GMLAN periodic-data broadcasts, scheduled by a
 * ReadDataByPacketIdentifier request, from the raw frames on one secondary
 * CAN id: byte 0 is the DPID number, the rest is data. The frames are not
 * ISO-TP; this observes [ObdTransport.incomingFrames] directly and coexists
 * with an active diagnostic session on the same transport.
 */
class PeriodicDataMonitor(transport: ObdTransport, canId: Int) {

    val records: Flow<DpidRecord> = transport.incomingFrames
        .filter { it.id == canId && it.data.isNotEmpty() }
        .map { DpidRecord(it.data[0].toInt() and 0xFF, it.data.copyOfRange(1, it.data.size)) }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:protocol:test --tests "nl.jwdr.ooc.protocol.gmlan.PeriodicDataMonitorTest"`
Expected: PASS

- [ ] **Step 5: Stage (no commit — project rule)**

```bash
git add core/protocol/src/main/kotlin/nl/jwdr/ooc/protocol/gmlan/ core/protocol/src/test/kotlin/nl/jwdr/ooc/protocol/gmlan/
```

---

### Task 2: `DiagnosticSession.sendWithoutResponse` (`:core:protocol`)

**Files:**
- Modify: `core/protocol/src/main/kotlin/nl/jwdr/ooc/protocol/session/DiagnosticSession.kt` (add one method after `execute`, around line 112)
- Test: `core/protocol/src/test/kotlin/nl/jwdr/ooc/protocol/session/SendWithoutResponseTest.kt`

**Interfaces:**
- Consumes: existing `DiagnosticSession` internals (`requestLock`, `channel`, `idleReset`, `ensureUsable()`, `SessionException`).
- Produces: `suspend fun DiagnosticSession.sendWithoutResponse(payload: ByteArray)` — sends via the ISO-TP channel, returns immediately, throws `SessionException.SessionClosed`/`TransportLost` like `execute`. Task 4 calls it.

- [ ] **Step 1: Write the failing test**

```kotlin
package nl.jwdr.ooc.protocol.session

import kotlinx.coroutines.test.runTest
import nl.jwdr.ooc.protocol.isotp.IsoTpAddress
import nl.jwdr.ooc.transport.CanFrame
import nl.jwdr.ooc.transport.FakeEcuTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SendWithoutResponseTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun `sends the frame and returns without awaiting a response`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        transport.connect()
        val session = DiagnosticSession(
            transport,
            IsoTpAddress(0x241, 0x641),
            scope = backgroundScope,
        )

        // Nothing is scripted to answer: execute() would retry into a
        // ResponseTimeout here; sendWithoutResponse must just return.
        session.sendWithoutResponse(bytes(0xAA, 0x03, 0x10, 0x11))

        assertEquals(
            listOf(CanFrame(0x241, bytes(0x04, 0xAA, 0x03, 0x10, 0x11, 0xAA, 0xAA, 0xAA))),
            transport.sentFrames,
        )
    }

    @Test
    fun `a closed session rejects the send`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        transport.connect()
        val session = DiagnosticSession(
            transport,
            IsoTpAddress(0x241, 0x641),
            scope = backgroundScope,
        )
        session.close()

        val e = runCatching { session.sendWithoutResponse(bytes(0xAA, 0x00)) }.exceptionOrNull()

        assertTrue("expected SessionClosed, got $e", e is SessionException.SessionClosed)
    }
}
```

Note: the expected frame is padded to 8 bytes with the default `IsoTpConfig` pad byte 0xAA (the same convention `OutputTestRunTest` relies on). If the first test's expected frame mismatches on padding, read `IsoTpConfig` for the actual default and fix the *test's* expectation, not the config.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:protocol:test --tests "nl.jwdr.ooc.protocol.session.SendWithoutResponseTest"`
Expected: FAIL to compile — `sendWithoutResponse` unresolved.

- [ ] **Step 3: Implement**

In `DiagnosticSession.kt`, after `execute` (keep its import list as is; nothing new is needed):

```kotlin
    /**
     * Sends [payload] without awaiting any response, for services whose
     * reply is out-of-band (GMLAN readDataByPacketIdentifier answers with
     * UUDT frames on the secondary CAN id, never with a USDT response).
     * Serialized with [execute] callers; resets the keep-alive idle timer.
     *
     * @throws SessionException
     */
    suspend fun sendWithoutResponse(payload: ByteArray) {
        require(payload.isNotEmpty()) { "payload must not be empty" }
        ensureUsable()
        requestLock.withLock {
            ensureUsable()
            try {
                channel.send(payload)
            } catch (e: IllegalStateException) {
                // The transport refuses to send when it is no longer Ready.
                throw SessionException.TransportLost()
            } finally {
                idleReset.trySend(Unit)
            }
        }
    }
```

- [ ] **Step 4: Run the module's tests**

Run: `./gradlew :core:protocol:test`
Expected: PASS (new tests plus no regressions)

- [ ] **Step 5: Stage**

```bash
git add core/protocol/src/main/kotlin/nl/jwdr/ooc/protocol/session/DiagnosticSession.kt core/protocol/src/test/kotlin/nl/jwdr/ooc/protocol/session/SendWithoutResponseTest.kt
```

---

### Task 3: `DisplayTagBindings` resolver (`:core:catalog`)

**Files:**
- Create: `core/catalog/src/main/kotlin/nl/jwdr/ooc/catalog/DisplayTagBindings.kt`
- Modify: `core/catalog/src/main/kotlin/nl/jwdr/ooc/catalog/MeasuringBlockDecoder.kt:40` (make `displayFor` public)
- Test: `core/catalog/src/test/kotlin/nl/jwdr/ooc/catalog/DisplayTagBindingsTest.kt`

**Interfaces:**
- Consumes: `MeasuringBlockCatalog` (`blocks`, `rowsFor`), `MeasuringBlock.measData: List<Int>`, `DataRow.tag: String?` (tag stored without asterisks — matching `OutputTest.displayTags`, also stored without asterisks).
- Produces: `data class TagBinding(val tag: String, val row: DataRow, val dpid: Int, val byteIndex: Int)` and `object DisplayTagBindings { fun resolve(catalog: MeasuringBlockCatalog, tags: List<String>): List<TagBinding> }`; `MeasuringBlockDecoder.displayFor(row: DataRow, raw: Int?): String` becomes public. Tasks 5 and 6 use these.

- [ ] **Step 1: Write the failing test**

```kotlin
package nl.jwdr.ooc.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayTagBindingsTest {

    // Synthetic fixture (no vehicle data), mirroring the documented MBF
    // structure: MEASDATA = scheduling-rate byte + DPID ids; enabled rows
    // spread over the DPIDs at 7 rows per DPID, one byte per row.
    private val mbfText = """
        ; synthetic fixture
        ##MB01=Synthetic List 1
        [begin]
        MEASDATA=03,10,11
        DISABLE_ALL
        ENABLE_RANGE=0001-0009
        [end]

        ##MB02=Synthetic List 2
        [begin]
        MEASDATA=03,20
        DISABLE_ALL
        ENABLE_RANGE=0010-0012
        [end]

        [MEASURING BLOCK DATA]
        Supply Voltage,string,[V]
        Pump Relay,string,Off,On,**PUMP**
        Row Three,string,[%]
        Row Four,string,[%]
        Row Five,string,[%]
        Row Six,string,[%]
        Row Seven,string,[%]
        Motor State,string,Idle,Moving,**MOTOR**
        Row Nine,string,[%]
        Row Ten,string,[%]
        Row Eleven,string,[%]
        Aux State,string,Closed,Open,**AUX**
    """.trimIndent()

    private val catalog = MeasuringBlockParser.parse(mbfText, "SYNTH.MBF.txt")

    @Test
    fun `resolves a tag to its dpid and byte offset within the first dpid`() {
        val bindings = DisplayTagBindings.resolve(catalog, listOf("PUMP"))

        assertEquals(1, bindings.size)
        // Row position 1 (0-based) in MB01's enabled range -> first DPID
        // (0x10), byte 1.
        assertEquals("PUMP", bindings[0].tag)
        assertEquals(0x10, bindings[0].dpid)
        assertEquals(1, bindings[0].byteIndex)
        assertEquals("Pump Relay", bindings[0].row.label)
    }

    @Test
    fun `rows past the seventh map onto the next dpid`() {
        val bindings = DisplayTagBindings.resolve(catalog, listOf("MOTOR"))

        // Row position 7 (0-based) -> second DPID (0x11), byte 0.
        assertEquals(0x11, bindings.single().dpid)
        assertEquals(0, bindings.single().byteIndex)
    }

    @Test
    fun `tags resolve across blocks and keep the requested order`() {
        val bindings = DisplayTagBindings.resolve(catalog, listOf("AUX", "PUMP"))

        assertEquals(listOf("AUX", "PUMP"), bindings.map { it.tag })
        // AUX is position 2 (0-based) in MB02's range -> DPID 0x20, byte 2.
        assertEquals(0x20, bindings[0].dpid)
        assertEquals(2, bindings[0].byteIndex)
    }

    @Test
    fun `unknown tags are skipped`() {
        val bindings = DisplayTagBindings.resolve(catalog, listOf("NOPE", "PUMP"))

        assertEquals(listOf("PUMP"), bindings.map { it.tag })
    }

    @Test
    fun `an empty tag list resolves to nothing`() {
        assertTrue(DisplayTagBindings.resolve(catalog, emptyList()).isEmpty())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:catalog:test --tests "nl.jwdr.ooc.catalog.DisplayTagBindingsTest"`
Expected: FAIL to compile — `DisplayTagBindings` unresolved.

(If `MeasuringBlockParser.parse` rejects the fixture, check its signature/format expectations against an existing test in `core/catalog/src/test/` and adjust the fixture text — not the parser.)

- [ ] **Step 3: Implement**

`DisplayTagBindings.kt`:

```kotlin
package nl.jwdr.ooc.catalog

/**
 * Where one output-test display tag lives in the GMLAN periodic-data stream:
 * the DPID whose broadcast carries it and the 0-based offset into that
 * DPID's data bytes, plus the data row that decodes the byte.
 */
data class TagBinding(
    val tag: String,
    val row: DataRow,
    val dpid: Int,
    val byteIndex: Int,
)

/**
 * Resolves output-test `**TAG**` display tags against a measuring-block
 * catalog. MEASDATA is a scheduling-rate byte followed by DPID ids, and a
 * block's enabled rows spread over those DPIDs in table order at
 * [ROWS_PER_DPID] rows per DPID, one byte per row (verified against
 * recorded sessions; see the 2026-08-19 design spec).
 */
object DisplayTagBindings {

    /** A UUDT frame carries the DPID id plus 7 data bytes. */
    const val ROWS_PER_DPID = 7

    /**
     * One binding per element of [tags] that a tagged data row matches,
     * in [tags] order; unmatched tags are skipped.
     */
    fun resolve(catalog: MeasuringBlockCatalog, tags: List<String>): List<TagBinding> {
        if (tags.isEmpty()) return emptyList()
        val found = mutableMapOf<String, TagBinding>()
        for (block in catalog.blocks) {
            // measData[0] is the scheduling rate, not a DPID.
            val dpids = block.measData.drop(1)
            if (dpids.isEmpty()) continue
            catalog.rowsFor(block).forEachIndexed { position, row ->
                val tag = row.tag ?: return@forEachIndexed
                if (tag !in tags || tag in found) return@forEachIndexed
                val dpidIndex = position / ROWS_PER_DPID
                if (dpidIndex >= dpids.size) return@forEachIndexed
                found[tag] = TagBinding(tag, row, dpids[dpidIndex], position % ROWS_PER_DPID)
            }
        }
        return tags.mapNotNull(found::get)
    }
}
```

In `MeasuringBlockDecoder.kt`, change the visibility of `displayFor` (line 40) from `private fun` to `fun` and give it a doc line:

```kotlin
    /** Display text for one raw byte of [row]: state label, decimal value, or placeholder. */
    fun displayFor(row: DataRow, raw: Int?): String = when {
```

- [ ] **Step 4: Run the module's tests**

Run: `./gradlew :core:catalog:test`
Expected: PASS

- [ ] **Step 5: Stage**

```bash
git add core/catalog/src/main/kotlin/nl/jwdr/ooc/catalog/DisplayTagBindings.kt core/catalog/src/main/kotlin/nl/jwdr/ooc/catalog/MeasuringBlockDecoder.kt core/catalog/src/test/kotlin/nl/jwdr/ooc/catalog/DisplayTagBindingsTest.kt
```

---

### Task 4: $AA dispatch fix + `secondaryId` on `EcuScanTarget` (`:app`)

Fixes the v1 bug: `AA` records sent through `execute(RawRequest(...))` wait for a positive response GMLAN never sends and die with `ResponseTimeout`.

**Files:**
- Modify: `app/src/main/java/nl/jwdr/ooc/diagnostics/EcuScan.kt:4-8` (add `secondaryId`)
- Modify: `app/src/main/java/nl/jwdr/ooc/diagnostics/DiagnosticsManager.kt` (before-test loop ~line 182, `OutputTestRun.send` ~line 379)
- Test: `app/src/test/java/nl/jwdr/ooc/diagnostics/OutputTestRunTest.kt` (add one test)

**Interfaces:**
- Consumes: `GmlanServices.READ_DATA_BY_PACKET_IDENTIFIER` (Task 1), `DiagnosticSession.sendWithoutResponse` (Task 2).
- Produces: `EcuScanTarget(name, requestId, responseId, secondaryId: Int? = null)`; a file-private `suspend fun DiagnosticSession.sendRecord(record: CommandRecord)` in `DiagnosticsManager.kt` used by both the before-test loop and `OutputTestRun.send` (Task 5 keeps using it).

- [ ] **Step 1: Write the failing test** (append to `OutputTestRunTest`; its helpers `record`, `frame`, `rec`, `test`, `scriptedTransport` already exist at the top of the file)

```kotlin
    @Test
    fun `readDataByPacketIdentifier records are sent without awaiting a response`() = runTest {
        // GMLAN $AA never answers on the diagnostic response id (its reply is
        // the UUDT stream on the secondary id), so nothing is scripted for
        // these frames — v1 would retry into a ResponseTimeout here.
        // 4-byte payload -> single-frame PCI 0x04.
        val scheduleFrame = frame(0x241, 0x04, 0xAA, 0x03, 0x10, 0x11)
        val stopFrame = frame(0x241, 0x02, 0xAA, 0x00)
        val transport = scriptedTransport(backgroundScope)
        transport.connect()
        val manager = DiagnosticsManager(transport)
        val withPeriodicData = test.copy(
            beforeTest = listOf(record(0xAA, 0x03, 0x10, 0x11)) + test.beforeTest,
            afterTest = test.afterTest + listOf(record(0xAA, 0x00)),
        )

        val run = manager.startOutputTest(rec, withPeriodicData)
        run.finish()

        assertTrue(transport.sentFrames.contains(scheduleFrame))
        assertTrue(transport.sentFrames.contains(stopFrame))
        assertTrue(transport.sentFrames.contains(beforeFrame))
        assertTrue(transport.sentFrames.contains(afterFrame))
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "nl.jwdr.ooc.diagnostics.OutputTestRunTest"`
Expected: the new test FAILS with `SessionException.ResponseTimeout` (it may take retries under virtual time; the other tests still pass).

- [ ] **Step 3: Implement**

`EcuScan.kt` — add the field:

```kotlin
/** One ECU to probe during a bus scan: display identity plus its CAN channel. */
data class EcuScanTarget(
    val name: String,
    val requestId: Int,
    val responseId: Int,
    /** UUDT broadcast id for GMLAN periodic data; null when unknown (OBD-II fallback). */
    val secondaryId: Int? = null,
)
```

`DiagnosticsManager.kt` — add imports `nl.jwdr.ooc.protocol.gmlan.GmlanServices` (plus, for later tasks in this file, keep the import block tidy). Add a file-private helper next to the existing `CommandRecord.toPayload()` at the bottom:

```kotlin
/**
 * Sends one catalog command record: GMLAN readDataByPacketIdentifier gets no
 * USDT response (its reply is the UUDT stream on the secondary id), so it
 * goes out fire-and-forget; everything else awaits its positive response.
 */
private suspend fun DiagnosticSession.sendRecord(record: CommandRecord) {
    if (record.significantBytes.isEmpty()) return
    val payload = record.toPayload()
    if ((payload[0].toInt() and 0xFF) == GmlanServices.READ_DATA_BY_PACKET_IDENTIFIER) {
        sendWithoutResponse(payload)
    } else {
        execute(RawRequest(payload))
    }
}
```

Replace the before-test loop in `startOutputTest`:

```kotlin
        try {
            for (record in test.beforeTest) {
                session.sendRecord(record)
            }
        } catch (e: Throwable) {
```

Replace `OutputTestRun.send`:

```kotlin
    private suspend fun send(records: List<CommandRecord>) {
        for (record in records) {
            session.sendRecord(record)
        }
    }
```

(`DiagnosticSession` needs to be imported already — it is. `import nl.jwdr.ooc.protocol.session.DiagnosticSession` exists since the class is constructed in this file.)

- [ ] **Step 4: Run the app unit tests**

Run: `./gradlew :app:testDebugUnitTest --tests "nl.jwdr.ooc.diagnostics.*"`
Expected: PASS, including all pre-existing `OutputTestRunTest` cases.

- [ ] **Step 5: Stage**

```bash
git add app/src/main/java/nl/jwdr/ooc/diagnostics/EcuScan.kt app/src/main/java/nl/jwdr/ooc/diagnostics/DiagnosticsManager.kt app/src/test/java/nl/jwdr/ooc/diagnostics/OutputTestRunTest.kt
```

---

### Task 5: `readouts` StateFlow on `OutputTestRun` (`:app`)

**Files:**
- Modify: `app/src/main/java/nl/jwdr/ooc/diagnostics/DiagnosticsManager.kt` (`startOutputTest` ~line 173, `OutputTestRun` ~line 354, new `TagReadout` near `Obd2Value`)
- Test: `app/src/test/java/nl/jwdr/ooc/diagnostics/OutputTestRunTest.kt` (add one test)

**Interfaces:**
- Consumes: `PeriodicDataMonitor`, `DpidRecord` (Task 1); `TagBinding`, `MeasuringBlockDecoder.displayFor`, `MeasuringBlockDecoder.NO_DATA` (Task 3); `EcuScanTarget.secondaryId` (Task 4).
- Produces: `data class TagReadout(val binding: TagBinding, val raw: Int?, val display: String)`; `DiagnosticsManager.startOutputTest(target, test, bindings: List<TagBinding> = emptyList())`; `OutputTestRun.readouts: StateFlow<List<TagReadout>>`. Task 6 consumes all three.

- [ ] **Step 1: Write the failing test** (append to `OutputTestRunTest`)

```kotlin
    @Test
    fun `periodic data broadcasts update the tag readouts`() = runTest {
        val transport = scriptedTransport(backgroundScope)
        // The schedule request "answers" with UUDT broadcasts on the
        // secondary id — DPID byte first, then 7 data bytes, no padding.
        val scheduleFrame = frame(0x241, 0x04, 0xAA, 0x03, 0x10, 0x11)
        transport.onFrame(scheduleFrame).respondWith(
            CanFrame(0x541, bytes(0x10, 0x0C, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00)),
            CanFrame(0x541, bytes(0x11, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)),
        )
        transport.connect()
        val manager = DiagnosticsManager(transport)
        val pumpRow = DataRow(label = "Pump Relay", states = listOf("Off", "On"))
        val motorRow = DataRow(label = "Motor State", states = listOf("Idle", "Moving"))
        val bindings = listOf(
            TagBinding("PUMP", pumpRow, dpid = 0x10, byteIndex = 1),
            TagBinding("MOTOR", motorRow, dpid = 0x11, byteIndex = 0),
        )
        val withPeriodicData = test.copy(
            beforeTest = listOf(record(0xAA, 0x03, 0x10, 0x11)) + test.beforeTest,
            displayTags = listOf("PUMP", "MOTOR"),
        )
        val target = rec.copy(secondaryId = 0x541)

        val run = manager.startOutputTest(target, withPeriodicData, bindings)
        testScheduler.runCurrent()
        val readouts = run.readouts.value
        run.finish()

        assertEquals(listOf("On", "Moving"), readouts.map { it.display })
        assertEquals(listOf(0x01, 0x01), readouts.map { it.raw })
    }

    @Test
    fun `without bindings the readouts stay empty`() = runTest {
        val transport = scriptedTransport(backgroundScope)
        transport.connect()
        val manager = DiagnosticsManager(transport)

        val run = manager.startOutputTest(rec, test)
        val readouts = run.readouts.value
        run.finish()

        assertEquals(emptyList<TagReadout>(), readouts)
    }
```

Add the needed imports to the test file: `nl.jwdr.ooc.catalog.DataRow`, `nl.jwdr.ooc.catalog.TagBinding`.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "nl.jwdr.ooc.diagnostics.OutputTestRunTest"`
Expected: FAIL to compile — `readouts`, `TagReadout`, third `startOutputTest` parameter unresolved.

- [ ] **Step 3: Implement**

In `DiagnosticsManager.kt`, add imports:

```kotlin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import nl.jwdr.ooc.catalog.MeasuringBlockDecoder
import nl.jwdr.ooc.catalog.TagBinding
import nl.jwdr.ooc.protocol.gmlan.PeriodicDataMonitor
```

(`StateFlow` is already imported for `isSimulated`; `MutableStateFlow`/`update` may be too — check the existing import block and add only what's missing.)

Add near `Obd2Value` at the bottom of the file:

```kotlin
/** One live display-tag reading shown while an output test runs. */
data class TagReadout(
    val binding: TagBinding,
    /** Unsigned raw byte from the DPID broadcast, or null before the first one. */
    val raw: Int?,
    val display: String,
)
```

Rework `startOutputTest` (the KDoc keeps its safety-gate wording; add a sentence about readouts):

```kotlin
    /**
     * Starts one catalog output test on [target]: opens a session, runs the
     * test's before-test records, and returns a handle for the interactive
     * phase. Actuates vehicle hardware: callers must obtain explicit user
     * confirmation, showing the test's pre-test instructions, first (design
     * spec safety rule). The caller must always call [OutputTestRun.finish],
     * which runs the teardown records and closes the session.
     *
     * [bindings] (from [nl.jwdr.ooc.catalog.DisplayTagBindings]) enable the
     * live display-tag readouts on [OutputTestRun.readouts], decoded from the
     * GMLAN periodic-data broadcasts on [EcuScanTarget.secondaryId] that the
     * script's readDataByPacketIdentifier records schedule.
     */
    suspend fun startOutputTest(
        target: EcuScanTarget,
        test: OutputTest,
        bindings: List<TagBinding> = emptyList(),
    ): OutputTestRun {
        val sessionScope = CoroutineScope(currentCoroutineContext() + Job())
        val session = DiagnosticSession(
            transport,
            IsoTpAddress(target.requestId, target.responseId),
            config = SessionConfig(),
            scope = sessionScope,
        )
        val readouts = MutableStateFlow(
            bindings.map { TagReadout(it, raw = null, display = MeasuringBlockDecoder.NO_DATA) },
        )
        // Subscribe before the before-test records go out: the script's AA
        // schedule record starts the broadcasts immediately.
        if (bindings.isNotEmpty() && target.secondaryId != null) {
            val monitor = PeriodicDataMonitor(transport, target.secondaryId)
            sessionScope.launch {
                monitor.records.collect { record ->
                    readouts.update { current ->
                        current.map { readout ->
                            if (readout.binding.dpid != record.dpid) return@map readout
                            val raw = record.data.getOrNull(readout.binding.byteIndex)
                                ?.toInt()?.and(0xFF)
                            TagReadout(
                                readout.binding,
                                raw,
                                MeasuringBlockDecoder.displayFor(readout.binding.row, raw),
                            )
                        }
                    }
                }
            }
        }
        try {
            for (record in test.beforeTest) {
                session.sendRecord(record)
            }
        } catch (e: Throwable) {
            session.close()
            sessionScope.cancel()
            throw e
        }
        // Recorded sessions hold the test mode with the periodic GMLAN
        // all-nodes testerPresent broadcast (the ECU's 7E answers on the
        // diagnostic id are skipped as stale replies), not a per-ECU 3E.
        sessionScope.launch {
            while (true) {
                delay(ALL_NODES_TESTER_PRESENT_INTERVAL)
                transport.send(ALL_NODES_TESTER_PRESENT)
            }
        }
        return OutputTestRun(test, session, sessionScope, readouts)
    }
```

Extend `OutputTestRun`:

```kotlin
class OutputTestRun internal constructor(
    private val test: OutputTest,
    private val session: DiagnosticSession,
    private val sessionScope: CoroutineScope,
    /** Live display-tag readings; empty when the test has no resolvable tags. */
    val readouts: StateFlow<List<TagReadout>> = MutableStateFlow(emptyList()),
) {
```

(the rest of the class is unchanged; `StateFlow` import already exists at file level).

- [ ] **Step 4: Run the app unit tests**

Run: `./gradlew :app:testDebugUnitTest --tests "nl.jwdr.ooc.diagnostics.*"`
Expected: PASS

- [ ] **Step 5: Stage**

```bash
git add app/src/main/java/nl/jwdr/ooc/diagnostics/DiagnosticsManager.kt app/src/test/java/nl/jwdr/ooc/diagnostics/OutputTestRunTest.kt
```

---

### Task 6: ViewModel wiring (`:app`)

**Files:**
- Modify: `app/src/main/java/nl/jwdr/ooc/ui/outputtests/OutputTestsViewModel.kt`
- Test: `app/src/test/java/nl/jwdr/ooc/ui/outputtests/OutputTestsViewModelTest.kt` (add one test + extend two helpers)

**Interfaces:**
- Consumes: `startOutputTest(target, test, bindings)`, `OutputTestRun.readouts`, `TagReadout` (Task 5); `DisplayTagBindings.resolve` (Task 3); `CatalogRepository.measuringBlocksFor(catalogKey)`; `EcuAddress.Can.secondaryId: Int` (0 in files that carry none).
- Produces: `OutputTestsUiState.Running.readouts: List<TagReadout>` — Task 7's UI reads it.

- [ ] **Step 1: Write the failing test**

In `OutputTestsViewModelTest`, extend the `canEcu` helper with a parameter (default keeps existing call sites compiling):

```kotlin
    private fun canEcu(
        name: String,
        requestId: Int,
        catalogKey: String? = null,
        secondaryId: Int = 0,
    ) = EcuEntity(
        // ... all existing fields unchanged, except:
        secondaryId = secondaryId,
```

Add a measuring-blocks file helper next to `outputTestsFile`:

```kotlin
    private fun measuringBlocksFile(fileKey: String, text: String) = CatalogFileEntity(
        catalogId = CatalogEntity.SINGLETON_ID,
        kind = "MEASURING_BLOCKS",
        fileKey = fileKey,
        fileName = "$fileKey.MBF.txt",
        content = text.toByteArray(Charsets.ISO_8859_1),
    )
```

Add the test (reusing the file's `frame`/`bytes`/`settle` helpers and `scriptText` style):

```kotlin
    private val taggedScriptText = """
        ;KW2000
        Pump Test With Readouts
        [TESTTYPE=ONOFF]
        [begin]
        **PUMP**
        BeforeTest=	0x04,0xAA,0x03,0x10,0x11,0x00,0x00,0x00,
        BeforeTest=	0x03,0xAE,0x01,0x00,0x00,0x00,0x00,0x00,
        GoActivate=	0x06,0xAE,0x02,0x02,0x00,0x00,0x00,0x00,
        DeActivate=	0x06,0xAE,0x02,0x00,0x00,0x00,0x00,0x00,
        AfterTest=	0x03,0xAE,0x01,0x0C,0x00,0x00,0x00,0x00,
        AfterTest=	0x02,0xAA,0x00,0x00,0x00,0x00,0x00,0x00,
        [end]
    """.trimIndent()

    private val taggedMbfText = """
        ; synthetic
        ##MB01=Synthetic List
        [begin]
        MEASDATA=03,10,11
        DISABLE_ALL
        ENABLE_RANGE=0001-0002
        [end]

        [MEASURING BLOCK DATA]
        Supply Voltage,string,[V]
        Pump Relay,string,Off,On,**PUMP**
    """.trimIndent()

    @Test
    fun `a running test shows live display-tag readouts`() = runTest(dispatcher) {
        storeCatalog(
            listOf(canEcu("REC", 0x240, "RECKEY", secondaryId = 0x540)),
            listOf(
                outputTestsFile("RECKEY", taggedScriptText),
                measuringBlocksFile("RECKEY", taggedMbfText),
            ),
        )
        val transport = scriptedTransport(backgroundScope)
        // The script's 4-significant-byte AA record -> single-frame PCI 0x04.
        val scheduleFrame = frame(0x240, 0x04, 0xAA, 0x03, 0x10, 0x11)
        transport.onFrame(scheduleFrame).respondWith(
            CanFrame(0x540, bytes(0x10, 0x0C, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00)),
        )
        val viewModel = viewModel(transport)
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.selectEcu("REC")
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.requestStart(0)
        viewModel.confirmStart()
        settle()

        val running = viewModel.state.value as OutputTestsUiState.Running
        assertEquals(1, running.readouts.size)
        assertEquals("Pump Relay", running.readouts[0].binding.row.label)
        // DPID 0x10 byte 1 = 0x01 -> the "On" state label.
        assertEquals("On", running.readouts[0].display)

        viewModel.stop()
        settle()
    }
```

Add imports the test needs: `nl.jwdr.ooc.diagnostics.TagReadout` (if referenced), and nothing else new.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "nl.jwdr.ooc.ui.outputtests.OutputTestsViewModelTest"`
Expected: FAIL to compile — `Running.readouts` unresolved.

- [ ] **Step 3: Implement**

In `OutputTestsViewModel.kt`:

Add imports: `kotlinx.coroutines.Job`, `nl.jwdr.ooc.catalog.DisplayTagBindings`, `nl.jwdr.ooc.diagnostics.TagReadout`.

Extend `Running`:

```kotlin
    /** One test's interactive phase. */
    data class Running(
        val ecuName: String,
        val test: OutputTest,
        /** The actuator is activated (meaningful for ONOFF tests). */
        val active: Boolean = false,
        /** A control command is on the bus. */
        val busy: Boolean = false,
        val error: UserMessage? = null,
        /** Live display-tag readings; empty when the test has none. */
        val readouts: List<TagReadout> = emptyList(),
    ) : OutputTestsUiState
```

Add a field next to `run`:

```kotlin
    private var readoutsJob: Job? = null
```

In `confirmStart`, resolve bindings and pass the secondary id (replace the `started` block):

```kotlin
                val bindings = if (test.displayTags.isEmpty()) {
                    emptyList()
                } else {
                    definition.catalogKey?.let { repository.measuringBlocksFor(it) }
                        ?.let { DisplayTagBindings.resolve(it, test.displayTags) }
                        .orEmpty()
                }
                val started = diagnosticsManager.startOutputTest(
                    EcuScanTarget(
                        definition.name,
                        address.requestId,
                        address.responseId,
                        // 0 in catalog records that carry no broadcast id.
                        address.secondaryId.takeIf { it != 0 },
                    ),
                    test,
                    bindings,
                )
```

After `run = started` / before setting the `Running` state, start the collector:

```kotlin
                run = started
                readoutsJob = viewModelScope.launch {
                    started.readouts.collect { readouts ->
                        _state.update { s ->
                            if (s is OutputTestsUiState.Running) s.copy(readouts = readouts) else s
                        }
                    }
                }
                _state.value =
                    OutputTestsUiState.Running(current.ecuName, test, readouts = started.readouts.value)
```

In `finishRun`, cancel it first:

```kotlin
    private suspend fun finishRun() {
        readoutsJob?.cancel()
        readoutsJob = null
        run?.let { active ->
```

(`onCleared` needs no change: `viewModelScope` cancellation already kills the collector.)

Note the `control` function's `_state.value = current.copy(...)` calls copy a **stale** `current` captured before suspension — they would wipe readout updates that arrived while the control command was on the bus. Change `control` and `stop` to update via `_state.update` against the live state:

```kotlin
    private fun control(activate: Boolean) {
        val current = _state.value as? OutputTestsUiState.Running ?: return
        val run = run ?: return
        if (current.busy) return
        viewModelScope.launch {
            _state.update { s ->
                if (s is OutputTestsUiState.Running) s.copy(busy = true, error = null) else s
            }
            try {
                if (activate) run.activate() else run.deactivate()
                _state.update { s ->
                    if (s is OutputTestsUiState.Running) {
                        s.copy(active = activate, busy = false, error = null)
                    } else {
                        s
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { s ->
                    if (s is OutputTestsUiState.Running) {
                        s.copy(busy = false, error = userMessageFor(e))
                    } else {
                        s
                    }
                }
            }
        }
    }
```

In `stop`, only the initial `_state.value = current.copy(busy = true, error = null)` needs the same `_state.update` treatment; its terminal states replace `Running` entirely and stay as they are.

- [ ] **Step 4: Run the ViewModel tests**

Run: `./gradlew :app:testDebugUnitTest --tests "nl.jwdr.ooc.ui.outputtests.OutputTestsViewModelTest"`
Expected: PASS, all pre-existing cases included.

- [ ] **Step 5: Stage**

```bash
git add app/src/main/java/nl/jwdr/ooc/ui/outputtests/OutputTestsViewModel.kt app/src/test/java/nl/jwdr/ooc/ui/outputtests/OutputTestsViewModelTest.kt
```

---

### Task 7: Run-panel readout rows (`:app` UI)

**Files:**
- Modify: `app/src/main/java/nl/jwdr/ooc/ui/outputtests/OutputTestsScreen.kt:249` (inside `RunPanel`, after the `activeLabels` block)

**Interfaces:**
- Consumes: `OutputTestsUiState.Running.readouts: List<TagReadout>` (Task 6). No new strings, no navigation changes.

- [ ] **Step 1: Implement** (pure presentation; the state logic is already tested — the check here is compilation + the full build)

Insert after the `state.test.activeLabels.forEach { ... }` block in `RunPanel`:

```kotlin
        if (state.readouts.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                state.readouts.forEach { readout ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = readout.binding.row.label,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = readout.display,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
        }
```

`Column`, `Row`, `Arrangement`, `Modifier`, `MaterialTheme`, `Text`, `fillMaxWidth` are already imported in this file; add `androidx.compose.foundation.layout.Spacer`-style missing imports only if the compiler asks (check the existing import list — `weight` comes with `RowScope`, nothing to import).

- [ ] **Step 2: Build the app**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Stage**

```bash
git add app/src/main/java/nl/jwdr/ooc/ui/outputtests/OutputTestsScreen.kt
```

---

### Task 8: Periodic-data conformance over recorded logs (`:core:protocol`)

Verifies against real OP-COM captures that periodic-data frames flow to a monitor **while** the ISO-TP session replays on the same transport, and that the DPID mapping matches every recorded broadcast.

**Files:**
- Modify: `core/protocol/src/test/kotlin/nl/jwdr/ooc/protocol/conformance/ConformanceDriver.kt` (add an `onTransport` hook)
- Test: `core/protocol/src/test/kotlin/nl/jwdr/ooc/protocol/conformance/PeriodicDataConformanceTest.kt`

**Interfaces:**
- Consumes: `driveConformance`, `CanLog` / `LoggedFrame` / `Direction`, `PeriodicDataMonitor` / `DpidRecord` (Task 1).
- Produces: `driveConformance(log, scope, onTransport: (ObdTransport) -> Unit = {})` — existing callers are unaffected by the defaulted parameter.

- [ ] **Step 1: Add the hook to `driveConformance`**

Change the signature and insert the callback right after the transport is constructed (before channels/connect):

```kotlin
suspend fun driveConformance(
    log: CanLog,
    scope: CoroutineScope,
    /** Runs before playback starts, for tests that also observe the transport. */
    onTransport: (ObdTransport) -> Unit = {},
): List<TesterOp> {
    val ops = reconstructTesterOps(log)
    val transport = ReplayTransport(log, ReplayMode.FastForward, scope)
    onTransport(transport)
```

Add `import nl.jwdr.ooc.transport.ObdTransport`.

- [ ] **Step 2: Write the test**

```kotlin
package nl.jwdr.ooc.protocol.conformance

import java.io.File
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import nl.jwdr.ooc.protocol.gmlan.DpidRecord
import nl.jwdr.ooc.protocol.gmlan.PeriodicDataMonitor
import nl.jwdr.ooc.transport.CanLog
import nl.jwdr.ooc.transport.Direction
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Conformance for GMLAN periodic data (issue #24): while [driveConformance]
 * replays a recorded session's ISO-TP traffic, every recorded broadcast on
 * the secondary CAN ids (request id + 0x300 for GMLAN 0x241..0x25F) must
 * reach a [PeriodicDataMonitor] and decode to the recorded DPID and data
 * bytes — the two listeners share one transport without stealing frames.
 * Skips logs with no periodic-data traffic, and skips entirely without
 * local logs (clean-room pattern, like [RecordedLogConformanceTest]).
 */
@RunWith(Parameterized::class)
class PeriodicDataConformanceTest(private val logFile: File?) {

    @Test
    fun `recorded periodic data reaches a monitor alongside the ISO-TP replay`() = runTest {
        assumeTrue("no local logs in logs/ (clean-room skip)", logFile != null)
        val log = CanLog.parse(logFile!!.readText())
        val secondaryIds = log.frames
            .filter { it.direction == Direction.TX && it.frame.id in 0x241..0x25F }
            .map { it.frame.id + 0x300 }
            .distinct()
        val expected = log.frames
            .filter { it.direction == Direction.RX && it.frame.id in secondaryIds }
        assumeTrue("log has no periodic-data traffic", expected.isNotEmpty())

        val collected = mutableListOf<Pair<Int, DpidRecord>>()
        driveConformance(log, backgroundScope) { transport ->
            for (id in secondaryIds) {
                // UNDISPATCHED: subscribed before playback starts.
                backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    PeriodicDataMonitor(transport, id).records.collect { collected += id to it }
                }
            }
        }
        testScheduler.advanceUntilIdle()

        assertEquals(expected.size, collected.size)
        expected.zip(collected).forEach { (entry, idAndRecord) ->
            val (id, record) = idAndRecord
            assertEquals(entry.frame.id, id)
            assertEquals(entry.frame.data[0].toInt() and 0xFF, record.dpid)
            assertArrayEquals(
                entry.frame.data.copyOfRange(1, entry.frame.data.size),
                record.data,
            )
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

(`LoggedFrame` exposes `frame` and `direction` — see `CanLog.kt`. If the property names differ, read that file and adjust the test, not the transport.)

- [ ] **Step 3: Run against the real logs**

Run: `./gradlew :core:protocol:test --tests "nl.jwdr.ooc.protocol.conformance.*"`
Expected: PASS. The AHL log (and likely REC/UEC/IPC) exercises the assertion; logs without $AA traffic skip via `assumeTrue`. If a log fails on ordering (interleaved multi-ECU capture), the `expected`/`collected` zip may need per-id grouping — group both by secondary id and compare per id, preserving order within each id.

- [ ] **Step 4: Stage**

```bash
git add core/protocol/src/test/kotlin/nl/jwdr/ooc/protocol/conformance/ConformanceDriver.kt core/protocol/src/test/kotlin/nl/jwdr/ooc/protocol/conformance/PeriodicDataConformanceTest.kt
```

---

### Task 9: Full verification + handoff

- [ ] **Step 1: Full build (what CI runs)**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL, all module tests green (recorded-log suites run because `/logs/` exists locally).

- [ ] **Step 2: Confirm everything is staged, nothing stray**

Run: `git status`
Expected: only the intended files staged; no vendor data, nothing from `/logs/` or `/DebugFiles/`.

- [ ] **Step 3: Propose the commit message (do NOT commit — project rule)**

Present to the user:

```
Live display-tag readouts during output tests (closes #24)

GMLAN $AA (readDataByPacketIdentifier) records are now sent
fire-and-forget — fixing v1's ResponseTimeout on scripts that carry
them — and their UUDT broadcasts on the secondary CAN id are decoded
into live per-tag readouts shown in the output-test run panel.
Tag-to-DPID mapping (MEASDATA = rate byte + DPIDs, 7 rows per DPID)
is conformance-verified against the recorded sessions.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01DQZ1KkA3pUekP794MmExmc
```
