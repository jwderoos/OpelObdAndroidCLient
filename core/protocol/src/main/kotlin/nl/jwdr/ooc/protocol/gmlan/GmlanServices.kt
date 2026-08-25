package nl.jwdr.ooc.protocol.gmlan

/** GMLAN service ids the stack must recognize in catalog command records. */
object GmlanServices {
    /**
     * ReadDataByPacketIdentifier. Has no USDT positive response: its replies
     * arrive as raw UUDT frames on the ECU's secondary CAN id (see
     * [PeriodicDataMonitor]), so it must be sent without awaiting one.
     */
    const val READ_DATA_BY_PACKET_IDENTIFIER = 0xAA

    /**
     * ReadDiagnosticInformation. Like [READ_DATA_BY_PACKET_IDENTIFIER], its
     * DTC list has no USDT positive response: it arrives as UUDT frames on
     * the ECU's secondary CAN id (see [GmlanDiagnosticInformationMonitor]),
     * so it must be sent without awaiting one.
     */
    const val READ_DIAGNOSTIC_INFORMATION = 0xA9

    /**
     * reportDTCByStatusMask sub-function of [READ_DIAGNOSTIC_INFORMATION].
     * Also the marker byte (byte 0) every UUDT reply frame carries.
     */
    const val REPORT_DTC_BY_STATUS_MASK = 0x81
}
