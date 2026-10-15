package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.component.CycleButtonState
import dev.s7a.strata.performance.JmhWorkloadInventory
import dev.s7a.strata.performance.JvmOperationReportAdapter
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State

/**
 * Constructs complete option states from prepared callers; validation and observable creation remain timed.
 */
@State(Scope.Thread)
public open class CycleOptionConstructionBenchmark {
    private val enumFormat: (Choice) -> String = { value -> value.name }
    private val numberFormat: (Int) -> String = Int::toString

    /**
     * Constructs through the collection/conversion overload with an existing List.
     */
    @Benchmark
    public fun listFactory(state: Inputs): CycleButtonState<Int> = state.listFactory()

    /**
     * Preserves the unchanged public list constructor as a control.
     */
    @Benchmark
    public fun directListControl(state: Inputs): CycleButtonState<Int> = state.directListControl()

    /**
     * Retains complete ordered non-List construction as a type-check regression control.
     */
    @Benchmark
    public fun collectionControl(state: Inputs): CycleButtonState<Int> = state.collectionControl()

    /**
     * Constructs the actual public enum/conversion factory over four choices.
     */
    @Benchmark
    public fun enumFactory(): CycleButtonState<Choice> = CycleButtonState(Choice.Second, enumFormat)

    /**
     * Constructs the same enum options through the unchanged public list overload.
     */
    @Benchmark
    public fun enumControl(): CycleButtonState<Choice> = CycleButtonState(Choice.entries, Choice.Second)

    /**
     * Retains empty List rejection and returns the actual constructor failure.
     */
    @Benchmark
    public fun emptyListRejected(): IllegalArgumentException = rejected(emptyList(), 0)

    /**
     * Retains empty non-List rejection and returns the actual constructor failure.
     */
    @Benchmark
    public fun emptyCollectionRejected(): IllegalArgumentException = rejected(emptySet(), 0)

    /**
     * Retains duplicate option validation rather than removing its work.
     */
    @Benchmark
    public fun duplicateRejected(): IllegalArgumentException = rejected(duplicates, 1)

    /**
     * Retains membership validation of an outside initial selection.
     */
    @Benchmark
    public fun outsideInitialRejected(): IllegalArgumentException = rejected(validOptions, 3)

    private fun rejected(
        values: Collection<Int>,
        selected: Int,
    ): IllegalArgumentException =
        try {
            CycleButtonState(values, selected, numberFormat)
            error("The rejected input unexpectedly constructed a state.")
        } catch (failure: IllegalArgumentException) {
            failure
        }

    /**
     * Prepared caller collections and conversions belong exclusively to one worker.
     */
    @State(Scope.Thread)
    public open class Inputs {
        /**
         * Complete original option counts, retaining the singleton control.
         */
        @JvmField
        @Param("1", "2", "8", "32", "128")
        public var length: Int = 1

        private lateinit var values: List<Int>
        private lateinit var collection: Collection<Int>
        private val convert: (Int) -> String = Int::toString

        /**
         * Prepares immutable member references and verifies all complete constructor outcomes outside timing.
         */
        @Setup(Level.Trial)
        public fun setup() {
            values = (0 until length).toList()
            collection = LinkedHashSet(values)
            verify(listFactory())
            verify(directListControl())
            verify(collectionControl())
        }

        /**
         * Includes the complete collection/conversion construction from a List caller.
         */
        public fun listFactory(): CycleButtonState<Int> = CycleButtonState(values, 0, convert)

        /**
         * Includes the complete unchanged primary list construction.
         */
        public fun directListControl(): CycleButtonState<Int> = CycleButtonState(values, 0)

        /**
         * Includes the complete original non-List copy and state construction.
         */
        public fun collectionControl(): CycleButtonState<Int> = CycleButtonState(collection, 0, convert)

        private fun verify(state: CycleButtonState<Int>) {
            check(state.values == values && state.value == 0)
            check(state.format(0) == "0")
            check(state.next() == values[1 % values.size])
            check(state.previous() == 0)
        }
    }

    /**
     * Immutable enum inputs preserve the real companion factory's complete option membership.
     */
    public enum class Choice {
        First,
        Second,
        Third,
        Fourth,
    }

    /**
     * Full fixture admission and the existing owner-meter adapter binding.
     */
    public companion object {
        private val duplicates = listOf(1, 1)
        private val validOptions = listOf(1, 2)

        /**
         * Verifies every registered row, ordered state behavior and rejection reason without collecting timings.
         */
        @JvmStatic
        public fun verifyWork() {
            check(JmhWorkloadInventory.capture(listOf(CycleOptionConstructionBenchmark::class.java), setOf("avgt")).size == 21)
            for (length in listOf(1, 2, 8, 32, 128)) {
                val state = Inputs()
                state.length = length
                state.setup()
            }
            val benchmark = CycleOptionConstructionBenchmark()
            for (state in listOf(benchmark.enumFactory(), benchmark.enumControl())) {
                check(state.values == listOf(Choice.First, Choice.Second, Choice.Third, Choice.Fourth))
                check(state.value == Choice.Second && state.format(Choice.Second) == "Second")
                check(state.next() == Choice.Third && state.previous() == Choice.Second)
            }
            check(benchmark.emptyListRejected().message == "CycleButton values must not be empty.")
            check(benchmark.emptyCollectionRejected().message == "CycleButton values must not be empty.")
            check(benchmark.duplicateRejected().message == "CycleButton values must be unique.")
            check(benchmark.outsideInitialRejected().message == "CycleButton initial value must belong to its values.")
        }

        /**
         * Reports every original row through the shared owner-thread collector.
         */
        @JvmStatic
        public fun operationReport(): JvmOperationReportAdapter = CycleOptionConstructionOperationReport
    }
}
