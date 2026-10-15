package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.performance.JmhWorkloadInventory
import dev.s7a.strata.quality.fixture.EmptyChildControls
import dev.s7a.strata.quality.fixture.EmptyChildFixture
import dev.s7a.strata.quality.fixture.EmptyChildWorkload
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown

/**
 * Measures the frozen 60-case empty-child corpus through complete public update/frame operations.
 */
public open class EmptyChildBenchmark {
    /**
     * Complete operation, including the same fresh declarations or source publication on both archives.
     */
    @Benchmark
    public fun reconcile(state: Invocation): Any = state.fixture.execute()

    /**
     * One owner-confined tree/session is prepared and settled outside every timed invocation.
     */
    @State(Scope.Thread)
    public open class Invocation {
        /**
         * Typed compiled case selected by the shared generic fixture inventory.
         */
        @JvmField
        @Param("DirectChanged1", "DirectChanged128", "DirectChanged4096", "ObservedChanged1", "ObservedChanged128", "ObservedChanged4096", "DirectEqual1", "DirectEqual128", "DirectEqual4096", "ObservedEqual1", "ObservedEqual128", "ObservedEqual4096", "Same1", "Same128", "Same4096", "Clean1", "Clean128", "Clean4096", "DirectEmptyToOne1", "DirectEmptyToOne128", "ObservedEmptyToOne1", "ObservedEmptyToOne128", "DirectOneToEmpty1", "DirectOneToEmpty128", "ObservedOneToEmpty1", "ObservedOneToEmpty128", "DirectNonempty1", "DirectNonempty128", "ObservedNonempty1", "ObservedNonempty128")
        public var workload: EmptyChildWorkload = EmptyChildWorkload.DirectChanged1

        /**
         * Collector-enabled and collector-disabled cases share the exact fixture.
         */
        @JvmField
        @Param("false", "true")
        public var monitoring: Boolean = false

        /**
         * Invocation-owned fixture, created outside timing and released after timing.
         */
        public lateinit var fixture: EmptyChildFixture

        /**
         * Creates and settles the initial declaration outside sampling.
         */
        @Setup(Level.Invocation)
        public fun setup() {
            fixture = EmptyChildFixture(workload, monitoring)
        }

        /**
         * Completes terminal cleanup outside sampling.
         */
        @TearDown(Level.Invocation)
        public fun close() {
            fixture.close()
        }
    }

    /**
     * Common preflight runs against the actual loaded runtime before any fork samples.
     */
    public companion object {
        /**
         * Independently qualifies every fixed case and all acceptance controls.
         */
        @JvmStatic
        public fun verifyWork() {
            check(JmhWorkloadInventory.capture(listOf(EmptyChildBenchmark::class.java), setOf("avgt", "sample")).size == 120)
            EmptyChildControls.verify()
            EmptyChildWorkEvidence.verifyPixels()
            EmptyChildWorkEvidence.verifyTerminalReferences()
        }
    }
}
