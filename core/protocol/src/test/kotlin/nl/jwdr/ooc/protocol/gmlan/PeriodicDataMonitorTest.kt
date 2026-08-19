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
        testScheduler.runCurrent()
        collector.cancel()

        assertEquals(2, collected.size)
        assertEquals(0x10, collected[0].dpid)
        assertArrayEquals(bytes(0x0C, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00), collected[0].data)
        assertEquals(0x11, collected[1].dpid)
        assertArrayEquals(bytes(0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00), collected[1].data)
    }
}
