package nl.jwdr.ooc.service

import nl.jwdr.ooc.transport.ConnectionState

/**
 * True while a foreground service should be running to protect a live,
 * non-simulated session from the OS killing the process while the app is
 * backgrounded. Simulated/replay sessions never need it (#20).
 */
fun shouldRunConnectionHolder(state: ConnectionState, isSimulated: Boolean): Boolean =
    state is ConnectionState.Ready && !isSimulated
