package nl.jwdr.ooc.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class TransportSelectionTest {

    @Test
    fun `demo round-trips through its persisted form`() {
        val persisted = TransportSelection.Demo.encode()
        assertEquals(TransportSelection.Demo, TransportSelection.decode(persisted))
    }

    @Test
    fun `an elm327 device round-trips through its persisted form`() {
        val selection = TransportSelection.Elm327Bluetooth(address = "00:11:22:AA:BB:CC", name = "OBDII")
        assertEquals(selection, TransportSelection.decode(selection.encode()))
    }

    @Test
    fun `a device name containing the separator still round-trips`() {
        // Bluetooth names are user-editable; "OBD|Car" must not decode to Demo.
        val selection = TransportSelection.Elm327Bluetooth(address = "00:11:22:AA:BB:CC", name = "OBD|Car")
        assertEquals(selection, TransportSelection.decode(selection.encode()))
    }

    @Test
    fun `garbage or absent persisted values fall back to demo`() {
        assertEquals(TransportSelection.Demo, TransportSelection.decode(null))
        assertEquals(TransportSelection.Demo, TransportSelection.decode(""))
        assertEquals(TransportSelection.Demo, TransportSelection.decode("elm327|"))
        assertEquals(TransportSelection.Demo, TransportSelection.decode("bogus|x|y"))
    }
}
