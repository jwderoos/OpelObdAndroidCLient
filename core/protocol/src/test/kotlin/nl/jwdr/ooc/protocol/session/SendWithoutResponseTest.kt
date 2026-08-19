package nl.jwdr.ooc.protocol.session

import kotlinx.coroutines.test.runTest
import nl.jwdr.ooc.protocol.isotp.IsoTpAddress
import nl.jwdr.ooc.transport.CanFrame
import nl.jwdr.ooc.transport.FakeEcuTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SendWithoutResponseTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun `sends the frame and returns without awaiting a response`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        transport.connect()
        val session = DiagnosticSession(
            transport,
            IsoTpAddress(0x241, 0x641),
            scope = backgroundScope,
        )

        // Nothing is scripted to answer: execute() would retry into a
        // ResponseTimeout here; sendWithoutResponse must just return.
        session.sendWithoutResponse(bytes(0xAA, 0x03, 0x10, 0x11))

        assertEquals(
            listOf(CanFrame(0x241, bytes(0x04, 0xAA, 0x03, 0x10, 0x11, 0xAA, 0xAA, 0xAA))),
            transport.sentFrames,
        )
    }

    @Test
    fun `a closed session rejects the send`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        transport.connect()
        val session = DiagnosticSession(
            transport,
            IsoTpAddress(0x241, 0x641),
            scope = backgroundScope,
        )
        session.close()

        val e = runCatching { session.sendWithoutResponse(bytes(0xAA, 0x00)) }.exceptionOrNull()

        assertTrue("expected SessionClosed, got $e", e is SessionException.SessionClosed)
    }
}
