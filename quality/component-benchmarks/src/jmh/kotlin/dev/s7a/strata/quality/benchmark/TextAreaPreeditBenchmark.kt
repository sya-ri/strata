package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.OperationsPerInvocation
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.infra.Blackhole

/**
 * Frozen 82-row corpus: complete normalization and real focused input plus completed frame for 41 cases.
 * Each invocation delivers 64 prepared events; editor resets and their completed frames are included.
 * Reflection invocation overhead remains in normalize scores; result extraction and diagnostics are untimed.
 */
@OptIn(InternalStrataRuntimeApi::class)
public open class TextAreaPreeditBenchmark {
    /**
     * Complete normalization, consuming each accepted wrapper or rejected result.
     */
    @Benchmark
    @OperationsPerInvocation(64)
    public fun normalize(
        state: Session,
        blackhole: Blackhole,
    ) {
        state.normalize(blackhole)
    }

    /**
     * Real input outcomes and all completed frames, including the fixed bounded reset sequence.
     */
    @Benchmark
    @OperationsPerInvocation(64)
    public fun inputFrame(
        state: Session,
        blackhole: Blackhole,
    ) {
        state.inputFrame(blackhole)
    }

    /**
     * Worker-owned immutable inputs and exactly one current editor, prepared identically for both archives.
     */
    @State(Scope.Thread)
    public open class Session {
        /**
         * Complete frozen first-41 matrix; no shortened selection can establish whole acceptance.
         */
        @JvmField
        @Param(
            "Ascii32",
            "Ascii4096",
            "Ascii16384",
            "Cjk32",
            "Cjk4096",
            "Cjk16384",
            "Supplementary32",
            "Supplementary4096",
            "Supplementary16384",
            "Lf32",
            "Lf4096",
            "Lf16384",
            "Caret32",
            "Caret4096",
            "Caret16384",
            "FocusedBlock32",
            "FocusedBlock4096",
            "FocusedBlock16384",
            "Replacement32",
            "Replacement4096",
            "Replacement16384",
            "Cr32",
            "Cr4096",
            "Crlf32",
            "Crlf4096",
            "MixedBreaks32",
            "MixedBreaks4096",
            "LateConversion32",
            "LateConversion4096",
            "EmptyClear",
            "ExactRemainingBudget",
            "OneUnitOverBudget",
            "RawTextOverTwiceBudget",
            "BlockTotalOverTwiceBudget",
            "BlockCountOverBudget",
            "SupplementarySplitCaret",
            "SplitSurrogateBlocks",
            "UnsupportedScalar",
            "BlockMismatch",
            "EmptyBoundaryBlocks",
            "CommittedCapacityAtZero",
        )
        public var case: PreeditCase = PreeditCase.Ascii32
        private lateinit var inputs: PreeditCase.Inputs
        private lateinit var runtime: PreeditRuntime
        private lateinit var editor: PreeditEditor

        /**
         * Freezes all events and validates independent expected budgets before timing starts.
         */
        @Setup(Level.Trial)
        public fun setup() {
            check(case.frameOnly.not())
            inputs = case.inputs()
            check(inputs.events.size == 64)
            inputs.events.forEach { PreeditReference.normalize(it, inputs.remaining) }
            runtime = PreeditRuntime()
            editor = PreeditEditor(inputs)
        }

        /**
         * Runs actual complete normalization without constructing input events inside timing.
         */
        public fun normalize(blackhole: Blackhole) {
            inputs.events.forEach { blackhole.consume(runtime.normalize(it, inputs.remaining)) }
        }

        /**
         * Resets only current presentation, then completes a frame after every real input.
         */
        public fun inputFrame(blackhole: Blackhole) {
            editor.resetComposition()
            inputs.events.forEach {
                blackhole.consume(editor.input(it))
                blackhole.consume(editor.frame())
            }
        }

        /**
         * Releases the sole host and requires all font owners to reach zero.
         */
        @TearDown(Level.Trial)
        public fun close() {
            editor.close()
        }
    }

    /**
     * Complete untimed admission discovered by the existing generic fixture collector.
     */
    public companion object {
        /**
         * Runs all independent controls and the full compiled 86-row matrix before collection.
         */
        @JvmStatic
        public fun verifyWork() {
            PreeditWorkEvidence.verify()
        }
    }
}
