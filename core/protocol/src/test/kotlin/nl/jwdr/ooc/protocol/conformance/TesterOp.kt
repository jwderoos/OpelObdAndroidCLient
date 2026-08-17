package nl.jwdr.ooc.protocol.conformance

import nl.jwdr.ooc.protocol.isotp.IsoTpAddress
import nl.jwdr.ooc.protocol.isotp.IsoTpFrame
import nl.jwdr.ooc.protocol.isotp.toIsoTpFrame
import nl.jwdr.ooc.transport.CanFrame
import nl.jwdr.ooc.transport.CanLog
import nl.jwdr.ooc.transport.Direction

/**
 * One tester-side step of a recorded diagnostic session, reconstructed from
 * an ooc-canlog by [reconstructTesterOps]. Replaying the ops through the
 * protocol stack must reproduce the recorded tx frames byte for byte —
 * that's the conformance check.
 */
sealed class TesterOp {

    /**
     * A frame sent outside an ISO-TP exchange: the GMLAN all-nodes tester
     * present, or a segmented send the recorded tester abandoned midway
     * (which the stack cannot legitimately reproduce as a send).
     */
    class RawSend(val frame: CanFrame) : TesterOp() {
        override fun toString() = "RawSend($frame)"
    }

    /** A complete ISO-TP payload the tester sent on [address]. */
    class Send(val address: IsoTpAddress, val payload: ByteArray) : TesterOp() {
        override fun toString() = "Send(0x${address.requestId.toString(16)}, ${payload.toHex()})"
    }

    /** A complete ISO-TP payload the ECU answered on [address]. */
    class Expect(val address: IsoTpAddress, val payload: ByteArray) : TesterOp() {
        override fun toString() = "Expect(0x${address.responseId.toString(16)}, ${payload.toHex()})"
    }
}

/**
 * The diagnostic address a request CAN id belongs to, or null when the id is
 * not a diagnostic request id. Pairings are protocol facts: GMLAN 11-bit
 * physical requests 0x241..0x25F answer on 0x641..0x65F (+0x400), ISO 15765
 * requests 0x7E0..0x7E7 answer on 0x7E8..0x7EF (+8).
 */
fun diagnosticAddressForRequest(requestId: Int): IsoTpAddress? = when (requestId) {
    in 0x241..0x25F -> IsoTpAddress(requestId, requestId + 0x400)
    in 0x7E0..0x7E7 -> IsoTpAddress(requestId, requestId + 8)
    else -> null
}

/** Inverse of [diagnosticAddressForRequest], keyed by response CAN id. */
fun diagnosticAddressForResponse(responseId: Int): IsoTpAddress? = when (responseId) {
    in 0x641..0x65F -> IsoTpAddress(responseId - 0x400, responseId)
    in 0x7E8..0x7EF -> IsoTpAddress(responseId - 8, responseId)
    else -> null
}

/** A multi-frame message being reassembled, keeping the raw frames it came from. */
private class Assembly(val address: IsoTpAddress, index: Int, frame: CanFrame, first: IsoTpFrame.First) {
    val payload = ByteArray(first.totalLength)
    var received = first.data.size
    var expectedSequence = 1
    val frames = mutableListOf(index to frame)

    init {
        first.data.copyInto(payload)
    }

    /** Returns true when [consecutive] completed the message. */
    fun add(index: Int, frame: CanFrame, consecutive: IsoTpFrame.Consecutive): Boolean {
        check(consecutive.sequenceNumber == expectedSequence) {
            "frame $index: sequence ${consecutive.sequenceNumber}, expected $expectedSequence"
        }
        frames += index to frame
        val count = minOf(consecutive.data.size, payload.size - received)
        consecutive.data.copyInto(payload, received, 0, count)
        received += count
        expectedSequence = (expectedSequence + 1) and 0x0F
        return received == payload.size
    }
}

