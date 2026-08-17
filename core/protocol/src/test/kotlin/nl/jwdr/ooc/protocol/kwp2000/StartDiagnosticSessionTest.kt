package nl.jwdr.ooc.protocol.kwp2000

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class StartDiagnosticSessionTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun `encodes service id and diagnostic mode`() {
        val request = StartDiagnosticSession(diagnosticMode = 0x81)

        assertArrayEquals(bytes(0x10, 0x81), request.encode())
    }

    @Test
    fun `decodes a positive response`() {
        val request = StartDiagnosticSession(diagnosticMode = 0x81)

        val response = request.decodeResponse(bytes(0x50, 0x81))

        assertEquals(0x81, response.diagnosticMode)
    }
}
