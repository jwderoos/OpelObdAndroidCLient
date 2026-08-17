package nl.jwdr.ooc.protocol.kwp2000

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DataServicesTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun `TesterPresent encodes bare and decodes its acknowledgement`() {
        val request = TesterPresent()

        assertArrayEquals(bytes(0x3E), request.encode())
        request.decodeResponse(bytes(0x7E))
    }

    @Test
    fun `TesterPresent encodes an optional response type sub-parameter`() {
        assertArrayEquals(bytes(0x3E, 0x01), TesterPresent(responseType = 0x01).encode())
    }

    @Test
    fun `ReadECUIdentification returns the identification record`() {
        val request = ReadECUIdentification(identificationOption = 0x90)

        assertArrayEquals(bytes(0x1A, 0x90), request.encode())

        val response = request.decodeResponse(bytes(0x5A, 0x90, 0x41, 0x42, 0x43))

        assertEquals(0x90, response.identificationOption)
        assertArrayEquals(bytes(0x41, 0x42, 0x43), response.record)
    }

    @Test
    fun `ReadDataByLocalIdentifier returns the data record`() {
        val request = ReadDataByLocalIdentifier(localIdentifier = 0x05)

        assertArrayEquals(bytes(0x21, 0x05), request.encode())

        val response = request.decodeResponse(bytes(0x61, 0x05, 0x12, 0x34))

        assertEquals(0x05, response.localIdentifier)
        assertArrayEquals(bytes(0x12, 0x34), response.record)
    }

    @Test
    fun `WriteDataByLocalIdentifier encodes identifier and record`() {
        val request = WriteDataByLocalIdentifier(
            localIdentifier = 0x99,
            record = bytes(0x01, 0x02, 0x03),
        )

        assertArrayEquals(bytes(0x3B, 0x99, 0x01, 0x02, 0x03), request.encode())
    }

    @Test
    fun `WriteDataByLocalIdentifier accepts a response echoing the identifier`() {
        val request = WriteDataByLocalIdentifier(localIdentifier = 0x99, record = bytes(0x01))

        assertEquals(0x99, request.decodeResponse(bytes(0x7B, 0x99)).localIdentifier)
    }

    @Test
    fun `WriteDataByLocalIdentifier accepts a bare positive response`() {
        // Seen in recorded OP-COM sessions: the ECU acknowledges with 0x7B
        // alone, without echoing the local identifier.
        val request = WriteDataByLocalIdentifier(localIdentifier = 0x99, record = bytes(0x01))

        assertEquals(null, request.decodeResponse(bytes(0x7B)).localIdentifier)
    }

    @Test
    fun `ClearDiagnosticInformation encodes the DTC group`() {
        val request = ClearDiagnosticInformation(groupOfDtc = 0xFF00)

        assertArrayEquals(bytes(0x14, 0xFF, 0x00), request.encode())
        request.decodeResponse(bytes(0x54, 0xFF, 0x00))
    }

    @Test
    fun `InputOutputControlByLocalIdentifier encodes control parameter and state`() {
        val request = InputOutputControlByLocalIdentifier(
            localIdentifier = 0x20,
            controlParameter = 0x07,
            controlState = bytes(0x01),
        )

        assertArrayEquals(bytes(0x30, 0x20, 0x07, 0x01), request.encode())

        val response = request.decodeResponse(bytes(0x70, 0x20, 0x07, 0x55))

        assertEquals(0x20, response.localIdentifier)
        assertArrayEquals(bytes(0x07, 0x55), response.record)
    }

    @Test
    fun `data services reject negative responses through the shared mapping`() {
        val thrown = assertThrows(KwpNegativeResponseException::class.java) {
            ReadDataByLocalIdentifier(localIdentifier = 0x05)
                .decodeResponse(bytes(0x7F, 0x21, 0x31))
        }

        assertEquals(0x21, thrown.serviceId)
        assertEquals(KwpError.RequestOutOfRange, thrown.error)
    }
}
