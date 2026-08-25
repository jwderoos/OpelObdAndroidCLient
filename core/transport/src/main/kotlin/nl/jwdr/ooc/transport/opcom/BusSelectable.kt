package nl.jwdr.ooc.transport.opcom

/**
 * Optional transport capability: puts the OP-COM interface on [bus] and
 * programs its RX filters for one ECU, replaying the vendor's fixed
 * post-handshake init block (issue #30). No other [nl.jwdr.ooc.transport.ObdTransport]
 * needs this — ELM327/fake/replay transports simply don't implement it, so
 * callers reach for it with an `as?` check.
 */
interface BusSelectable {

    /**
     * Ensures the interface is on [bus] with RX filters for [requestId]'s ECU
     * (its GMLAN UUDT id [secondaryId] on filter slot 3, [responseId] on slot
     * 5 — pass `0` when the ECU has no broadcast id). Cheap to call repeatedly
     * with the same arguments: a no-op once already configured for them.
     * Requires the transport to already be connected.
     */
    suspend fun configureBus(bus: OpComBus, requestId: Int, secondaryId: Int, responseId: Int)
}
