package nl.jwdr.ooc.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CanLogTest {

    private val minimalLog = """
        # ooc-canlog v1
        0 tx 246 01 A0
        12 rx 646 05 E0 A0 01 02
    """.trimIndent()

    @Test
    fun `parses frame lines into timestamped directed frames`() {
        val log = CanLog.parse(minimalLog)

        assertEquals(
            listOf(
                LoggedFrame(0, Direction.TX, CanFrame(0x246, byteArrayOf(0x01, 0xA0.toByte()))),
                LoggedFrame(
                    12,
                    Direction.RX,
                    CanFrame(0x646, byteArrayOf(0x05, 0xE0.toByte(), 0xA0.toByte(), 0x01, 0x02)),
                ),
            ),
            log.frames,
        )
    }

    @Test
    fun `parses metadata key-value comment lines`() {
        val log = CanLog.parse(
            """
            # ooc-canlog v1
            # vehicle: Astra H
            # adapter: synthetic
            0 tx 246 01 A0
            """.trimIndent(),
        )

        assertEquals(mapOf("vehicle" to "Astra H", "adapter" to "synthetic"), log.metadata)
    }

    @Test
    fun `ignores blank lines and plain comments`() {
        val log = CanLog.parse(
            """
            # ooc-canlog v1

            # just a note without a colon-value shape? no: this has one
            # note this line is ignored as metadata-free comment
            0 tx 246 01 A0
            """.trimIndent(),
        )

        assertEquals(1, log.frames.size)
    }

    @Test
    fun `allows an empty payload`() {
        val log = CanLog.parse("# ooc-canlog v1\n0 rx 646")

        assertEquals(0, log.frames.single().frame.data.size)
    }

    @Test
    fun `missing header throws mentioning line 1`() {
        val e = assertThrows(CanLogParseException::class.java) {
            CanLog.parse("0 tx 246 01 A0")
        }

        assertEquals(1, e.lineNumber)
    }

    @Test
    fun `unsupported version throws`() {
        assertThrows(CanLogParseException::class.java) {
            CanLog.parse("# ooc-canlog v2\n0 tx 246 01")
        }
    }

    @Test
    fun `malformed frame line throws with its line number`() {
        val e = assertThrows(CanLogParseException::class.java) {
            CanLog.parse("# ooc-canlog v1\n0 tx 246 01\nnot a frame line")
        }

        assertEquals(3, e.lineNumber)
    }

    @Test
    fun `invalid direction throws`() {
        assertThrows(CanLogParseException::class.java) {
            CanLog.parse("# ooc-canlog v1\n0 up 246 01")
        }
    }

    @Test
    fun `payload longer than 8 bytes throws with its line number`() {
        val e = assertThrows(CanLogParseException::class.java) {
            CanLog.parse("# ooc-canlog v1\n0 rx 646 01 02 03 04 05 06 07 08 09")
        }

        assertEquals(2, e.lineNumber)
    }

    @Test
    fun `decreasing timestamps throw`() {
        val e = assertThrows(CanLogParseException::class.java) {
            CanLog.parse("# ooc-canlog v1\n10 tx 246 01\n5 rx 646 02")
        }

        assertEquals(3, e.lineNumber)
    }

    @Test
    fun `format then parse round-trips frames and metadata`() {
        val original = CanLog.parse(
            """
            # ooc-canlog v1
            # vehicle: Astra H
            0 tx 246 01 A0
            12 rx 646 05 E0 A0 01 02
            250 tx 246 02 21 05
            """.trimIndent(),
        )

        val reparsed = CanLog.parse(original.format())

        assertEquals(original, reparsed)
    }

    @Test
    fun `format starts with the version header`() {
        val log = CanLog(emptyMap(), emptyList())

        assertTrue(log.format().startsWith("# ooc-canlog v1"))
    }
}
