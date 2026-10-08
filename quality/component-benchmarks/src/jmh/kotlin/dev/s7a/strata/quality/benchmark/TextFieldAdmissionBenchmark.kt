package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.component.TextFieldState
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State

/**
 * Unchanged single-line public-state controls with the same UTF-16 sizes and 64-assignment denominator.
 */
public open class TextFieldAdmissionBenchmark {
    /**
     * Constructs one complete single-line state.
     */
    @Benchmark
    public fun construct(input: Inputs): TextFieldState = TextFieldState(input.texts[0], maxOf(1, input.length))

    /**
     * Assigns the first prepared value to a newly constructed empty single-line state.
     */
    @Benchmark
    public fun coldSetter(input: Inputs): TextFieldState = TextFieldState(maxLength = maxOf(1, input.length)).also { it.value = input.texts[0] }

    /**
     * Alternates 64 immutable values through the unchanged TextField setter.
     */
    @Benchmark
    public fun setter64(input: Inputs): String {
        repeat(64) { input.state.value = input.texts[it % 2] }
        return input.state.value
    }

    /**
     * Resubmits a prepared distinct equal single-line value 64 times.
     */
    @Benchmark
    public fun equal64(input: Inputs): String {
        repeat(64) { input.state.value = input.equal }
        return input.state.value
    }

    /**
     * Immutable control strings and one current caller-owned state.
     */
    @State(Scope.Thread)
    public open class Inputs {
        /**
         * Frozen UTF-16 size; zero is an explicit equal-write control.
         */
        @JvmField
        @Param("0", "8", "2048", "16384")
        public var length: Int = 0

        /**
         * Prepared alternating single-line values.
         */
        public lateinit var texts: List<String>

        /**
         * Distinct equal reference prepared outside timing.
         */
        public lateinit var equal: String

        /**
         * Public single-line state control.
         */
        public lateinit var state: TextFieldState

        /**
         * Builds the complete control corpus before measurement.
         */
        @Setup(Level.Trial)
        public fun setup() {
            texts = listOf("A".repeat(length), "B".repeat(length))
            equal = texts[0].toCharArray().concatToString()
            state = TextFieldState(texts[0], maxOf(1, length))
        }
    }

    /**
     * Deterministic control verification outside timing.
     */
    public companion object {
        /**
         * Requires every control to remain valid through constructor, cold and equal/changed batches.
         */
        @JvmStatic
        public fun verifyWork() {
            val benchmark = TextFieldAdmissionBenchmark()
            for (length in listOf(0, 8, 2_048, 16_384)) {
                val input = Inputs().also {
                    it.length = length
                    it.setup()
                }
                check(benchmark.construct(input).value == input.texts[0])
                check(benchmark.coldSetter(input).value == input.texts[0])
                check(benchmark.setter64(input) == input.texts[1])
                benchmark.equal64(input)
                check(input.state.value == input.texts[0])
            }
        }
    }
}
