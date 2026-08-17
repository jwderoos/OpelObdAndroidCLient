package nl.jwdr.ooc.transport

/** Lifecycle of a transport connection, observable by the UI. */
sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data object Ready : ConnectionState
    data class Error(val cause: Throwable) : ConnectionState
}
