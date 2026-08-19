package nl.jwdr.ooc.service

import nl.jwdr.ooc.transport.ConnectionState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionHolderPolicyTest {

    @Test
    fun `runs when Ready and not simulated`() {
        assertTrue(shouldRunConnectionHolder(ConnectionState.Ready, isSimulated = false))
    }

    @Test
    fun `does not run when Ready but simulated`() {
        assertFalse(shouldRunConnectionHolder(ConnectionState.Ready, isSimulated = true))
    }

    @Test
    fun `does not run when Disconnected`() {
        assertFalse(shouldRunConnectionHolder(ConnectionState.Disconnected, isSimulated = false))
    }

    @Test
    fun `does not run when Connecting`() {
        assertFalse(shouldRunConnectionHolder(ConnectionState.Connecting, isSimulated = false))
    }

    @Test
    fun `does not run on Error`() {
        assertFalse(
            shouldRunConnectionHolder(ConnectionState.Error(RuntimeException("boom")), isSimulated = false),
        )
    }
}
