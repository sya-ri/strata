package dev.s7a.strata.performance

/**
 * Deterministic native field surface, independent of actual Minecraft and any timing expectations.
 */
internal class NativeMeterFixture {
    @JvmField var renderExtractionCount = 0L

    @JvmField var hostFrameCount = 0L

    @JvmField var framePreparationCount = 0L

    @JvmField var portableRasterizationCount = 0L

    @JvmField var textureUploadCount = 0L

    @JvmField var sampledImageDirectHitCount = 0L

    @JvmField var sampledImageDirectMissCount = 0L

    @JvmField var sampledImageUploadCount = 0L

    @JvmField var sampledImageDrawCount = 0L

    @JvmField var sampledImageEvictionCount = 0L

    @JvmField var sampledImageCapacityFallbackCount = 0L

    @JvmField var sampledImageIneligibleFallbackCount = 0L

    @JvmField var sampledImageRetainedEntryCount = 0L

    @JvmField var sampledImageRetainedByteCount = 0L

    @JvmField var preparedCommands = listOf("fixture command")
    var monitorClosed = false
    var retiredRecords = 0
    var work = 0L
    var overflowed = false

    /**
     * Creates a simulated owner monitor whose lifetime can be asserted after success and failure.
     */
    fun startRenderMonitoring(): Monitor = Monitor(this) { monitorClosed = true }

    /**
     * Simulated diagnostics contract for owner-thread lifetime assertions.
     */
    class Monitor(
        private val owner: NativeMeterFixture,
        private val release: () -> Unit,
    ) {
        /**
         * Returns complete fixture diagnostics without sampling or aggregation.
         */
        fun snapshot(): Snapshot = Snapshot(owner.overflowed || 4096 <= owner.retiredRecords, owner.work)

        /**
         * Forgets completed retired identities while preserving the live owner.
         */
        fun checkpoint() {
            owner.retiredRecords = 0
            owner.work = 0L
        }

        /**
         * Relinquishes the fixture monitor owner.
         */
        fun close() {
            release()
        }
    }

    /**
     * Simulated immutable diagnostic snapshot, containing no inferred work.
     */
    class Snapshot(
        val overflowed: Boolean,
        work: Long,
    ) {
        val activeSubscriptions = 0
        val nodes = emptyList<Any>()
        val counts = mapOf("FrameFailure" to 0L, "NodeCreate" to work)
    }
}
