package nl.jwdr.ooc.protocol.obd2

import java.util.Locale

/** One standard mode-01 PID with its public SAE J1979 scaling. */
class Obd2Pid(
    val id: Int,
    val name: String,
    val unit: String?,
    private val decimals: Int,
    private val compute: (ByteArray) -> Double,
) {
    /** The scaled engineering value of [data]. */
    fun value(data: ByteArray): Double = compute(data)

    /** The scaled value of [data], formatted with this PID's precision. */
    fun format(data: ByteArray): String =
        "%.${decimals}f".format(Locale.ROOT, value(data))
}

/** The common current-data PIDs of the generic OBD-II fallback mode. */
object Obd2Pids {

    private fun a(data: ByteArray) = data[0].toInt() and 0xFF
    private fun ab(data: ByteArray) = (a(data) shl 8) or (data[1].toInt() and 0xFF)

    val all: List<Obd2Pid> = listOf(
        Obd2Pid(0x04, "Calculated engine load", "%", 1) { a(it) * 100.0 / 255 },
        Obd2Pid(0x05, "Engine coolant temperature", "°C", 0) { a(it) - 40.0 },
        Obd2Pid(0x06, "Short term fuel trim, bank 1", "%", 1) { (a(it) - 128) * 100.0 / 128 },
        Obd2Pid(0x07, "Long term fuel trim, bank 1", "%", 1) { (a(it) - 128) * 100.0 / 128 },
        Obd2Pid(0x0A, "Fuel pressure", "kPa", 0) { a(it) * 3.0 },
        Obd2Pid(0x0B, "Intake manifold pressure", "kPa", 0) { a(it).toDouble() },
        Obd2Pid(0x0C, "Engine speed", "rpm", 0) { ab(it) / 4.0 },
        Obd2Pid(0x0D, "Vehicle speed", "km/h", 0) { a(it).toDouble() },
        Obd2Pid(0x0E, "Timing advance", "°", 1) { a(it) / 2.0 - 64 },
        Obd2Pid(0x0F, "Intake air temperature", "°C", 0) { a(it) - 40.0 },
        Obd2Pid(0x10, "Mass air flow rate", "g/s", 2) { ab(it) / 100.0 },
        Obd2Pid(0x11, "Throttle position", "%", 1) { a(it) * 100.0 / 255 },
        Obd2Pid(0x1F, "Run time since engine start", "s", 0) { ab(it).toDouble() },
        Obd2Pid(0x21, "Distance with MIL on", "km", 0) { ab(it).toDouble() },
        Obd2Pid(0x2F, "Fuel level", "%", 1) { a(it) * 100.0 / 255 },
        Obd2Pid(0x33, "Barometric pressure", "kPa", 0) { a(it).toDouble() },
        Obd2Pid(0x42, "Control module voltage", "V", 2) { ab(it) / 1000.0 },
        Obd2Pid(0x45, "Relative throttle position", "%", 1) { a(it) * 100.0 / 255 },
        Obd2Pid(0x46, "Ambient air temperature", "°C", 0) { a(it) - 40.0 },
        Obd2Pid(0x5C, "Engine oil temperature", "°C", 0) { a(it) - 40.0 },
    )

    private val byId = all.associateBy { it.id }

    fun byId(id: Int): Obd2Pid? = byId[id]

    /**
     * Expands a supported-PID bitmask (PID 0x00/0x20/0x40…) into PID numbers:
     * the MSB of byte 0 is [basePid]+1, and so on for 32 bits.
     */
    fun supportedFrom(basePid: Int, data: ByteArray): Set<Int> {
        val supported = mutableSetOf<Int>()
        for (byteIndex in data.indices) {
            val byte = data[byteIndex].toInt() and 0xFF
            for (bit in 0 until 8) {
                if (byte and (0x80 ushr bit) != 0) {
                    supported += basePid + byteIndex * 8 + bit + 1
                }
            }
        }
        return supported
    }
}
