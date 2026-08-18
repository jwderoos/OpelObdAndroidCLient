package nl.jwdr.ooc.diagnostics

import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import nl.jwdr.ooc.protocol.obd2.Obd2Pids
import nl.jwdr.ooc.transport.elm327.Elm327Transport
import nl.jwdr.ooc.transport.elm327.ScriptedElm327Link
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * End-to-end JVM check of the live-data path over an ELM327: the OBD-II
 * fallback in [DiagnosticsManager] drives the real ISO-TP stack, which
 * drives [Elm327Transport], which talks scripted ELM dialogue — exactly the
 * layering used in the car, minus the Bluetooth socket.
 */
class Elm327ObdIiIntegrationTest {

    private val engine = EcuScanTarget(name = "0x7E0", requestId = 0x7E0, responseId = 0x7E8)

    @Test
    fun `polls engine rpm through the full stack over scripted ELM dialogue`() = runTest {
        val link = ScriptedElm327Link()
        // Mode 01 PID 0C request, ISO-TP single frame padded to 8 with 0xAA.
        link.on("02010CAAAAAAAAAA", "SEARCHING...\r7E804410C1AF8AAAAAA\r\r>")
        val transport = Elm327Transport(link)
        val manager = DiagnosticsManager(transport)
        manager.connect()
        val rpm = requireNotNull(Obd2Pids.byId(0x0C))

        val reading = manager.pollObd2Pids(engine, listOf(rpm), 100.milliseconds).first().single()

        assertEquals(1726.0, reading.value, 0.0)
        assertEquals("rpm", reading.pid.unit)
        assertEquals(
            "the transport must select the physical header before the request",
            listOf("ATSH7E0", "02010CAAAAAAAAAA"),
            link.written.drop(11), // after the 11-command init sequence
        )
    }
}
