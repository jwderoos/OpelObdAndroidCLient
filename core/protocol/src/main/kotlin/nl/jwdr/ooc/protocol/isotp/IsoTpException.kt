package nl.jwdr.ooc.protocol.isotp

/** Failure of an ISO-TP send or reassembly. */
sealed class IsoTpException(message: String) : Exception(message) {

    /** The ECU never answered our first frame with flow control. */
    class FlowControlTimeout : IsoTpException("timed out waiting for flow control")

    /** A multi-frame response stalled mid-message. */
    class ConsecutiveFrameTimeout : IsoTpException("timed out waiting for a consecutive frame")

    /** The ECU reported it cannot buffer a message of the announced length. */
    class Overflow : IsoTpException("receiver reported buffer overflow")

    /** A consecutive frame arrived out of order. */
    class SequenceError(expected: Int, actual: Int) :
        IsoTpException("expected consecutive frame $expected, got $actual")
}
