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
