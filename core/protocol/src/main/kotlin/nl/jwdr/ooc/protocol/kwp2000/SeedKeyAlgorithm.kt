package nl.jwdr.ooc.protocol.kwp2000

/**
 * Computes the key for a SecurityAccess seed. Concrete algorithms are
 * proprietary per-ECU and are never committed to this repository — callers
 * supply their own, like imported catalogs.
 */
fun interface SeedKeyAlgorithm {
    /**
     * Computes the key for [seed] at security [level] (the odd access mode
     * used in the seed request). Pure and synchronous — no I/O, no
     * suspension.
     */
    fun computeKey(seed: ByteArray, level: Int): ByteArray
}
