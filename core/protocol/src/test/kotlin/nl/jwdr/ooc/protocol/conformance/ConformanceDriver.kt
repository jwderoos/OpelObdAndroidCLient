package nl.jwdr.ooc.protocol.conformance

import kotlinx.coroutines.CoroutineScope
import nl.jwdr.ooc.protocol.isotp.IsoTpChannel
import nl.jwdr.ooc.protocol.isotp.IsoTpConfig
import nl.jwdr.ooc.transport.CanLog
import nl.jwdr.ooc.transport.ReplayMode
import nl.jwdr.ooc.transport.ReplayTransport
import org.junit.Assert.assertArrayEquals

/**
 * Replays a recorded session through the real protocol stack.
 *
 * The [TesterOp]s reconstructed from [log] are executed in order against a
 * tx-gating [ReplayTransport] over the same log: [TesterOp.Send] goes through
 * an [IsoTpChannel] (one per address, like a live session would hold), so
 * segmentation, flow control, padding, and sequence numbers must all
 * reproduce the recording byte for byte, or the transport throws.
 * [TesterOp.Expect] asserts that the channel reassembles the recorded
 * response payload exactly.
 *
 * Returns the executed ops for reporting.
 */
suspend fun driveConformance(log: CanLog, scope: CoroutineScope): List<TesterOp> {
    val ops = reconstructTesterOps(log)
    val transport = ReplayTransport(log, ReplayMode.FastForward, scope)
    // OP-COM pads frames with 0x00 and advertises "stream everything, no
    // separation" in its flow control; the config must match the recording.
    val config = IsoTpConfig(padByte = 0x00, rxBlockSize = 0, rxSeparationTimeRaw = 0)
    // Channels subscribe on construction; create them all before playback
    // starts so no early response frames are missed.
    val channels = ops
        .mapNotNull {
            when (it) {
                is TesterOp.Send -> it.address
                is TesterOp.Expect -> it.address
                is TesterOp.RawSend -> null
            }
        }
        .distinct()
        .associateWith { IsoTpChannel(transport, it, config, scope) }
    transport.connect()
    for ((index, op) in ops.withIndex()) {
        when (op) {
            is TesterOp.RawSend -> transport.send(op.frame)
            is TesterOp.Send -> channels.getValue(op.address).send(op.payload)
            is TesterOp.Expect -> assertArrayEquals(
                "op #$index $op",
                op.payload,
                channels.getValue(op.address).receive(),
            )
        }
    }
    return ops
}
