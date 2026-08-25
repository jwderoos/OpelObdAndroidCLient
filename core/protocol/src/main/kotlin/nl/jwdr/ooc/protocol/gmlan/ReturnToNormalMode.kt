package nl.jwdr.ooc.protocol.gmlan

import nl.jwdr.ooc.protocol.kwp2000.KwpRequest
import nl.jwdr.ooc.protocol.kwp2000.checkPositiveResponse

/**
 * returnToNormalMode (GMLAN 0x20): the opening request of every recorded
 * GMLAN session, sent before ECU identification or DTC reads.
 */
object ReturnToNormalMode : KwpRequest<Unit> {

    override fun encode() = byteArrayOf(0x20)

    override fun decodeResponse(payload: ByteArray) {
        checkPositiveResponse(0x20, payload)
    }
}
