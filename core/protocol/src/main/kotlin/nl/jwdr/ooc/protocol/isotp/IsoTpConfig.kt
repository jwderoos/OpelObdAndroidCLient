package nl.jwdr.ooc.protocol.isotp

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Tuning of one ISO-TP channel.
 *
 * @param flowControlTimeout how long a segmented send waits for the ECU's
 *   flow control before failing.
 * @param consecutiveFrameTimeout how long reassembly waits for each
 *   consecutive frame before failing.
 * @param rxBlockSize block size we advertise in flow control when receiving;
 *   0 lets the sender stream everything without further flow control.
 * @param rxSeparationTimeRaw STmin byte we advertise when receiving
 *   (0x00..0x7F ms, 0xF1..0xF9 = 100..900 µs).
 * @param padByte frames are padded to 8 bytes with this value, as GMLAN
 *   expects; null disables padding.
 */
data class IsoTpConfig(
    val flowControlTimeout: Duration = 1.seconds,
    val consecutiveFrameTimeout: Duration = 1.seconds,
    val rxBlockSize: Int = 0,
    val rxSeparationTimeRaw: Int = 0,
    val padByte: Byte? = 0xAA.toByte(),
)
