package nl.jwdr.ooc.protocol.isotp

/**
 * The CAN identifier pair of one ECU's diagnostic channel: we transmit on
 * [requestId], the ECU answers on [responseId]. Pairs come from the imported
 * catalog's address map.
 */
data class IsoTpAddress(
    val requestId: Int,
    val responseId: Int,
)
