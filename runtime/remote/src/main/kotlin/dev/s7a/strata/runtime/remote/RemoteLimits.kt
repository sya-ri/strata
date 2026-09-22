package dev.s7a.strata.runtime.remote

/**
 * Hard per-connection admission bounds, negotiated by taking each endpoint's minimum.
 * Byte limits include encoded headers; values are checked before incoming allocation.
 */
public data class RemoteLimits(
    public val frameBytes: Int = 24 * 1024,
    public val messageBytes: Int = 8 * 1024 * 1024,
    public val valueDepth: Int = 64,
    public val collectionEntries: Int = 65536,
    public val treeNodes: Int = 8192,
    public val pendingBytes: Int = 16 * 1024 * 1024,
    public val assemblyMillis: Long = 10000,
    public val valueEntries: Int = minOf(messageBytes, 262144),
    public val reconstructionMillis: Long = 1000,
) {
    init {
        require(64 <= frameBytes && frameBytes <= messageBytes) { "Invalid frame bound." }
        require(messageBytes <= pendingBytes) { "A message must fit the pending-byte budget." }
        require(0 < valueDepth && valueDepth <= 256) { "Invalid nesting bound." }
        require(0 < collectionEntries && collectionEntries <= messageBytes) { "Invalid collection bound." }
        require(0 < treeNodes && treeNodes <= collectionEntries) { "Invalid node bound." }
        require(0 < assemblyMillis) { "Assembly timeout must be positive." }
        require(0 < valueEntries && valueEntries <= messageBytes) { "Invalid aggregate value bound." }
        require(reconstructionMillis in 1..60000) { "Invalid reconstruction time bound." }
    }

    /**
     * Returns limits no larger than either endpoint advertised.
     */
    public fun intersect(other: RemoteLimits): RemoteLimits =
        RemoteLimits(
            minOf(frameBytes, other.frameBytes),
            minOf(messageBytes, other.messageBytes),
            minOf(valueDepth, other.valueDepth),
            minOf(collectionEntries, other.collectionEntries),
            minOf(treeNodes, other.treeNodes),
            minOf(pendingBytes, other.pendingBytes),
            minOf(assemblyMillis, other.assemblyMillis),
            minOf(valueEntries, other.valueEntries),
            minOf(reconstructionMillis, other.reconstructionMillis),
        )
}
