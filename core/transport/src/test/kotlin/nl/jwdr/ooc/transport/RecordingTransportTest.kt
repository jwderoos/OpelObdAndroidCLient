package nl.jwdr.ooc.transport

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingTransportTest {

    private val request = CanFrame(0x7E0, byteArrayOf(0x02, 0x01, 0x00))
    private val response = CanFrame(0x7E8, byteArrayOf(0x41, 0x00))

    /** Fake clock: each call advances by 10 ms so tx and rx get distinct stamps. */
    private class StepClock(private var now: Long = 1_000) : () -> Long {
        override fun invoke(): Long = now.also { now += 10 }
    }

    @Test
    fun `passes frames through and records tx and rx with relative timestamps`() = runTest {
        val fake = FakeEcuTransport(backgroundScope)
        fake.onFrame(request).respondWith(response)
        val out = StringBuilder()
        val recorder = RecordingTransport(fake, { AppendableCanLogSink(out) }, backgroundScope, clock = StepClock())
        val received = mutableListOf<CanFrame>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { recorder.incomingFrames.toList(received) }

        recorder.connect()
        recorder.send(request)
        testScheduler.runCurrent()
        recorder.disconnect()
        job.cancel()

        assertEquals(listOf(request), fake.sentFrames)
        assertEquals(listOf(response), received)
        val log = CanLog.parse(out.toString())
        assertEquals(
            listOf(
                LoggedFrame(10, Direction.TX, request),
                LoggedFrame(20, Direction.RX, response),
            ),
            log.frames,
        )
    }

    @Test
    fun `writes metadata header and event comments`() = runTest {
        val fake = FakeEcuTransport(backgroundScope)
        val out = StringBuilder()
        val recorder = RecordingTransport(
            fake,
            { AppendableCanLogSink(out, metadata = mapOf("transport" to "fake")) },
            backgroundScope,
            clock = StepClock(),
        )

        recorder.connect()
        recorder.note("read DTC ecu=Engine")
        recorder.disconnect()

        val text = out.toString()
        assertTrue(text, text.startsWith("# ooc-canlog v1\n# transport: fake\n"))
        assertTrue(text, text.contains("# event 10: read DTC ecu=Engine\n"))
        assertEquals(mapOf("transport" to "fake"), CanLog.parse(text).metadata)
    }

    @Test
    fun `opens a fresh sink per session and closes it on disconnect`() = runTest {
        val fake = FakeEcuTransport(backgroundScope)
        val opened = mutableListOf<StringBuilder>()
        var closed = 0
        val recorder = RecordingTransport(fake, {
            val out = StringBuilder().also(opened::add)
            object : CanLogSink by AppendableCanLogSink(out) {
                override fun close() { closed++ }
            }
        }, backgroundScope)

        recorder.connect(); recorder.disconnect()
        recorder.connect(); recorder.disconnect()

        assertEquals(2, opened.size)
        assertEquals(2, closed)
    }

    @Test
    fun `records nothing when the sink factory returns null`() = runTest {
        val fake = FakeEcuTransport(backgroundScope)
        fake.onFrame(request).respondWith(response)
        val recorder = RecordingTransport(fake, { null }, backgroundScope)
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { recorder.incomingFrames.toList(mutableListOf()) }

        recorder.connect()
        recorder.send(request)
        recorder.note("ignored")
        recorder.disconnect()
        job.cancel()

        assertEquals(listOf(request), fake.sentFrames)
        assertEquals(ConnectionState.Disconnected, recorder.state.first())
    }

    @Test
    fun `recorded log replays through ReplayTransport`() = runTest {
        val fake = FakeEcuTransport(backgroundScope)
        fake.onFrame(request).respondWith(response)
        val out = StringBuilder()
        val recorder = RecordingTransport(fake, { AppendableCanLogSink(out) }, backgroundScope, clock = StepClock())
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { recorder.incomingFrames.toList(mutableListOf()) }
        recorder.connect(); recorder.send(request); testScheduler.runCurrent(); recorder.disconnect(); job.cancel()

        val replay = ReplayTransport(CanLog.parse(out.toString()), ReplayMode.FastForward, backgroundScope)
        val replayed = mutableListOf<CanFrame>()
        val replayJob = launch(UnconfinedTestDispatcher(testScheduler)) { replay.incomingFrames.toList(replayed) }
        replay.connect()
        replay.send(request)
        testScheduler.advanceUntilIdle()

        assertEquals(listOf(response), replayed)
        replayJob.cancel()
    }
}
