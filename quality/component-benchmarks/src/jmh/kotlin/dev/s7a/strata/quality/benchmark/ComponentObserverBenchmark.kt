package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.performance.JmhWorkloadInventory
import dev.s7a.strata.performance.JvmOperationReportAdapter
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown

/**
 * Complete component publication operations, with cold, churn, no-op and completed-frame controls.
 * The existing JMH configuration and shared testkit own all clocks, counters and provenance.
 */
public open class ComponentObserverBenchmark {
    /**
     * Performs the entire selected operation, including registration and release where specified.
     */
    @Benchmark
    public fun operation(state: Main): Long = state.fixture.execute()

    /**
     * Frozen independent operation boundaries; counts are complete writes or completed frames per sample.
     */
    public enum class Scenario {
        Changed64,
        Equal64,
        FirstWrite,
        Churn64,
        ChangedFrame,
        CleanFrame,
    }

    /**
     * One owner-confined fixture per trial, primed outside sampling and released on the same worker.
     */
    @State(Scope.Thread)
    public open class Main {
        /**
         * Current callback count, or actual retained Checkbox count for frame operations.
         */
        @JvmField
        @Param("0", "1", "16", "128")
        public var observers: Int = 0

        /**
         * Complete operation selected without shortening the standard collection profile.
         */
        @JvmField
        @Param
        public var scenario: Scenario = Scenario.Changed64

        internal lateinit var fixture: ComponentObserverFixture

        /**
         * Prepares callbacks, optional host and its first completed frame outside measurement.
         */
        @Setup(Level.Trial)
        public fun setup() {
            fixture = ComponentObserverFixture(observers, scenario)
        }

        /**
         * Releases surviving observations and the optional host outside measurement.
         */
        @TearDown(Level.Trial)
        public fun close() {
            fixture.close()
        }
    }

    /**
     * Whole-matrix admission and optional owner CPU/allocation reporting through existing collectors.
     */
    public companion object {
        /**
         * Returns all 24 original operation rows without a separate timing implementation.
         */
        @JvmStatic
        public fun operationReport(): JvmOperationReportAdapter = ComponentObserverOperationReport

        /**
         * Checks exact callback work, current values, completed-frame semantics/pixels and terminal release.
         */
        @JvmStatic
        public fun verifyWork() {
            check(JmhWorkloadInventory.capture(listOf(ComponentObserverBenchmark::class.java), setOf("avgt")).size == 24)
            for (scenario in Scenario.entries) {
                for (count in listOf(0, 1, 16, 128)) {
                    ComponentObserverFixture(count, scenario).use { fixture ->
                        repeat(3) {
                            val before = fixture.notifications
                            val checked = fixture.checked
                            fixture.execute()
                            fixture.verifyOperation(before, checked)
                            fixture.verifyFrame()
                        }
                        fixture.close()
                        fixture.verifyRelease()
                    }
                }
            }
        }
    }
}
