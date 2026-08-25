package nl.jwdr.ooc.transport.opcom

import nl.jwdr.ooc.transport.CanFrame

/** A decoded OP-COM USB serial record (the payload of one length/checksum frame). */
sealed interface OpComRecord {

    /** A `0xNN + 0x40` reply to a previously sent command, e.g. `EB` for `AB`, `D0` for `90`. */
    data class Response(val code: Int, val payload: ByteArray) : OpComRecord {
        override fun equals(other: Any?): Boolean =
            other is Response && other.code == code && other.payload.contentEquals(payload)

        override fun hashCode(): Int = 31 * code + payload.contentHashCode()
    }

    /** A `91` record: a CAN frame received from the bus. */
    data class RxFrame(val frame: CanFrame) : OpComRecord

    /** A `7F` record: unsolicited keep-alive/status, carries no frame data. */
    data object KeepAlive : OpComRecord
}
