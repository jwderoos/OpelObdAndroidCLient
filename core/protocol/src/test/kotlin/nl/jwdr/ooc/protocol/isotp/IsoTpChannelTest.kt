package nl.jwdr.ooc.protocol.isotp

import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.test.runTest
import nl.jwdr.ooc.transport.CanFrame
import nl.jwdr.ooc.transport.FakeEcuTransport
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IsoTpChannelTest {

    private val address = IsoTpAddress(requestId = 0x241, responseId = 0x641)
    private val pad = 0xAA.toByte()

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun request(vararg values: Int) = CanFrame(0x241, padded(bytes(*values)))

    private fun response(vararg values: Int) = CanFrame(0x641, padded(bytes(*values)))

    private fun padded(data: ByteArray) =
        if (data.size < 8) data + ByteArray(8 - data.size) { pad } else data

    @Test
    fun `sends a short payload as a padded single frame`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        transport.connect()
        val channel = IsoTpChannel(transport, address, scope = backgroundScope)

        channel.send(bytes(0x3E))

        assertEquals(listOf(request(0x01, 0x3E)), transport.sentFrames)
    }

    @Test
    fun `receives a single frame response`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        transport.connect()
        transport.onId(0x241).respondWith(response(0x02, 0x7E, 0x01))
        val channel = IsoTpChannel(transport, address, scope = backgroundScope)

        val payload = channel.exchange(bytes(0x3E))

        assertArrayEquals(bytes(0x7E, 0x01), payload)
    }

    @Test
    fun `reassembles a multi-frame response and sends flow control`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        transport.connect()
        // 10-byte response: FF with 6 bytes, then one CF with the remaining 4.
        transport.onFrame(request(0x02, 0x1A, 0x90)).respondWith(response(0x10, 0x0A, 1, 2, 3, 4, 5, 6))
        transport.onMatch { it.data[0].toInt() == 0x30 }
            .respondWith(response(0x21, 7, 8, 9, 10))
        val channel = IsoTpChannel(transport, address, scope = backgroundScope)

        val payload = channel.exchange(bytes(0x1A, 0x90))

        assertArrayEquals(bytes(1, 2, 3, 4, 5, 6, 7, 8, 9, 10), payload)
        // request, then our flow control clear-to-send
        assertEquals(request(0x30, 0x00, 0x00), transport.sentFrames[1])
    }

    @Test
    fun `segments a long payload into first and consecutive frames after flow control`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        transport.connect()
        // ECU answers our first frame with flow control: CTS, no block limit, no delay.
        transport.onMatch { it.data[0].toInt() == 0x10 }
            .respondWith(response(0x30, 0x00, 0x00))
        val channel = IsoTpChannel(transport, address, scope = backgroundScope)

        val payload = ByteArray(12) { (it + 1).toByte() }
        channel.send(payload)

        assertEquals(
            listOf(
                CanFrame(0x241, bytes(0x10, 0x0C, 1, 2, 3, 4, 5, 6)),
                request(0x21, 7, 8, 9, 10, 11, 12),
            ),
            transport.sentFrames,
        )
    }

    @Test
    fun `waits for the next flow control after each block`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        transport.connect()
        // Block size 1: every consecutive frame needs a fresh flow control.
        transport.onMatch { it.data[0].toInt() == 0x10 }
            .respondWith(response(0x30, 0x01, 0x00))
        transport.onMatch { it.data[0].toInt() and 0xF0 == 0x20 }
            .respondWith(response(0x30, 0x01, 0x00))
        val channel = IsoTpChannel(transport, address, scope = backgroundScope)

        channel.send(ByteArray(20) { it.toByte() })

        // FF (6 bytes) + 2 CFs (7 + 7 bytes) = 20 bytes
        assertEquals(3, transport.sentFrames.size)
        assertEquals(0x21, transport.sentFrames[1].data[0].toInt())
        assertEquals(0x22, transport.sentFrames[2].data[0].toInt())
    }

    @Test
    fun `paces consecutive frames by the requested separation time`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        transport.connect()
        // Clear to send, no block limit, STmin 20 ms.
        transport.onMatch { it.data[0].toInt() == 0x10 }
            .respondWith(response(0x30, 0x00, 0x14))
        val channel = IsoTpChannel(transport, address, scope = backgroundScope)
        val start = testScheduler.currentTime

        channel.send(ByteArray(20)) // FF + 2 CFs -> one 20 ms gap between the CFs

        assertEquals(20, testScheduler.currentTime - start)
    }

    @Test
    fun `send fails when no flow control arrives in time`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        transport.connect()
        val channel = IsoTpChannel(
            transport,
            address,
            config = IsoTpConfig(flowControlTimeout = 100.milliseconds),
            scope = backgroundScope,
        )

        val error = runCatching { channel.send(ByteArray(12)) }.exceptionOrNull()
        assertTrue("expected IsoTpException.FlowControlTimeout, got $error", error is IsoTpException.FlowControlTimeout)
    }

    @Test
    fun `send fails when the receiver reports overflow`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        transport.connect()
        transport.onMatch { it.data[0].toInt() == 0x10 }
            .respondWith(response(0x32, 0x00, 0x00))
        val channel = IsoTpChannel(transport, address, scope = backgroundScope)

        val error = runCatching { channel.send(ByteArray(12)) }.exceptionOrNull()
        assertTrue("expected IsoTpException.Overflow, got $error", error is IsoTpException.Overflow)
    }

    @Test
    fun `receive fails on a wrong consecutive sequence number`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        transport.connect()
        transport.onFrame(request(0x02, 0x1A, 0x90)).respondWith(response(0x10, 0x0A, 1, 2, 3, 4, 5, 6))
        transport.onMatch { it.data[0].toInt() == 0x30 }
            .respondWith(response(0x23, 7, 8, 9, 10)) // expected seq 1, got 3
        val channel = IsoTpChannel(transport, address, scope = backgroundScope)

        val error = runCatching { channel.exchange(bytes(0x1A, 0x90)) }.exceptionOrNull()
        assertTrue("expected IsoTpException.SequenceError, got $error", error is IsoTpException.SequenceError)
    }

    @Test
    fun `receive fails when a consecutive frame does not arrive in time`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        transport.connect()
        transport.onId(0x241).respondWith(response(0x10, 0x0A, 1, 2, 3, 4, 5, 6))
        // No consecutive frame ever follows.
        val channel = IsoTpChannel(
            transport,
            address,
            config = IsoTpConfig(consecutiveFrameTimeout = 100.milliseconds),
            scope = backgroundScope,
        )

        val error = runCatching { channel.exchange(bytes(0x1A, 0x90)) }.exceptionOrNull()
        assertTrue("expected IsoTpException.ConsecutiveFrameTimeout, got $error", error is IsoTpException.ConsecutiveFrameTimeout)
    }

    @Test
    fun `ignores frames from other CAN ids`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        transport.connect()
        transport.onId(0x241).respondWith(
            CanFrame(0x642, padded(bytes(0x02, 0x50, 0x81))), // another ECU
            response(0x02, 0x7E, 0x01),
        )
        val channel = IsoTpChannel(transport, address, scope = backgroundScope)

        val payload = channel.exchange(bytes(0x3E))

        assertArrayEquals(bytes(0x7E, 0x01), payload)
    }

    @Test
    fun `consecutive exchanges on one channel do not observe earlier frames`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        transport.connect()
        transport.onFrame(request(0x01, 0x3E)).respondWith(response(0x02, 0x7E, 0x01))
        transport.onFrame(request(0x02, 0x1A, 0x90)).respondWith(response(0x03, 0x5A, 0x90, 0x42))
        val channel = IsoTpChannel(transport, address, scope = backgroundScope)

        assertArrayEquals(bytes(0x7E, 0x01), channel.exchange(bytes(0x3E)))
        assertArrayEquals(bytes(0x5A, 0x90, 0x42), channel.exchange(bytes(0x1A, 0x90)))
    }

    @Test
    fun `rejects payloads that cannot be segmented`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        transport.connect()
        val channel = IsoTpChannel(transport, address, scope = backgroundScope)

        val tooLong = runCatching { channel.send(ByteArray(4096)) }.exceptionOrNull()
        assertTrue("expected IllegalArgumentException, got $tooLong", tooLong is IllegalArgumentException)
        val empty = runCatching { channel.send(ByteArray(0)) }.exceptionOrNull()
        assertTrue("expected IllegalArgumentException, got $empty", empty is IllegalArgumentException)
    }
}
