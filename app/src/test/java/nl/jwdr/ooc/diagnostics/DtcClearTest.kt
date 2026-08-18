package nl.jwdr.ooc.diagnostics

import kotlinx.coroutines.test.runTest
import nl.jwdr.ooc.protocol.kwp2000.Dtc
import nl.jwdr.ooc.protocol.session.SessionException
import nl.jwdr.ooc.transport.CanFrame
import nl.jwdr.ooc.transport.FakeEcuTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DtcClearTest {

    private val pad = 0xAA.toByte()

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun frame(id: Int, vararg values: Int): CanFrame {
        val data = bytes(*values)
        return CanFrame(id, if (data.size < 8) data + ByteArray(8 - data.size) { pad } else data)
    }

    /** The single-frame clearDiagnosticInformation request for all DTC groups. */
    private fun clearRequest(requestId: Int) = frame(requestId, 0x03, 0x14, 0xFF, 0x00)

    /** The single-frame readDTCByStatus request of the verifying read-back. */
    private fun readRequest(requestId: Int) = frame(requestId, 0x04, 0x18, 0x02, 0xFF, 0x00)

    private val engine = EcuScanTarget(name = "Engine", requestId = 0x7E0, responseId = 0x7E8)

    @Test
    fun `clears all DTC groups of one ECU and reads back an empty store`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        transport.onFrame(clearRequest(0x7E0)).respondWith(frame(0x7E8, 0x01, 0x54))
        transport.onFrame(readRequest(0x7E0)).respondWith(frame(0x7E8, 0x02, 0x58, 0x00))
        transport.connect()
        val manager = DiagnosticsManager(transport)

        val remaining = manager.clearDtcs(engine)

        assertTrue(
            "expected the 14 FF 00 clear request on the bus",
            transport.sentFrames.contains(clearRequest(0x7E0)),
        )
        assertEquals(emptyList<Dtc>(), remaining)
    }

    @Test
    fun `DTCs the ECU still stores after the clear are returned`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        transport.onFrame(clearRequest(0x7E0)).respondWith(frame(0x7E8, 0x01, 0x54))
        transport.onFrame(readRequest(0x7E0))
            .respondWith(frame(0x7E8, 0x05, 0x58, 0x01, 0x00, 0x16, 0x00))
        transport.connect()
        val manager = DiagnosticsManager(transport)

        val remaining = manager.clearDtcs(engine)

        assertEquals(listOf(Dtc(code = 0x0016, symptom = 0x00)), remaining)
    }

    @Test
    fun `a negative response fails the clear without a read-back`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        // 7F 14 11: serviceNotSupported.
        transport.onFrame(clearRequest(0x7E0))
            .respondWith(frame(0x7E8, 0x03, 0x7F, 0x14, 0x11))
        transport.connect()
        val manager = DiagnosticsManager(transport)

        val e = runCatching { manager.clearDtcs(engine) }.exceptionOrNull()

        assertTrue("expected NegativeResponse, got $e", e is SessionException.NegativeResponse)
        assertTrue(
            "the failed clear must not be followed by a read-back",
            transport.sentFrames.none { it == readRequest(0x7E0) },
        )
    }
}
