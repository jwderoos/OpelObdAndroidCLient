package nl.jwdr.ooc.protocol.gmlan

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import nl.jwdr.ooc.transport.ObdTransport

/** One GMLAN UUDT periodic-data frame: DPID number plus its data bytes. */
class DpidRecord(val dpid: Int, val data: ByteArray)

/**
 * Decodes GMLAN periodic-data broadcasts, scheduled by a
 * ReadDataByPacketIdentifier request, from the raw frames on one secondary
 * CAN id: byte 0 is the DPID number, the rest is data. The frames are not
 * ISO-TP; this observes [ObdTransport.incomingFrames] directly and coexists
 * with an active diagnostic session on the same transport.
 */
class PeriodicDataMonitor(transport: ObdTransport, canId: Int) {

    val records: Flow<DpidRecord> = transport.incomingFrames
        .filter { it.id == canId && it.data.isNotEmpty() }
        .map { DpidRecord(it.data[0].toInt() and 0xFF, it.data.copyOfRange(1, it.data.size)) }
}
