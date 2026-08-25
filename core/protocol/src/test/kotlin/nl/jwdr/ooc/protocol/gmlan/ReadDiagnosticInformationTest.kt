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

    @Test
    fun `collects DTCs from the UUDT stream and stops at the end marker`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        val session = DiagnosticSession(transport, address, config = SessionConfig(), scope = backgroundScope)
        transport.onFrame(request(0x03, 0xA9, 0x81, 0x12)).respondWith(
            dtcFrame(0x81, 0x93, 0x25, 0x03, 0x92),
            dtcFrame(0x81, 0xD1, 0x12, 0x00, 0x10),
            dtcFrame(0x81, 0x00, 0x00, 0x00, 0x92),
        )
        transport.connect()

        val dtcs = session.readDiagnosticInformation(
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
        val session = DiagnosticSession(transport, address, config = SessionConfig(), scope = backgroundScope)
        transport.onFrame(request(0x03, 0xA9, 0x81, 0x12)).respondNothing()
        transport.connect()

        val e = runCatching {
            session.readDiagnosticInformation(
                transport,
                secondaryId,
                ReadDiagnosticInformation(statusMask = 0x12),
                timeout = 100.milliseconds,
            )
        }.exceptionOrNull()

        assertTrue("expected ResponseTimeout, got $e", e is SessionException.ResponseTimeout)
    }

    @Test
    fun `a second read on the same connection ignores the first read's replayed frames`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        var reads = 0
        // The transport's replay buffer survives between reads (it is only
        // reset on disconnect), so the second read starts out seeing the
        // first read's DTC and end marker.
        transport.onFrame(request(0x03, 0xA9, 0x81, 0x12)).respondBy {
            reads++
            if (reads == 1) {
                listOf(
                    dtcFrame(0x81, 0x93, 0x25, 0x03, 0x92),
                    dtcFrame(0x81, 0x00, 0x00, 0x00, 0x92),
                )
            } else {
                listOf(dtcFrame(0x81, 0x00, 0x00, 0x00, 0x92))
            }
        }
        transport.connect()

        // A fresh session per call, the way DiagnosticsManager uses this.
        val first = DiagnosticSession(transport, address, config = SessionConfig(), scope = backgroundScope)
            .readDiagnosticInformation(
                transport,
                secondaryId,
                ReadDiagnosticInformation(statusMask = 0x12),
                timeout = 1.seconds,
            )
        val second = DiagnosticSession(transport, address, config = SessionConfig(), scope = backgroundScope)
            .readDiagnosticInformation(
                transport,
                secondaryId,
                ReadDiagnosticInformation(statusMask = 0x12),
                timeout = 1.seconds,
            )

        assertEquals(listOf(GmlanDtc(0x9325, 0x03, 0x92)), first)
        assertEquals(emptyList<GmlanDtc>(), second)
        assertEquals(2, reads)
    }
}
