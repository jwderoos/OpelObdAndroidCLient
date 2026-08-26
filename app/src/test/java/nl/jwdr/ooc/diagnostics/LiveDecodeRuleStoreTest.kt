package nl.jwdr.ooc.diagnostics

import nl.jwdr.ooc.catalog.LiveDecodeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveDecodeRuleStoreTest {

    private val json = """
        {"HASTRAREC":[
          {"row":1,"dpid":1,"t":"flag","byte":0,"mask":1,"eq":1},
          {"row":11,"dpid":1,"t":"num","byte":3,"factor":0.1},
          {"row":39,"dpid":126,"t":"state","byte":0},
          {"row":12,"dpid":2,"t":"raw","byte":0}
        ]}
    """.trimIndent()

    private val store = LiveDecodeRuleStore { json.byteInputStream() }

    @Test
    fun `parses each rule kind keyed by catalog then row number`() {
        val rec = store.rulesFor("HASTRAREC")
        assertEquals(LiveDecodeRule.Flag(dpid = 1, byte = 0, mask = 1, eq = 1), rec[1])
        assertEquals(LiveDecodeRule.Numeric(dpid = 1, byte = 3, factor = 0.1), rec[11])
        assertEquals(LiveDecodeRule.StateByte(dpid = 126, byte = 0), rec[39])
        assertEquals(LiveDecodeRule.RawByte(dpid = 2, byte = 0), rec[12])
    }

    @Test
    fun `an unknown catalog has no rules`() {
        assertTrue(store.rulesFor("NOPE").isEmpty())
    }
}
