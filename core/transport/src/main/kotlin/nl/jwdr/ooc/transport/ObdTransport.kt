package nl.jwdr.ooc.transport

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Byte-level access to a vehicle bus through some adapter hardware.
 *
 * The protocol layer consumes only this interface; concrete implementations
 * (replay, fake ECU, ELM327, USB serial, ...) live alongside it and normalize
 * their wire format to [CanFrame]s.
 */
interface ObdTransport {
    val state: StateFlow<ConnectionState>

    /** Frames received from the bus. Collectable while [state] is [ConnectionState.Ready]. */
    val incomingFrames: Flow<CanFrame>

    suspend fun connect()

    suspend fun disconnect()

    /** Sends one frame. Throws if the transport is not [ConnectionState.Ready]. */
    suspend fun send(frame: CanFrame)
}