/**
 * Reconstructs the tester-side operations of a recorded session, ordered by
 * log position (multi-frame payloads sit at their completing frame).
 *
 * tx frames on diagnostic request ids become [TesterOp.Send]; their
 * flow-control frames are implied by the stack and dropped, and a segmented
 * send the tester abandoned (its consecutive frames never followed, as seen
 * in the engine capture) is downgraded to [TesterOp.RawSend]s of its raw
 * frames. Other tx frames become [TesterOp.RawSend]. rx frames on diagnostic
 * response ids become [TesterOp.Expect]; everything else the ECUs broadcast
 * (GMLAN periodic data on 0x5XX and other non-ISO-TP traffic) is dropped,
 * mirroring the channel's own filtering. A response left half-assembled when
 * the capture ends is dropped too — nothing awaits it.
 *
 * Throws on logs a sequential driver cannot replay: a tx gate landing inside
 * another id's half-assembled message, or broken consecutive-frame
 * sequences. None of the OP-COM captures do this.
 */
fun reconstructTesterOps(log: CanLog): List<TesterOp> {
    val ops = mutableListOf<Pair<Int, TesterOp>>()
    var openTx: Assembly? = null
    val openRx = mutableMapOf<Int, Assembly>()

    fun abandonOpenTx() {
        openTx?.frames?.forEach { (index, frame) -> ops += index to TesterOp.RawSend(frame) }
        openTx = null
    }

    for ((index, entry) in log.frames.withIndex()) {
        val frame = entry.frame
        when (entry.direction) {
            Direction.TX -> {
                val address = diagnosticAddressForRequest(frame.id)
                val isoTp = if (address == null) null else frame.toIsoTpFrame()
                val closesRxAssembly = address != null && isoTp is IsoTpFrame.FlowControl &&
                    openRx.containsKey(address.responseId)
                check(openRx.isEmpty() || closesRxAssembly) {
                    "frame $index: tx $frame while a response is half-assembled"
                }
                if (address == null || isoTp == null) {
                    check(openTx == null) {
                        "frame $index: raw tx $frame while a send is half-assembled"
                    }
                    ops += index to TesterOp.RawSend(frame)
                    continue
                }
                when (isoTp) {
                    is IsoTpFrame.Single -> {
                        if (openTx?.address == address) abandonOpenTx()
                        check(openTx == null) {
                            "frame $index: tx single frame while another id's send is half-assembled"
                        }
                        ops += index to TesterOp.Send(address, isoTp.data)
                    }
                    is IsoTpFrame.First -> {
                        if (openTx?.address == address) abandonOpenTx()
                        check(openTx == null) {
                            "frame $index: tx first frame while another id's send is half-assembled"
                        }
                        openTx = Assembly(address, index, frame, isoTp)
                    }
                    is IsoTpFrame.Consecutive -> {
                        val assembly = checkNotNull(openTx) {
                            "frame $index: tx consecutive frame without a first frame"
                        }
                        check(assembly.address == address) {
                            "frame $index: tx consecutive frame on ${frame.id}, " +
                                "expected ${assembly.address.requestId}"
                        }
                        if (assembly.add(index, frame, isoTp)) {
                            ops += index to TesterOp.Send(address, assembly.payload)
                            openTx = null
                        }
                    }
                    // The tester's flow control for the response being
                    // reassembled; IsoTpChannel emits it on its own.
                    is IsoTpFrame.FlowControl -> {}
                }
            }
            Direction.RX -> {
                val address = diagnosticAddressForResponse(frame.id) ?: continue
                when (val isoTp = frame.toIsoTpFrame() ?: continue) {
                    is IsoTpFrame.Single -> ops += index to TesterOp.Expect(address, isoTp.data)
                    is IsoTpFrame.First -> {
                        check(frame.id !in openRx) {
                            "frame $index: rx first frame while a response is half-assembled"
                        }
                        openRx[frame.id] = Assembly(address, index, frame, isoTp)
                    }
                    is IsoTpFrame.Consecutive -> {
                        val assembly = openRx[frame.id] ?: continue
                        if (assembly.add(index, frame, isoTp)) {
                            ops += index to TesterOp.Expect(address, assembly.payload)
                            openRx.remove(frame.id)
                        }
                    }
                    // The ECU's flow control for our segmented send.
                    is IsoTpFrame.FlowControl -> {}
                }
            }
        }
    }
    abandonOpenTx()
    return ops.sortedBy { it.first }.map { it.second }
}

private fun ByteArray.toHex() = joinToString(" ") { "%02X".format(it) }
