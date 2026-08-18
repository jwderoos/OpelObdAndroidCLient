package nl.jwdr.ooc.ui

import androidx.annotation.StringRes
import nl.jwdr.ooc.R
import nl.jwdr.ooc.protocol.session.SessionException

/** A user-readable message: a string resource plus its format arguments. */
data class UserMessage(
    @param:StringRes val resId: Int,
    val formatArgs: List<Any> = emptyList(),
)

/**
 * Maps typed protocol failures to user-readable messages. The single place
 * for this mapping — every screen reuses it instead of rendering exceptions.
 */
fun userMessageFor(failure: Throwable): UserMessage = when (failure) {
    is SessionException.ResponseTimeout ->
        UserMessage(R.string.error_response_timeout)
    is SessionException.NegativeResponse ->
        UserMessage(R.string.error_negative_response, listOf(failure.error.toString()))
    is SessionException.TransportLost ->
        UserMessage(R.string.error_transport_lost)
    is SessionException.SessionClosed ->
        UserMessage(R.string.error_session_closed)
    else ->
        UserMessage(R.string.error_generic_communication)
}
