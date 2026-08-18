package nl.jwdr.ooc.protocol.session

import kotlinx.coroutines.test.runTest
import nl.jwdr.ooc.protocol.isotp.IsoTpAddress
import nl.jwdr.ooc.protocol.obd2.ReadCurrentData
import nl.jwdr.ooc.transport.CanFrame
import nl.jwdr.ooc.transport.FakeEcuTransport
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Replayed frames of an earlier exchange can share the service id with the
 * awaited reply (both mode 01). The session must skip them by the
 * request-specific echo, or a poll started after a supported-PID query scales
 * the PID-00 bitmask as sensor data.
 */
class StaleReplyTest {

    private val address = IsoTpAddress(requestId = 0x7E0, responseId = 0x7E8)
    private val pad = 0xAA.toByte()

    private fun frame(id: Int, vararg values: Int): CanFrame {
        val data = ByteArray(values.size) { values[it].toByte() }
        return CanFrame(id, if (data.size < 8) data + ByteArray(8 - data.size) { pad } else data)
    }

    @Test
    fun `a stale same-service reply with the wrong PID echo is skipped`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        transport.onFrame(frame(0x7E0, 0x02, 0x01, 0x0C)).respondWith(
            // A replayed supported-PIDs response (mode 01, PID 00 echo) that a
            // fresh session ingested before the real RPM reply.
            frame(0x7E8, 0x06, 0x41, 0x00, 0xBE, 0x3E, 0xB8, 0x11),
            frame(0x7E8, 0x04, 0x41, 0x0C, 0x1A, 0xF8),
        )
        transport.connect()
        val session = DiagnosticSession(transport, address, scope = backgroundScope)

        val response = session.execute(ReadCurrentData(0x0C))

        assertEquals(0x0C, response.pid)
        assertEquals(listOf(0x1A, 0xF8), response.data.map { it.toInt() and 0xFF })
    }
}
