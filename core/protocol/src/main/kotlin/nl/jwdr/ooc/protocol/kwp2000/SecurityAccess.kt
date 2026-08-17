package nl.jwdr.ooc.protocol.kwp2000

/**
 * securityAccess (0x27), as a seed/key pair: an odd access mode requests a
 * seed, the following even mode sends the key computed from it. Key
 * computation itself is a pluggable, user-supplied algorithm and lives
 * outside this repository.
 */
object SecurityAccess {

    /** Requests the seed for the odd [accessMode]. */
    data class RequestSeed(val accessMode: Int) : KwpRequest<RequestSeed.Response> {

        init {
            require(accessMode % 2 == 1) { "seed requests use an odd access mode, got $accessMode" }
        }

        override fun encode() = byteArrayOf(0x27, accessMode.toByte())

        override fun decodeResponse(payload: ByteArray): Response {
            checkPositiveResponse(0x27, payload, minLength = 2)
            return Response(seed = payload.copyOfRange(2, payload.size))
        }

        class Response(val seed: ByteArray) {
            /** An all-zero seed means the ECU is already unlocked; no key is needed. */
            val alreadyUnlocked: Boolean get() = seed.all { it == 0.toByte() }
        }
    }

    /** Sends the [key] for the even [accessMode] following the seed request's mode. */
    class SendKey(val accessMode: Int, val key: ByteArray) : KwpRequest<Unit> {

        init {
            require(accessMode % 2 == 0) { "key submissions use an even access mode, got $accessMode" }
        }

        override fun encode() = byteArrayOf(0x27, accessMode.toByte()) + key

        override fun decodeResponse(payload: ByteArray) {
            checkPositiveResponse(0x27, payload)
        }
    }
}
