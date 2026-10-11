package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.performance.JmhWorkloadInventory
import dev.s7a.strata.runtime.spi.RuntimeUiFrame
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown

/**
 * Complete public-host selection operations: nine two-mode fixtures and thirty independent controls.
 * JMH and the shared performance testkit own clocks, CPU, allocation, sampling and provenance.
 */
@OptIn(InternalStrataRuntimeApi::class)
public open class CycleSelectionBenchmark {
    /**
     * Alternates actual inside/outside hover through the completed production host frame.
     */
    @Benchmark
    public fun unchangedHoverAndCompletedFrame(state: Main): RuntimeUiFrame = state.fixture.hover()

    /**
     * Publishes one preconstructed valid selection, including validation and the completed frame.
     */
    @Benchmark
    public fun selectionWriteAndCompletedFrame(state: Main): RuntimeUiFrame = state.fixture.write()

    /**
     * Executes one frozen independent transition sequence, including specified recovery frames and terminal close.
     */
    @Benchmark
    public fun control(state: Controls): RuntimeUiFrame = state.fixture.control()

    /**
     * Nine primed retained main fixtures, owned by the JMH worker for one trial.
     */
    @State(Scope.Thread)
    public open class Main {
        /**
         * Immutable option count, never changed inside a measured operation.
         */
        @JvmField
        @Param("8", "128", "1024")
        public var choices: Int = 8

        /**
         * Canonical selected position, with the adjacent wraparound option prepared before collection.
         */
        @JvmField
        @Param
        public var position: CycleSelectionPosition = CycleSelectionPosition.First

        internal lateinit var fixture: CycleSelectionFixture

        /**
         * Prepares declaration, attachment, labels and current selection outside measurement.
         */
        @Setup(Level.Trial)
        public fun setup() {
            fixture = CycleSelectionFixture(choices, position.index(choices))
        }

        /**
         * Releases the current tree and every observer on the construction worker.
         */
        @TearDown(Level.Trial)
        public fun close() {
            fixture.close()
        }
    }

    /**
     * Fresh primed state for each complete independent transition; construction is outside the timed operation.
     */
    @State(Scope.Thread)
    public open class Controls {
        /**
         * One separately frozen complete operation row.
         */
        @JvmField
        @Param
        public var scenario: CycleSelectionControl = CycleSelectionControl.CleanRetainedFrame

        internal lateinit var fixture: CycleSelectionFixture

        /**
         * Prepares immutable producers and the initial completed host frame.
         */
        @Setup(Level.Invocation)
        public fun setup() {
            fixture = CycleSelectionFixture(control = scenario)
        }

        /**
         * Closes remaining resources outside timing; the terminal row also closes them inside its operation.
         */
        @TearDown(Level.Invocation)
        public fun close() {
            fixture.close()
        }
    }

    /**
     * Frozen matrix and deterministic admission for every main mode and every independent control.
     */
    public companion object {
        /**
         * Requires all 39 fixtures and 48 rows, with detached pixels, input, semantics, work and release checks.
         */
        @JvmStatic
        public fun verifyWork() {
            check(JmhWorkloadInventory.capture(listOf(CycleSelectionBenchmark::class.java), setOf("avgt")).size == 48)
            var rows = 0
            for (size in listOf(8, 128, 1_024)) {
                for (position in CycleSelectionPosition.entries) {
                    CycleSelectionFixture(size, position.index(size), verify = true).use { fixture ->
                        fixture.verifyHover()
                        rows += 1
                    }
                    CycleSelectionFixture(size, position.index(size), verify = true).use { fixture ->
                        fixture.verifyWrite()
                        rows += 1
                    }
                }
            }
            for (control in CycleSelectionControl.entries) {
                CycleSelectionFixture(control = control, verify = true).use { fixture -> fixture.verifyControl() }
                rows += 1
            }
            check(CycleSelectionControl.entries.size == 30 && rows == 48)
        }
    }
}
