package nl.jwdr.ooc.diagnostics

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import nl.jwdr.ooc.transport.CanFrame
import nl.jwdr.ooc.transport.CanLog
import nl.jwdr.ooc.transport.Direction
import nl.jwdr.ooc.transport.LoggedFrame
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionCaptureStoreTest {

    private val root: File = Files.createTempDirectory("captures").toFile()
    private var now = 1_700_000_000_000L
    private val store = SessionCaptureStore(root, clock = { now })

    @After
    fun cleanup() {
        root.deleteRecursively()
    }

    @Test
    fun `a session writes canlog and usb trace into one timestamped directory`() {
        val sink = store.openSession(mapOf("transport" to "opcom-usb"))
        now += 5
        store.trace("open: port ready")
        now += 5
        sink.frame(LoggedFrame(10, Direction.TX, CanFrame(0x7E0, byteArrayOf(0x02, 0x01, 0x00))))
        sink.event(11, "read DTC ecu=Engine")
        sink.close()

        val dir = store.latestSession()!!
        assertEquals(setOf("session.canlog", "usb.trace"), dir.listFiles()!!.map { it.name }.toSet())
        val log = CanLog.parse(File(dir, "session.canlog").readText())
        assertEquals(mapOf("transport" to "opcom-usb"), log.metadata)
        assertEquals(1, log.frames.size)
        assertTrue(File(dir, "session.canlog").readText().contains("# event 11: read DTC ecu=Engine"))
        assertEquals("5 open: port ready\n", File(dir, "usb.trace").readText())
    }

    @Test
    fun `lines are flushed as they are written, not on close`() {
        val sink = store.openSession(emptyMap())
        sink.frame(LoggedFrame(0, Direction.RX, CanFrame(0x7E8, byteArrayOf(0x41))))
        store.trace("read [41]")

        val dir = store.latestSession()!!
        assertTrue(File(dir, "session.canlog").readText().endsWith("0 rx 7e8 41\n"))
        assertEquals("0 read [41]\n", File(dir, "usb.trace").readText())
        sink.close()
    }

    @Test
    fun `trace outside a session is dropped`() {
        store.trace("nobody listening")
        assertNull(store.latestSession())
        assertFalse(root.exists() && root.listFiles()!!.isNotEmpty())
    }

    @Test
    fun `latestSession picks the newest directory and zipLatest bundles it`() {
        store.openSession(emptyMap()).close()
        now += 60_000
        val second = store.openSession(emptyMap())
        store.trace("second")
        second.close()

        val latest = store.latestSession()!!
        assertEquals("0 second\n", File(latest, "usb.trace").readText())
        val zip = store.zipLatest()!!
        assertEquals(latest.name + ".zip", zip.name)
        ZipFile(zip).use { z ->
            assertEquals(
                setOf("${latest.name}/session.canlog", "${latest.name}/usb.trace"),
                z.entries().asSequence().map { it.name }.toSet(),
            )
        }
    }
}
