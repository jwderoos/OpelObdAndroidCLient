package nl.jwdr.ooc.protocol.gmlan

/** GMLAN service ids the stack must recognize in catalog command records. */
object GmlanServices {
    /**
     * ReadDataByPacketIdentifier. Has no USDT positive response: its replies
     * arrive as raw UUDT frames on the ECU's secondary CAN id (see
     * [PeriodicDataMonitor]), so it must be sent without awaiting one.
     */
    const val READ_DATA_BY_PACKET_IDENTIFIER = 0xAA
}
