package nl.jwdr.ooc.protocol.session

import kotlinx.coroutines.test.runTest
import nl.jwdr.ooc.protocol.isotp.IsoTpAddress
import nl.jwdr.ooc.protocol.kwp2000.KwpError
import nl.jwdr.ooc.protocol.kwp2000.SeedKeyAlgorithm
import nl.jwdr.ooc.transport.CanFrame
import nl.jwdr.ooc.transport.FakeEcuTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticSessionUnlockTest {

    private val address = IsoTpAddress(requestId = 0x241, responseId = 0x641)
    private val pad = 0xAA.toByte()

    /** Adds 1 to each seed byte -- a synthetic stand-in, never a real algorithm. */
    private val incrementAlgorithm = SeedKeyAlgorithm { seed, _ ->
        ByteArray(seed.size) { (seed[it] + 1).toByte() }
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun request(vararg values: Int) = CanFrame(0x241, padded(bytes(*values)))

    private fun response(vararg values: Int) = CanFrame(0x641, padded(bytes(*values)))

    private fun padded(data: ByteArray) =
        if (data.size < 8) data + ByteArray(8 - data.size) { pad } else data

    @Test
    fun `unlock sends the seed request then the computed key`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        transport.onFrame(request(0x02, 0x27, 0x01)).respondWith(response(0x04, 0x67, 0x01, 0xDE, 0xAD))
        transport.onFrame(request(0x04, 0x27, 0x02, 0xDF, 0xAE)).respondWith(response(0x02, 0x67, 0x02))
        transport.connect()
        val session = DiagnosticSession(transport, address, scope = backgroundScope)

        val outcome = session.unlock(level = 0x01, algorithm = incrementAlgorithm)

        assertEquals(UnlockOutcome.Unlocked, outcome)
    }

    @Test
    fun `an all-zero seed means already unlocked and sends no key`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        transport.onFrame(request(0x02, 0x27, 0x01)).respondWith(response(0x04, 0x67, 0x01, 0x00, 0x00))
        transport.connect()
        val session = DiagnosticSession(transport, address, scope = backgroundScope)

        val outcome = session.unlock(level = 0x01, algorithm = incrementAlgorithm)

        assertEquals(UnlockOutcome.AlreadyUnlocked, outcome)
        assertTrue(transport.sentFrames.none { it.data[1] == 0x27.toByte() && it.data[2] == 0x02.toByte() })
    }

    @Test
    fun `unlock rejects an even level before sending any frame`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        transport.connect()
        val session = DiagnosticSession(transport, address, scope = backgroundScope)

        val e = runCatching { session.unlock(level = 0x02, algorithm = incrementAlgorithm) }.exceptionOrNull()

        assertTrue("expected IllegalArgumentException, got $e", e is IllegalArgumentException)
        assertTrue(transport.sentFrames.isEmpty())
    }

    @Test
    fun `a rejected key surfaces as UnlockFailed with InvalidKey`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        transport.onFrame(request(0x02, 0x27, 0x01)).respondWith(response(0x04, 0x67, 0x01, 0xDE, 0xAD))
        transport.onFrame(request(0x04, 0x27, 0x02, 0xDF, 0xAE)).respondWith(response(0x03, 0x7F, 0x27, 0x35))
        transport.connect()
        val session = DiagnosticSession(transport, address, scope = backgroundScope)

        val e = runCatching { session.unlock(level = 0x01, algorithm = incrementAlgorithm) }.exceptionOrNull()

        assertTrue("expected UnlockFailed, got $e", e is SessionException.UnlockFailed)
        assertEquals(KwpError.InvalidKey, (e as SessionException.UnlockFailed).error)
    }

    @Test
    fun `too many attempts surfaces as UnlockFailed with ExceededNumberOfAttempts`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        transport.onFrame(request(0x02, 0x27, 0x01)).respondWith(response(0x04, 0x67, 0x01, 0xDE, 0xAD))
        transport.onFrame(request(0x04, 0x27, 0x02, 0xDF, 0xAE)).respondWith(response(0x03, 0x7F, 0x27, 0x36))
        transport.connect()
        val session = DiagnosticSession(transport, address, scope = backgroundScope)

        val e = runCatching { session.unlock(level = 0x01, algorithm = incrementAlgorithm) }.exceptionOrNull()

        assertTrue("expected UnlockFailed, got $e", e is SessionException.UnlockFailed)
        assertEquals(KwpError.ExceededNumberOfAttempts, (e as SessionException.UnlockFailed).error)
    }

    @Test
    fun `an unexpired cooldown surfaces as UnlockFailed with RequiredTimeDelayNotExpired`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        transport.onFrame(request(0x02, 0x27, 0x01)).respondWith(response(0x04, 0x67, 0x01, 0xDE, 0xAD))
        transport.onFrame(request(0x04, 0x27, 0x02, 0xDF, 0xAE)).respondWith(response(0x03, 0x7F, 0x27, 0x37))
        transport.connect()
        val session = DiagnosticSession(transport, address, scope = backgroundScope)

        val e = runCatching { session.unlock(level = 0x01, algorithm = incrementAlgorithm) }.exceptionOrNull()

        assertTrue("expected UnlockFailed, got $e", e is SessionException.UnlockFailed)
        assertEquals(KwpError.RequiredTimeDelayNotExpired, (e as SessionException.UnlockFailed).error)
    }

    @Test
    fun `a rejected seed request surfaces as a plain NegativeResponse, not UnlockFailed`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        transport.onFrame(request(0x02, 0x27, 0x01)).respondWith(response(0x03, 0x7F, 0x27, 0x80))
        transport.connect()
        val session = DiagnosticSession(transport, address, scope = backgroundScope)

        val e = runCatching { session.unlock(level = 0x01, algorithm = incrementAlgorithm) }.exceptionOrNull()

        assertTrue("expected NegativeResponse, got $e", e is SessionException.NegativeResponse)
        assertTrue("must not be remapped to UnlockFailed", e !is SessionException.UnlockFailed)
        assertTrue(transport.sentFrames.none { it.data[1] == 0x27.toByte() && it.data[2] == 0x02.toByte() })
    }
}
