package nl.jwdr.ooc.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards that the committed synthetic sample stays a valid ooc-canlog v1 document. */
class SampleCanLogTest {

    @Test
    fun `synthetic sample log parses`() {
        val resource = javaClass.getResource("/canlog/synthetic-session.canlog")
        assertNotNull("sample log missing from test resources", resource)

        val log = CanLog.parse(resource!!.readText())

        assertEquals("synthetic", log.metadata["vehicle"])
        assertTrue("sample should contain both directions", log.frames.any { it.direction == Direction.TX })
        assertTrue(log.frames.any { it.direction == Direction.RX })
    }
}
