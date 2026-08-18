package nl.jwdr.ooc.protocol.obd2

import nl.jwdr.ooc.protocol.kwp2000.KwpNegativeResponseException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Obd2ServicesTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    // Mode 01 — current data

    @Test
    fun `mode 01 request encodes service and PID`() {
        assertArrayEquals(bytes(0x01, 0x0C), ReadCurrentData(0x0C).encode())
    }

    @Test
    fun `mode 01 response yields the PID data bytes`() {
        val response = ReadCurrentData(0x0C).decodeResponse(bytes(0x41, 0x0C, 0x1A, 0xF8))
        assertEquals(0x0C, response.pid)
        assertArrayEquals(bytes(0x1A, 0xF8), response.data)
    }

    @Test
    fun `mode 01 negative response throws`() {
        val e = runCatching {
            ReadCurrentData(0x0C).decodeResponse(bytes(0x7F, 0x01, 0x12))
        }.exceptionOrNull()
        assertTrue("expected KwpNegativeResponseException, got $e", e is KwpNegativeResponseException)
    }

    // Mode 03 — stored emission DTCs

    @Test
    fun `mode 03 request is the bare service byte`() {
        assertArrayEquals(bytes(0x03), ReadStoredDtcs.encode())
    }

    @Test
    fun `mode 03 response decodes two-byte DTCs`() {
        val response = ReadStoredDtcs.decodeResponse(bytes(0x43, 0x02, 0x01, 0x43, 0xC1, 0x23))
        assertEquals(listOf(0x0143, 0xC123), response.codes)
    }

    @Test
    fun `mode 03 with no stored DTCs yields an empty list`() {
        assertEquals(emptyList<Int>(), ReadStoredDtcs.decodeResponse(bytes(0x43, 0x00)).codes)
    }

    // Mode 04 — clear emission DTCs

    @Test
    fun `mode 04 request and response round-trip`() {
        assertArrayEquals(bytes(0x04), ClearEmissionData.encode())
        ClearEmissionData.decodeResponse(bytes(0x44))
    }

    // Supported-PID discovery

    @Test
    fun `supported-PID bitmask expands to PID numbers`() {
        // 0xBE1FA813: standard example — PIDs 01,03,04,05,06,07,0C,0D,...
        val supported = Obd2Pids.supportedFrom(basePid = 0x00, data = bytes(0xBE, 0x1F, 0xA8, 0x13))
        assertTrue(supported.contains(0x01))
        assertTrue(supported.contains(0x0C))
        assertTrue(supported.contains(0x0D))
        assertTrue(supported.contains(0x20))
        assertTrue(!supported.contains(0x02))
    }

    @Test
    fun `bitmask of a higher range offsets the PID numbers`() {
        val supported = Obd2Pids.supportedFrom(basePid = 0x20, data = bytes(0x80, 0x00, 0x00, 0x00))
        assertEquals(setOf(0x21), supported)
    }

    // PID scaling (SAE J1979 public formulas)

    private fun value(pid: Int, vararg data: Int): String =
        Obd2Pids.byId(pid)!!.format(bytes(*data))

    @Test
    fun `engine RPM scales by quarter revolutions`() {
        assertEquals("1918", value(0x0C, 0x1D, 0xF8))
    }

    @Test
    fun `coolant temperature has a -40 offset`() {
        assertEquals("50", value(0x05, 0x5A))
    }

    @Test
    fun `vehicle speed is the raw byte`() {
        assertEquals("113", value(0x0D, 0x71))
    }

    @Test
    fun `MAF scales by hundredths`() {
        assertEquals("6.87", value(0x10, 0x02, 0xAF))
    }

    @Test
    fun `throttle position is a percentage`() {
        assertEquals("39.2", value(0x11, 0x64))
    }

    @Test
    fun `catalog covers the common demo PIDs`() {
        val expected = listOf(0x04, 0x05, 0x0C, 0x0D, 0x0F, 0x10, 0x11, 0x2F, 0x42, 0x46)
        for (pid in expected) {
            assertTrue("missing PID 0x%02X".format(pid), Obd2Pids.byId(pid) != null)
        }
    }
}
