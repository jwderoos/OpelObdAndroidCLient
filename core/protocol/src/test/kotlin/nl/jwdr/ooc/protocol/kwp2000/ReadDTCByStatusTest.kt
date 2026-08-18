package nl.jwdr.ooc.protocol.kwp2000

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ReadDTCByStatusTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun `encodes status sub-function and DTC group`() {
        val request = ReadDTCByStatus(status = 0x02, groupOfDtc = 0xFF00)

        assertArrayEquals(bytes(0x18, 0x02, 0xFF, 0x00), request.encode())
    }

    @Test
    fun `decodes the reported DTC list`() {
        val request = ReadDTCByStatus(status = 0x02, groupOfDtc = 0xFF00)

        val response = request.decodeResponse(
            bytes(0x58, 0x02, 0x01, 0x70, 0xE1, 0x12, 0x34, 0x60),
        )

        assertEquals(
            listOf(Dtc(code = 0x0170, symptom = 0xE1), Dtc(code = 0x1234, symptom = 0x60)),
            response.dtcs,
        )
    }

    @Test
    fun `decodes an empty DTC list`() {
        val request = ReadDTCByStatus(status = 0x02, groupOfDtc = 0xFF00)

        assertEquals(emptyList<Dtc>(), request.decodeResponse(bytes(0x58, 0x00)).dtcs)
    }

    @Test
    fun `rejects a DTC list shorter than its announced count`() {
        val request = ReadDTCByStatus(status = 0x02, groupOfDtc = 0xFF00)

        assertThrows(KwpDecodeException::class.java) {
            request.decodeResponse(bytes(0x58, 0x02, 0x01, 0x70, 0xE1))
        }
    }
}
