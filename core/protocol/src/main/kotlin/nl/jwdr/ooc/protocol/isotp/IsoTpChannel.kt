package nl.jwdr.ooc.protocol.isotp

import kotlin.math.min
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import nl.jwdr.ooc.transport.CanFrame
import nl.jwdr.ooc.transport.ObdTransport

/**
 * One ECU's ISO-TP endpoint: segments outgoing payloads into CAN frames and
 * reassembles incoming ones, honoring flow control in both directions.
 *
 * The channel filters [ObdTransport.incomingFrames] to [IsoTpAddress.responseId]
 * from construction onward, so frames arriving before a call to [receive] are
 * not lost. It carries no request/response pairing or overall response
 * deadline — that is the diagnostic session's job (one request in flight,
 * timeout and retry policy).
 *
 * @param scope scope for the frame-collecting coroutine; it should outlive the
 *   channel's use and be cancelled when the connection ends.
 */
class IsoTpChannel(
    private val transport: ObdTransport,
    private val address: IsoTpAddress,
    private val config: IsoTpConfig = IsoTpConfig(),
    scope: CoroutineScope,
) {

    private val incoming = Channel<CanFrame>(Channel.UNLIMITED)

    init {
        scope.launch {
            transport.incomingFrames.collect { frame ->
                if (frame.id == address.responseId) incoming.send(frame)
            }
        }
    }

    /**
     * Sends [payload] (1..4095 bytes): as a single frame when it fits, else as
     * first + consecutive frames paced by the ECU's flow control.
     *
     * @throws IsoTpException.FlowControlTimeout
     * @throws IsoTpException.Overflow
     */
    suspend fun send(payload: ByteArray) {
        require(payload.isNotEmpty()) { "cannot send an empty payload" }
        require(payload.size <= 4095) { "ISO-TP payload is at most 4095 bytes, got ${payload.size}" }

        if (payload.size <= 7) {
            sendFrame(IsoTpFrame.Single(payload))
            return
        }

        sendFrame(IsoTpFrame.First(payload.size, payload.copyOfRange(0, 6)))
        var offset = 6
        var sequenceNumber = 1
        var flowControl = awaitClearToSend()
        var sentInBlock = 0
        var separation = Duration.ZERO
        while (offset < payload.size) {
            if (flowControl.blockSize > 0 && sentInBlock == flowControl.blockSize) {
                flowControl = awaitClearToSend()
                sentInBlock = 0
                separation = Duration.ZERO
            }
            if (separation > Duration.ZERO) delay(separation)
            separation = separationTime(flowControl.separationTimeRaw)

            val end = min(offset + 7, payload.size)
            sendFrame(IsoTpFrame.Consecutive(sequenceNumber, payload.copyOfRange(offset, end)))
            offset = end
            sequenceNumber = (sequenceNumber + 1) and 0x0F
            sentInBlock++
        }
    }

    /**
     * Receives one complete payload, reassembling multi-frame messages and
     * answering their first frame with our flow control. Suspends until an
     * opening (single or first) frame arrives; apply an overall deadline at
     * the session layer.
     *
     * @throws IsoTpException.ConsecutiveFrameTimeout
     * @throws IsoTpException.SequenceError
     */
    suspend fun receive(): ByteArray {
        while (true) {
            when (val frame = nextFrame()) {
                is IsoTpFrame.Single -> return frame.data
                is IsoTpFrame.First -> return receiveRemainder(frame)
                // Stale consecutive or flow-control frames from an aborted
                // message; skip until a message opens.
                is IsoTpFrame.Consecutive, is IsoTpFrame.FlowControl -> continue
            }
        }
    }

    /** [send] followed by [receive]. */
    suspend fun exchange(payload: ByteArray): ByteArray {
        send(payload)
        return receive()
    }

    private suspend fun receiveRemainder(first: IsoTpFrame.First): ByteArray {
        val payload = ByteArray(first.totalLength)
        first.data.copyInto(payload)
        var received = first.data.size
        var expectedSequence = 1
        var receivedInBlock = 0
        sendFrame(flowControlFrame())
        while (received < payload.size) {
            val frame = withTimeoutOrNull(config.consecutiveFrameTimeout) { nextFrame() }
                ?: throw IsoTpException.ConsecutiveFrameTimeout()
            if (frame !is IsoTpFrame.Consecutive) continue
            if (frame.sequenceNumber != expectedSequence) {
                throw IsoTpException.SequenceError(expectedSequence, frame.sequenceNumber)
            }
            val count = min(frame.data.size, payload.size - received)
            frame.data.copyInto(payload, received, 0, count)
            received += count
            expectedSequence = (expectedSequence + 1) and 0x0F
            receivedInBlock++
            if (received < payload.size && config.rxBlockSize > 0 && receivedInBlock == config.rxBlockSize) {
                sendFrame(flowControlFrame())
                receivedInBlock = 0
            }
        }
        return payload
    }

    private suspend fun awaitClearToSend(): IsoTpFrame.FlowControl {
        while (true) {
            val frame = withTimeoutOrNull(config.flowControlTimeout) { nextFrame() }
                ?: throw IsoTpException.FlowControlTimeout()
            if (frame !is IsoTpFrame.FlowControl) continue
            when (frame.status) {
                IsoTpFrame.FlowStatus.CLEAR_TO_SEND -> return frame
                IsoTpFrame.FlowStatus.WAIT -> continue
                IsoTpFrame.FlowStatus.OVERFLOW -> throw IsoTpException.Overflow()
            }
        }
    }

    private suspend fun nextFrame(): IsoTpFrame {
        while (true) {
            incoming.receive().toIsoTpFrame()?.let { return it }
        }
    }

    private fun flowControlFrame() = IsoTpFrame.FlowControl(
        status = IsoTpFrame.FlowStatus.CLEAR_TO_SEND,
        blockSize = config.rxBlockSize,
        separationTimeRaw = config.rxSeparationTimeRaw,
    )

    private suspend fun sendFrame(frame: IsoTpFrame) {
        transport.send(frame.toCanFrame(address.requestId, config.padByte))
    }

    private fun separationTime(raw: Int): Duration = when (raw) {
        in 0x00..0x7F -> raw.milliseconds
        in 0xF1..0xF9 -> ((raw - 0xF0) * 100).microseconds
        // Reserved values mean "use the maximum".
        else -> 127.milliseconds
    }
}
