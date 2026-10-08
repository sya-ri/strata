package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.component.TextAreaState
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.StateObservation
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown

/**
 * Complete public constructor and setter admissions, preserving all validation and state guards in timing.
 * Each batch is 64 public assignments; report the batch and disclose any per-assignment division by 64.
 */
@OptIn(InternalStrataRuntimeApi::class)
public open class TextAreaStateBenchmark {
    /**
     * Constructs one cold caller-owned state, including scroll/state ownership and input normalization.
     */
    @Benchmark
    public fun construct(input: Inputs): TextAreaState = TextAreaState(input.values.raw[0], input.values.maximum)

    /**
     * Constructs an empty state and performs its first public value assignment.
     */
    @Benchmark
    public fun coldSetter(input: Inputs): TextAreaState = TextAreaState(maxLength = input.values.maximum).also { it.value = input.values.raw[0] }

    /**
     * Alternates prepared unequal raw values, including the complete observer or dependency invalidation path.
     */
    @Benchmark
    public fun setter64(input: Assignments): String {
        repeat(64) { input.state.value = input.next() }
        return input.state.value
    }

    /**
     * Resubmits the current canonical immutable reference through full public validation and equality guards.
     */
    @Benchmark
    public fun sameReference64(input: Assignments): String {
        repeat(64) { input.state.value = input.identical }
        return input.state.value
    }

    /**
     * Resubmits an independently prepared equal raw spelling, preserving conversion work on converted cases.
     */
    @Benchmark
    public fun equal64(input: Assignments): String {
        repeat(64) { input.state.value = input.equal }
        return input.state.value
    }

    /**
     * Frozen immutable inputs are prepared outside measured admissions.
     */
    @State(Scope.Thread)
    public open class Inputs {
        /**
         * Exact compiled input distribution.
         */
        @JvmField
        @Param
        public var workload: TextAreaInput = TextAreaInput.Empty

        /**
         * Independent expected/raw values retained only by the benchmark fixture.
         */
        public lateinit var values: TextAreaInput.Values

        /**
         * Prepares every string before the measured operation.
         */
        @Setup(Level.Trial)
        public open fun setup() {
            values = workload.prepare()
        }
    }

    /**
     * Current state and one bounded observation interval; no previous benchmark result is retained.
     */
    @State(Scope.Thread)
    public open class Assignments : Inputs() {
        /**
         * Subscriber cost remains an explicit part of each public setter case.
         */
        @JvmField
        @Param
        public var observation: TextAreaObservation = TextAreaObservation.Unobserved

        /**
         * Caller-owned state exercised through its public setter.
         */
        public lateinit var state: TextAreaState

        /**
         * Stable current canonical reference for identical resubmission.
         */
        public lateinit var identical: String

        /**
         * Independently copied raw value for equal normalized resubmission.
         */
        public lateinit var equal: String

        /**
         * Observable work count for untimed verification; durations never come from instrumentation.
         */
        public var notifications: Int = 0
            private set

        private var revision = 0
        private var release: AutoCloseable? = null

        @Setup(Level.Trial)
        override fun setup() {
            super.setup()
            state = TextAreaState(values.raw[0], values.maximum)
            identical = state.value
            equal = values.raw[0].toCharArray().concatToString()
            release =
                when (observation) {
                    TextAreaObservation.Unobserved -> null
                    TextAreaObservation.TextObserver -> state.observe { notifications += 1 }
                    TextAreaObservation.ContentDependency -> StateObservation({}, {}, { notifications += 1 }, {}).also { it.evaluate { state.value } }
                }
        }

        /**
         * Alternates only prepared immutable inputs.
         */
        public fun next(): String {
            revision = 1 - revision
            return values.raw[revision]
        }

        /**
         * Releases the sole observation interval after measurement.
         */
        @TearDown(Level.Trial)
        public fun close() {
            release?.close()
            release = null
        }
    }

    /**
     * Generic fixture discovery runs deterministic admission outside all measured intervals.
     */
    public companion object {
        /**
         * Checks every constructor, batch and equal control against independently prepared canonical values.
         */
        @JvmStatic
        public fun verifyWork() {
            TextAreaCorpus.verify()
            val benchmark = TextAreaStateBenchmark()
            for (workload in TextAreaInput.entries) {
                val inputs = Inputs().also {
                    it.workload = workload
                    it.setup()
                }
                check(benchmark.construct(inputs).value == inputs.values.canonical[0])
                check(benchmark.coldSetter(inputs).value == inputs.values.canonical[0])
                for (observation in TextAreaObservation.entries) {
                    val assignments = Assignments().also {
                        it.workload = workload
                        it.observation = observation
                        it.setup()
                    }
                    try {
                        check(benchmark.setter64(assignments) == inputs.values.canonical[0])
                        val expected = if (observation == TextAreaObservation.Unobserved || workload == TextAreaInput.Empty) 0 else 64
                        check(assignments.notifications == expected)
                        check(benchmark.sameReference64(assignments) == inputs.values.canonical[0])
                        check(benchmark.equal64(assignments) == inputs.values.canonical[0])
                        check(assignments.notifications == expected)
                    } finally {
                        assignments.close()
                    }
                }
            }
        }
    }
}
