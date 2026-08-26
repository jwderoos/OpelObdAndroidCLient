package nl.jwdr.ooc.diagnostics

import kotlinx.coroutines.test.runTest
import nl.jwdr.ooc.catalog.CodingTable
import nl.jwdr.ooc.catalog.DidEntry
import nl.jwdr.ooc.protocol.session.SessionException
import nl.jwdr.ooc.transport.CanFrame
import nl.jwdr.ooc.transport.FakeEcuTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodingReadTest {

    private val pad = 0xAA.toByte()

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun frame(id: Int, vararg values: Int): CanFrame {
        val data = bytes(*values)
        return CanFrame(id, if (data.size < 8) data + ByteArray(8 - data.size) { pad } else data)
    }

    private val uec = EcuScanTarget(name = "UEC", requestId = 0x250, responseId = 0x650)

    private val table = CodingTable(
        dataIdentifier = 0x1201,
        didEntries = listOf(DidEntry(id = 0x44, count = 4), DidEntry(id = 0x4C, count = 2)),
        rows = emptyList(),
    )

    @Test
    fun `reads every entry's record in table order`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        transport.onFrame(frame(0x250, 0x02, 0x1A, 0x44))
            .respondWith(frame(0x650, 0x06, 0x5A, 0x44, 0x01, 0x02, 0x03, 0x04))
        transport.onFrame(frame(0x250, 0x02, 0x1A, 0x4C))
            .respondWith(frame(0x650, 0x04, 0x5A, 0x4C, 0x05, 0x06))
        transport.connect()
        val manager = DiagnosticsManager(transport)

        val result = manager.readCoding(uec, table)

        assertEquals(
            listOf(0x44 to listOf<Byte>(0x01, 0x02, 0x03, 0x04), 0x4C to listOf<Byte>(0x05, 0x06)),
            result.entries.map { it.id to it.bytes.toList() },
        )
    }

    @Test
    fun `a negative response on any entry propagates instead of being swallowed`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        // 7F 1A 31: requestOutOfRange.
        transport.onFrame(frame(0x250, 0x02, 0x1A, 0x44))
            .respondWith(frame(0x650, 0x03, 0x7F, 0x1A, 0x31))
        transport.connect()
        val manager = DiagnosticsManager(transport)

        val e = runCatching { manager.readCoding(uec, table) }.exceptionOrNull()

        assertTrue("expected NegativeResponse, got $e", e is SessionException.NegativeResponse)
    }
}
