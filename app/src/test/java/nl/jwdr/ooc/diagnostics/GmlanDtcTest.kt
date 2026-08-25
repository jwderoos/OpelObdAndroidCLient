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

    @Test
    fun `an ISO15765-addressed target with a non-zero secondaryId still uses readDTCByStatus`() = runTest {
        // Real catalogs give 0x7Ex-addressed ECUs (engine, transmission) a
        // non-zero secondaryId too, but they are not on the GMLAN 11-bit scheme
        // and no recorded session ever sent them an A9 request.
        val isoTpEcu = gmlanEcu.copy(requestId = 0x7E0, responseId = 0x7E8, secondaryId = 0x5E8)
        val transport = FakeEcuTransport(backgroundScope)
        transport.onFrame(frame(0x7E0, 0x04, 0x18, 0x02, 0xFF, 0x00))
            .respondWith(frame(0x7E8, 0x05, 0x58, 0x01, 0x93, 0x25, 0x03))
        transport.connect()
        val manager = DiagnosticsManager(transport)

        val dtcs = manager.readDtcs(isoTpEcu)

        assertEquals(listOf(Dtc(0x9325, 0x03)), dtcs)
    }

    @Test
    fun `scanEcus reports Present with unknown dtcCount when only the A9 read times out`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        transport.onFrame(returnToNormalRequest).respondWith(returnToNormalResponse)
        // readRequest deliberately gets no answer: ReturnToNormalMode already
        // proved the ECU is alive, so it must not be reported Absent.
        transport.connect()
        val manager = DiagnosticsManager(transport)

        val result = manager.scanEcus(listOf(gmlanEcu)).toList()

        assertEquals(listOf(EcuScanResult(gmlanEcu, EcuScanStatus.Present(dtcCount = null))), result)
    }
}
