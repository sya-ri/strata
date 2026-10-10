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
 * Four complete-frame controls using identical committed TextAreaState source in both variants.
 */
@OptIn(InternalStrataRuntimeApi::class)
public open class TextAreaPreeditFrameBenchmark {
    /**
     * 64 real frames or committed edit/reset pairs; no helper-only timing replaces these controls.
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
     * Owner-thread control host with one current state and fully prepared scalar events.
     */
    @State(Scope.Thread)
    public open class Session {
        /**
         * All four frame controls remain in the declared acceptance matrix.
         */
        @JvmField
        @Param("CleanFrameNoComposition", "CleanFrameActiveComposition", "CommittedCanonicalEdit", "CommittedNewlineEdit")
        public var case: PreeditCase = PreeditCase.CleanFrameNoComposition
        private lateinit var inputs: PreeditCase.Inputs
        private lateinit var editor: PreeditEditor

        /**
         * Creates one host and installs the declared current composition before timing.
         */
        @Setup(Level.Trial)
        public fun setup() {
            check(case.frameOnly)
            inputs = case.inputs()
            editor = PreeditEditor(inputs)
            inputs.seed?.let {
                editor.input(it)
                editor.frame()
            }
        }

        /**
         * Includes committed-state resets and their frames when the control edits the authoritative value.
         */
        public fun inputFrame(blackhole: Blackhole) {
            repeat(64) {
                inputs.edit?.let { edit ->
                    editor.state.value = inputs.committed
                    blackhole.consume(editor.frame())
                    blackhole.consume(editor.input(edit))
                }
                blackhole.consume(editor.frame())
            }
        }

        /**
         * Releases the current editor and both font-resource ownership levels.
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
         * Isolated frame-control selections also perform the complete deterministic admission.
         */
        @JvmStatic
        public fun verifyWork() {
            PreeditWorkEvidence.verify()
        }
    }
}
