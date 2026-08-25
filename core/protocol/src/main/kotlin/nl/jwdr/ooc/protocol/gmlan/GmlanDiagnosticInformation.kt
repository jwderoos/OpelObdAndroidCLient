package nl.jwdr.ooc.protocol.gmlan

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import nl.jwdr.ooc.protocol.session.DiagnosticSession
import nl.jwdr.ooc.protocol.session.SessionException
import nl.jwdr.ooc.transport.ObdTransport

/**
 * One DTC reported by GMLAN readDiagnosticInformation/reportDTCByStatusMask
 * (0xA9/0x81). [code] `0x0000` is the end-of-list marker, not a real fault.
 */
data class GmlanDtc(val code: Int, val failureType: Int, val status: Int)

/**
 * Decodes GMLAN readDiagnosticInformation/reportDTCByStatusMask replies:
 * UUDT frames on the ECU's secondary CAN id, one DTC per frame (`81 | code
 * hi | code lo | failure type | status`). Not ISO-TP; like
 * [PeriodicDataMonitor], this observes [ObdTransport.incomingFrames]
 * directly and coexists with an active diagnostic session on the same
 * transport. The end-of-list marker (code `0x0000`) is emitted like any
 * other record — [readDiagnosticInformation] is what stops on it.
 */
class GmlanDiagnosticInformationMonitor(transport: ObdTransport, canId: Int) {

    val dtcs: Flow<GmlanDtc> = transport.incomingFrames
        .filter {
            it.id == canId && it.data.size >= 5 &&
                (it.data[0].toInt() and 0xFF) == GmlanServices.REPORT_DTC_BY_STATUS_MASK
        }
        .map {
            GmlanDtc(
                code = ((it.data[1].toInt() and 0xFF) shl 8) or (it.data[2].toInt() and 0xFF),
                failureType = it.data[3].toInt() and 0xFF,
                status = it.data[4].toInt() and 0xFF,
            )
        }
}

/**
 * readDiagnosticInformation (GMLAN 0xA9), reportDTCByStatusMask (0x81)
 * sub-function: request the DTCs matching [statusMask]. Its reply has no
 * USDT positive response — send it with
 * `DiagnosticSession.readDiagnosticInformation`, not
 * `DiagnosticSession.execute`.
 */
data class ReadDiagnosticInformation(val statusMask: Int) {

    fun encode() = byteArrayOf(
        GmlanServices.READ_DIAGNOSTIC_INFORMATION.toByte(),
        GmlanServices.REPORT_DTC_BY_STATUS_MASK.toByte(),
        statusMask.toByte(),
    )
}

/**
 * Sends [request] fire-and-forget (its reply has no USDT positive response,
 * unlike KWP2000's readDTCByStatus) and collects the DTCs
 * [GmlanDiagnosticInformationMonitor] decodes on [secondaryId], stopping at
 * the DTC `0x0000` end-of-list marker (excluded from the result).
 * Subscribes before sending so no early frame is missed.
 *
 * Only frames observed after the request has actually gone out count: the
 * transport's replay buffer still holds the UUDT frames (end-of-list marker
 * included) of any earlier read on the same connection, and the
 * subscribe-before-send collector drains those synchronously. They are
 * discarded, so a stale answer can never short-circuit this call before the
 * ECU has even seen the new request.
 *
 * Throws [SessionException.ResponseTimeout] if the end marker does not
 * arrive within [timeout] — including when the ECU rejects [request]
 * outright, since that would arrive as a negative response on the ISO-TP
 * channel this function never reads (same trade-off as
 * [GmlanServices.READ_DATA_BY_PACKET_IDENTIFIER]'s fire-and-forget send).
 */
suspend fun DiagnosticSession.readDiagnosticInformation(
    transport: ObdTransport,
    secondaryId: Int,
    request: ReadDiagnosticInformation,
    timeout: Duration,
): List<GmlanDtc> = coroutineScope {
    val dtcs = mutableListOf<GmlanDtc>()
    val endOfList = CompletableDeferred<Unit>()
    val armed = AtomicBoolean(false)
    val collector = launch(start = CoroutineStart.UNDISPATCHED) {
        GmlanDiagnosticInformationMonitor(transport, secondaryId).dtcs.collect { dtc ->
            // Frames still sitting in the transport's replay buffer from an
            // earlier read on this connection arrive here before `armed` is
            // set; discard them so a stale end marker never short-circuits
            // this call before the ECU has even seen the new request.
            if (!armed.get()) return@collect
            if (dtc.code == 0) {
                endOfList.complete(Unit)
            } else {
                dtcs += dtc
            }
        }
    }
    sendWithoutResponse(request.encode())
    armed.set(true)
    val completed = withTimeoutOrNull(timeout) { endOfList.await() } != null
    // cancelAndJoin, not cancel: the collector must have stopped writing to
    // `dtcs` before it is read below.
    collector.cancelAndJoin()
    if (!completed) throw SessionException.ResponseTimeout(GmlanServices.READ_DIAGNOSTIC_INFORMATION)
    dtcs.toList()
}
