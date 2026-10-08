package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.performance.JmhWorkloadInventory
import dev.s7a.strata.runtime.headless.HeadlessImage
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.infra.Blackhole

/**
 * Separates real queued token resolution, full CPU protocol, detached capture and portable output rasterization.
 */
@OptIn(InternalStrataRuntimeApi::class)
public open class NativeCanvasLookupBenchmark {
    /**
     * Borrows every queued target through the actual device's owner/thread/batch checks.
     */
    @Benchmark
    public fun target(
        state: Scene,
        sink: Blackhole,
    ): Unit = state.fixture.resolveTargets(sink)

    /**
     * Includes preparation/index construction, queueing, all target borrows, consumption and nonblocking cleanup.
     */
    @Benchmark
    public fun protocol(
        state: Scene,
        sink: Blackhole,
    ): Unit = state.fixture.protocol(sink)

    /**
     * Captures a detached presentation repeatedly without invoking any live native owner.
     */
    @Benchmark
    public fun capture(state: Scene): List<DrawCommand> = state.fixture.capture()

    /**
     * Includes detached publication and a single capture, diagnosing cold membership/index construction.
     */
    @Benchmark
    public fun captureOneShot(state: Scene): List<DrawCommand> = state.fixture.captureOneShot()

    /**
     * Renders previously captured portable commands, independently controlling output raster cost.
     */
    @Benchmark
    public fun rasterizePrepared(state: Scene): HeadlessImage = state.fixture.rasterizePrepared()

    /**
     * Includes complete detached capture and portable rasterization, without native GPU claims.
     */
    @Benchmark
    public fun captureAndRasterize(state: Scene): HeadlessImage = state.fixture.captureAndRasterize()

    /**
     * Exact current target membership and repeated command occurrences, independent of surrounding portable commands.
     */
    public enum class Placement(
        public val targets: Int,
        public val occurrences: Int,
    ) {
        None(0, 0),
        One(1, 1),
        OneRepeated64(1, 64),
        OneRepeated4096(1, 4096),
        Sixteen(16, 16),
        SixteenRepeated64(16, 64),
        SixteenRepeated4096(16, 4096),
        SixtyFour(64, 64),
        SixtyFourRepeated4096(64, 4096),
    }

    /**
     * One bounded device and detached current receipt set per worker; no measured result history is retained.
     */
    @State(Scope.Thread)
    public open class Scene {
        /**
         * Native membership and occurrence counts.
         */
        @JvmField
        @Param
        public var placement: Placement = Placement.None

        /**
         * Surrounding portable fills, with clip and overlay commands counted separately.
         */
        @JvmField
        @Param("8", "10000")
        public var portable: Int = 8

        /**
         * Whether complete protocol commits a fresh generation or reuses the initial immutable capture.
         */
        @JvmField
        @Param("false", "true")
        public var changed: Boolean = false

        internal lateinit var fixture: NativeCanvasLookupWorkload

        /**
         * Resolves normal constructor handles and primes actual device/CPU receipts outside collection.
         */
        @Setup(Level.Trial)
        public fun setup() {
            fixture = NativeCanvasLookupWorkload(placement.targets, placement.occurrences, portable, changed)
        }

        /**
         * Releases all CPU mock target permits and attachment owners outside measurement.
         */
        @TearDown(Level.Trial)
        public fun close() {
            fixture.close()
        }
    }

    public companion object {
        /**
         * Checks all fixture input combinations, exact ordered image association and terminal owner release untimed.
         */
        @JvmStatic
        public fun verifyWork() {
            check(JmhWorkloadInventory.capture(listOf(NativeCanvasLookupBenchmark::class.java), setOf("avgt")).size == 216)
            for (placement in Placement.entries) {
                for (portable in listOf(8, 10_000)) {
                    for (changed in listOf(false, true)) {
                        NativeCanvasLookupWorkload(placement.targets, placement.occurrences, portable, changed).use { it.verify() }
                    }
                }
            }
        }
    }
}
