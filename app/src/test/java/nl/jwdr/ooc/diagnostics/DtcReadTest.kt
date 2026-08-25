package nl.jwdr.ooc.diagnostics

import kotlinx.coroutines.test.runTest
import nl.jwdr.ooc.protocol.kwp2000.Dtc
import nl.jwdr.ooc.protocol.session.SessionException
import nl.jwdr.ooc.transport.CanFrame
import nl.jwdr.ooc.transport.FakeEcuTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DtcReadTest {

    private val pad = 0xAA.toByte()

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun frame(id: Int, vararg values: Int): CanFrame {
        val data = bytes(*values)
        return CanFrame(id, if (data.size < 8) data + ByteArray(8 - data.size) { pad } else data)
    }

    /** The single-frame readDTCByStatus request. */
    private fun readRequest(requestId: Int) = frame(requestId, 0x04, 0x18, 0x02, 0xFF, 0x00)

    private val engine = EcuScanTarget(name = "Engine", requestId = 0x7E0, responseId = 0x7E8)

    @Test
    fun `reads the stored DTCs of one ECU`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        // Two DTCs (0x0016 symptom 0x00, 0x9000 symptom 0x02): an 8-byte
        // payload, segmented as ISO-TP first frame + consecutive frame.
        transport.onFrame(readRequest(0x7E0)).respondWith(
            frame(0x7E8, 0x10, 0x08, 0x58, 0x02, 0x00, 0x16, 0x00, 0x90),
            frame(0x7E8, 0x21, 0x00, 0x02),
        )
        transport.connect()
        val manager = DiagnosticsManager(transport)

        val dtcs = manager.readDtcs(engine)

        assertEquals(
            listOf(Dtc(code = 0x0016, symptom = 0x00), Dtc(code = 0x9000, symptom = 0x02)),
            dtcs,
        )
    }

    @Test
    fun `annotates the session capture with the action and ECU`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        transport.onFrame(readRequest(0x7E0)).respondWith(frame(0x7E8, 0x02, 0x58, 0x00))
        transport.connect()
        val notes = mutableListOf<String>()
        val manager = DiagnosticsManager(transport, annotate = { notes += it })

        manager.readDtcs(engine)

        assertEquals(listOf("readDtcs ecu=Engine req=0x7E0 resp=0x7E8"), notes)
    }

    @Test
    fun `an ECU without stored faults yields an empty list`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        transport.onFrame(readRequest(0x7E0))
            .respondWith(frame(0x7E8, 0x02, 0x58, 0x00))
        transport.connect()
        val manager = DiagnosticsManager(transport)

        assertEquals(emptyList<Dtc>(), manager.readDtcs(engine))
    }

    @Test
    fun `a negative response fails the read`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        // 7F 18 11: serviceNotSupported.
        transport.onFrame(readRequest(0x7E0))
            .respondWith(frame(0x7E8, 0x03, 0x7F, 0x18, 0x11))
        transport.connect()
        val manager = DiagnosticsManager(transport)

        val e = runCatching { manager.readDtcs(engine) }.exceptionOrNull()

        assertTrue("expected NegativeResponse, got $e", e is SessionException.NegativeResponse)
    }

    @Test
    fun `a silent ECU is retried before the read times out`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        transport.connect()
        val manager = DiagnosticsManager(transport)

        val e = runCatching { manager.readDtcs(engine) }.exceptionOrNull()

        assertTrue("expected ResponseTimeout, got $e", e is SessionException.ResponseTimeout)
        assertTrue(
            "a targeted read must retry, sent ${transport.sentFrames.size} frame(s)",
            transport.sentFrames.size > 1,
        )
    }
}
