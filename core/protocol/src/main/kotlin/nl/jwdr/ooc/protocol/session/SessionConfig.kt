package nl.jwdr.ooc.protocol.session

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Timing and retry policy of one [DiagnosticSession].
 *
 * @param responseTimeout how long a request waits for the ECU's response
 *   before the attempt counts as lost.
 * @param pendingTimeout the extended deadline armed each time the ECU answers
 *   responsePending (0x78).
 * @param maxRetries how many times a request is re-sent after a lost attempt
 *   or busyRepeatRequest (0x21) before failing.
 * @param testerPresentInterval bus-idle time after which the keep-alive sends
 *   testerPresent; any completed request resets the timer.
 */
data class SessionConfig(
    val responseTimeout: Duration = 1.seconds,
    val pendingTimeout: Duration = 5.seconds,
    val maxRetries: Int = 2,
    val testerPresentInterval: Duration = 2.seconds,
)
