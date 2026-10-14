@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.performance.JvmOperationReportAdapter
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State

/**
 * Eight additional current-input preparation controls with fixed reset/repeat transitions on both variants.
 * Clean borrowing is the isolated already-prepared CPU input boundary; loaded tests must separately prove that
 * the actual screen/presenter preflight selects it and that native pins, fences and ownership remain correct.
 */
public open class SamplingCoverageFrameBenchmark {
    /**
     * Returns genuine prepared inputs, or the primed immutable input in the explicit clean borrow control.
     */
    @Benchmark
    public fun completeCpuFramePreparation(state: Scene): Any = state.prepare()

    /**
     * Holds only two frozen display lists and one current immutable prepared input, never frame history.
     */
    @State(Scope.Thread)
    public open class Scene {
        /**
         * Complete eight-case frame-only inventory.
         */
        @JvmField
        @Param
        public var fixture: SamplingCoverageFixture.FrameOnly = SamplingCoverageFixture.FrameOnly.CleanSameCommandIdentity

        private lateinit var runtime: SamplingCoverageRuntime
        private lateinit var input: SamplingCoverageFixture.Input
        private lateinit var first: List<DrawCommand>
        private lateinit var second: List<DrawCommand>
        private lateinit var current: Any
        private var alternate = false

        /**
         * Primes identical bounded state before each independent trial; preparation is outside timing.
         */
        @Setup(Level.Trial)
        public fun setup() {
            runtime = SamplingCoverageRuntime()
            input = SamplingCoverageFixture.frame(fixture)
            first = input.commands
            second = if (fixture == SamplingCoverageFixture.FrameOnly.UnchangedMetadataEquivalentOutput) listOf(input.command.copy()) else ArrayList(first)
            current = runtime.prepare(input, first)
            alternate = false
            SamplingCoverageWorkEvidence.frameAdmission(runtime, fixture, current)
        }

        /**
         * Alternates exactly the same two frozen lists on both variants, replacing the sole current result.
         */
        public fun prepare(): Any {
            if (fixture == SamplingCoverageFixture.FrameOnly.CleanSameCommandIdentity) return current
            alternate = alternate.not()
            current = runtime.prepare(input, if (alternate) second else first)
            return current
        }
    }

    /**
     * Shares the existing operation-report connection without changing the eight-case JMH inventory.
     */
    public companion object {
        /**
         * Registers all original frame controls with the shared owner-thread meter.
         */
        @JvmStatic
        public fun operationReport(): JvmOperationReportAdapter = SamplingCoverageFrameOperationReport
    }
}
