package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.performance.JmhWorkloadInventory
import dev.s7a.strata.runtime.spi.RuntimeUiFrame
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown

/**
 * Complete real retained scroll opportunities with explicit public input and direct outside-node SPI cases.
 * Profiles, geometry, keys and immutable events are prepared before timing; every score includes its stated frame boundary.
 * Restoring cycles include all restoration work in both CPU and allocation and do not measure isolated event latency.
 */
public open class ScrollInputBenchmark {
    /**
     * Executes the selected complete input/frame opportunity or restoring changed-state cycle.
     */
    @Benchmark
    public fun input(scene: Scene): RuntimeUiFrame = scene.operation()

    /**
     * Complete generated corpus; outside SPI cases exercise branches unavailable through standalone host hit testing.
     */
    public enum class Case(
        internal val action: Action,
        internal val endpoint: Endpoint = Endpoint.Top,
        internal val bars: Boolean = true,
        internal val nested: Boolean = false,
    ) {
        WheelTop(Action.WheelOutward),
        WheelBottom(Action.WheelOutward, Endpoint.Bottom),
        WheelZeroTop(Action.WheelZero),
        WheelZeroBottom(Action.WheelZero, Endpoint.Bottom),
        WheelFractionalCycle(Action.WheelFractional),
        WheelOrdinaryCycle(Action.WheelOrdinary),
        WheelReverseBottomCycle(Action.WheelOrdinary, Endpoint.Bottom),
        WheelSignedZeroCycle(Action.SignedZero),
        WheelNonfiniteMultiplication(Action.Overflow),
        WheelDirtyLeafCycle(Action.DirtyLeaf),
        WheelExternalStateCycle(Action.ExternalState),
        WheelGeometryCycle(Action.Geometry),
        WheelCoalescedTopFour(Action.CoalescedUnchanged),
        WheelCoalescedBottomFour(Action.CoalescedUnchanged, Endpoint.Bottom),
        WheelCoalescedChangedCycle(Action.CoalescedChanged),
        NestedWheelTop(Action.WheelOutward, nested = true),
        NestedWheelBottom(Action.WheelOutward, Endpoint.Bottom, nested = true),
        NestedWheelFractionalCycle(Action.WheelFractional, nested = true),
        BarTopInTrackOutward(Action.BarOutward),
        BarBottomInTrackOutward(Action.BarOutward, Endpoint.Bottom),
        BarZeroTop(Action.BarZero),
        BarZeroBottom(Action.BarZero, Endpoint.Bottom),
        BarFractionalCycle(Action.BarFractional),
        BarOrdinaryCycle(Action.BarOrdinary),
        BarReverseBottomCycle(Action.BarOrdinary, Endpoint.Bottom),
        BarOutsideTopSpi(Action.BarOutside),
        BarOutsideBottomSpi(Action.BarOutside, Endpoint.Bottom),
        WheelTopWithoutBars(Action.WheelOutward, bars = false),
        WheelBottomWithoutBars(Action.WheelOutward, Endpoint.Bottom, bars = false),
        CleanTop(Action.Clean),
        CleanBottom(Action.Clean, Endpoint.Bottom),
    }

    /**
     * Input algorithms with distinct public opportunity boundaries.
     */
    internal enum class Action {
        WheelOutward,
        WheelZero,
        WheelFractional,
        WheelOrdinary,
        SignedZero,
        Overflow,
        DirtyLeaf,
        ExternalState,
        Geometry,
        CoalescedUnchanged,
        CoalescedChanged,
        BarOutward,
        BarZero,
        BarFractional,
        BarOrdinary,
        BarOutside,
        Clean,
    }

    /**
     * Exact primed endpoint before collection.
     */
    internal enum class Endpoint {
        Top,
        Bottom,
    }

    /**
     * One owner-thread retained host with no diagnostics or callback traces during sampling.
     */
    @State(Scope.Thread)
    public open class Scene {
        /**
         * Exact generated input/control case.
         */
        @JvmField
        @Param
        public var workload: Case = Case.WheelTop

        private lateinit var fixture: ScrollInputFixture

        /**
         * Primes the stated endpoint and verifies state, ordered observers, independent pixels and bounded callback work.
         */
        @Setup(Level.Trial)
        public fun setUp() {
            fixture = ScrollInputFixture(workload)
            try {
                fixture.verify()
            } catch (failure: Throwable) {
                try {
                    fixture.close()
                } catch (cleanup: Throwable) {
                    failure.addSuppressed(cleanup)
                }
                throw failure
            }
        }

        /**
         * Executes only the declared prepared opportunity and actual runtime input/state/frame operations.
         */
        public fun operation(): RuntimeUiFrame = fixture.operation()

        /**
         * Releases retained state observers and any active drag on the owner worker.
         */
        @TearDown(Level.Trial)
        public fun close(): Unit = fixture.close()
    }

    /**
     * Entry point discovered by the existing generic fixture selection and verification contract.
     */
    public companion object {
        /**
         * Requires all 31 generated cases and verifies every frozen fixture without wall-clock thresholds.
         */
        @JvmStatic
        public fun verifyWork() {
            check(JmhWorkloadInventory.capture(listOf(ScrollInputBenchmark::class.java), setOf("avgt")).size == 31)
            for (workload in Case.entries) ScrollInputFixture(workload).use { fixture -> fixture.verify() }
        }
    }
}
