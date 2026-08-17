package nl.jwdr.ooc.protocol.kwp2000

/** A KWP2000 negative response code (ISO 14230-3 / GMLAN). */
sealed class KwpError(val code: Int) {
    object GeneralReject : KwpError(0x10)
    object ServiceNotSupported : KwpError(0x11)
    object SubFunctionNotSupported : KwpError(0x12)
    object BusyRepeatRequest : KwpError(0x21)
    object ConditionsNotCorrect : KwpError(0x22)
    object RoutineNotComplete : KwpError(0x23)
    object RequestOutOfRange : KwpError(0x31)
    object SecurityAccessDenied : KwpError(0x33)
    object InvalidKey : KwpError(0x35)
    object ExceededNumberOfAttempts : KwpError(0x36)
    object RequiredTimeDelayNotExpired : KwpError(0x37)

    /** Not a failure: the ECU asks the tester to extend its response timeout. */
    object ResponsePending : KwpError(0x78)
    object ServiceNotSupportedInActiveSession : KwpError(0x80)
    data class Unknown(val rawCode: Int) : KwpError(rawCode)

    override fun toString(): String = "${this::class.simpleName}(0x%02X)".format(code)

    companion object {
        fun fromCode(code: Int): KwpError = when (code) {
            0x10 -> GeneralReject
            0x11 -> ServiceNotSupported
            0x12 -> SubFunctionNotSupported
            0x21 -> BusyRepeatRequest
            0x22 -> ConditionsNotCorrect
            0x23 -> RoutineNotComplete
            0x31 -> RequestOutOfRange
            0x33 -> SecurityAccessDenied
            0x35 -> InvalidKey
            0x36 -> ExceededNumberOfAttempts
            0x37 -> RequiredTimeDelayNotExpired
            0x78 -> ResponsePending
            0x80 -> ServiceNotSupportedInActiveSession
            else -> Unknown(code)
        }
    }
}

/** The ECU answered [serviceId] with a negative response carrying [error]. */
class KwpNegativeResponseException(val serviceId: Int, val error: KwpError) :
    Exception("service 0x%02X rejected: %s".format(serviceId, error))

/** The response payload does not parse as a response to the request. */
class KwpDecodeException(message: String) : Exception(message)
