@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.performance.JmhWorkloadInventory
import dev.s7a.strata.runtime.diagnostics.UiRenderMetric
import dev.s7a.strata.runtime.spi.RuntimeUiFrame
import dev.s7a.strata.runtime.spi.RuntimeUiSession
import dev.s7a.strata.runtime.spi.createRuntimeUiSession
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown

/**
 * Actual public transform and retained TiledImage paths: pre-input layout, complete dirty frames, and cold lifetimes.
 * The shared JMH kit owns all clocks, warm-up, forks, allocation and GC evidence.
 */
public open class TiledImageTopologyBenchmark {
    /**
     * Synchronizes dirty layout through real pointer dispatch without frame painting or semantics.
     * Includes public transform publication, geometry synchronization and ordinary pointer delivery.
     */
    @Benchmark
    public fun preInputLayout(state: Viewport): InputResult = state.preInputLayout()

    /**
     * Includes public transform publication and every normal dirty-frame phase.
     */
    @Benchmark
    public fun dirtyFrame(state: Viewport): RuntimeUiFrame = state.dirtyFrame()

    /**
     * Returns a complete clean retained frame without changing transform or source inputs.
     */
    @Benchmark
    public fun cleanFrame(state: Viewport): RuntimeUiFrame = state.cleanFrame()

    /**
     * Includes source creation, attach, first admission, navigation, independent tile/overlay revisions,
     * equivalent redeclaration, fit/viewport/source replacement, full frames and terminal release.
     */
    @Benchmark
    public fun completeProtocol(state: Viewport): RuntimeUiFrame = state.completeProtocol()

    /**
     * One real session per JMH worker with immutable input geometry and no prior-frame history.
     */
    @State(Scope.Thread)
    public open class Viewport {
        /**
         * Compiled source shape and unchanged policy selected by JMH.
         */
        @JvmField
        @Param
        public var case: TiledImageBenchmarkInput.Case = TiledImageBenchmarkInput.Case.Sparse

        /**
         * Real public transform operation selected independently of geometry.
         */
        @JvmField
        @Param
        public var change: TiledImageBenchmarkInput.Change = TiledImageBenchmarkInput.Change.SameRangePan

        /**
         * Current deterministic source counters, owned by this worker.
         */
        public lateinit var input: TiledImageBenchmarkInput
            private set

        /**
         * Actual attached session, also used by optional untimed diagnostics.
         */
        public lateinit var session: RuntimeUiSession
            private set

        private lateinit var constraints: Constraints

        /**
         * Constructs and primes the actual session outside steady-state measurements.
         */
        @Setup(Level.Trial)
        public fun setup() {
            input = TiledImageBenchmarkInput(case)
            session = createRuntimeUiSession(content = input::element)
            constraints = Constraints.fixed(input.size.width, input.size.height)
            session.attach()
            session.frame(constraints)
        }

        /**
         * Runs layout via the public pre-input geometry cutoff.
         */
        public fun preInputLayout(): InputResult {
            input.advance(change)
            return session.dispatchPointer(PointerEvent.Move(IntOffset.Zero))
        }

        /**
         * Produces a real dirty frame, including paint and semantics after layout.
         */
        public fun dirtyFrame(): RuntimeUiFrame {
            input.advance(change)
            return session.frame(constraints)
        }

        /**
         * Executes the public clean-frame control.
         */
        public fun cleanFrame(): RuntimeUiFrame = session.frame(constraints)

        /**
         * Keeps cold acquisition and cleanup inside the operation instead of invocation setup.
         */
        public fun completeProtocol(): RuntimeUiFrame {
            val fresh = TiledImageBenchmarkInput(case)
            val frame =
                createRuntimeUiSession(content = fresh::element).use { host ->
                    host.attach()
                    host.frame(constraints)
                    fresh.advance(change)
                    host.frame(constraints)
                    fresh.revise()
                    host.frame(constraints)
                    fresh.redeclare()
                    host.frame(constraints)
                    fresh.changeFit()
                    host.frame(constraints)
                    fresh.resize()
                    val resized = Constraints.fixed(fresh.size.width, fresh.size.height)
                    host.frame(resized)
                    fresh.replaceSource()
                    host.frame(resized)
                }
            fresh.verifyReleased()
            return frame
        }

        /**
         * Releases the actual owner and checks balanced observations with the fixture still reachable.
         */
        @TearDown(Level.Trial)
        public fun close() {
            session.close()
            input.verifyReleased()
        }
    }

    /**
     * Optional deterministic admission invoked by the shared fixture registry and ordinary CI checks.
     */
    public companion object {
        /**
         * Checks every generated row and the actual phase boundary without a timer or performance forecast.
         */
        @JvmStatic
        public fun verifyWork() {
            check(JmhWorkloadInventory.capture(listOf(TiledImageTopologyBenchmark::class.java), setOf("avgt")).size == 176)
            for (case in TiledImageBenchmarkInput.Case.entries) {
                for (change in TiledImageBenchmarkInput.Change.entries) {
                    val state = Viewport()
                    state.case = case
                    state.change = change
                    state.setup()
                    try {
                        verifyPhases(state)
                        verifyFrames(state)
                    } finally {
                        state.close()
                    }
                }
            }
        }

        private fun verifyPhases(state: Viewport) {
            state.session.startRenderMonitoring().use { monitor ->
                val before = TiledImageTopologyObservation.capture(state.session, state.input)
                state.preInputLayout()
                val after = TiledImageTopologyObservation.capture(state.session, state.input)
                TiledImageTopologyObservation.record(listOf(state.case.name, state.change.name, "preInputLayout").joinToString(":"), after, state.input, before.identity === after.identity)
                if (state.change == TiledImageBenchmarkInput.Change.SameRangePan || state.change == TiledImageBenchmarkInput.Change.SameRangeZoom) {
                    check(before.required == after.required && before.painted == after.painted)
                }
                val layout = monitor.snapshot()
                check(layout.overflowed.not())
                check(layout.counts.getValue(UiRenderMetric.Paint) == 0L)
                check(layout.counts.getValue(UiRenderMetric.Semantics) == 0L)
                check(layout.counts.getValue(UiRenderMetric.FrameAttempt) == 0L)
                println(listOf(state.case.name, state.change.name, layout.counts.getValue(UiRenderMetric.Layout), state.input.active, state.input.opened, state.input.closed).joinToString(","))
                if (state.case != TiledImageBenchmarkInput.Case.TinyKeyBudget && state.case != TiledImageBenchmarkInput.Case.ManyLevels && state.case != TiledImageBenchmarkInput.Case.LargeCoordinates) {
                    check(0L < layout.counts.getValue(UiRenderMetric.Layout))
                }
                monitor.checkpoint()
                state.dirtyFrame()
                check(monitor.snapshot().counts.getValue(UiRenderMetric.FrameSuccess) == 1L)
            }
        }

        private fun verifyFrames(state: Viewport) {
            val first = state.dirtyFrame()
            val commands = first.drawCommands.toList()
            val opened = state.input.opened
            var previous = TiledImageTopologyObservation.capture(state.session, state.input)
            repeat(8) { iteration ->
                state.dirtyFrame()
                val current = TiledImageTopologyObservation.capture(state.session, state.input)
                TiledImageTopologyObservation.record(listOf(state.case.name, state.change.name, "dirtyFrame", iteration).joinToString(":"), current, state.input, previous.identity === current.identity)
                if (state.change == TiledImageBenchmarkInput.Change.SameRangePan || state.change == TiledImageBenchmarkInput.Change.SameRangeZoom) {
                    check(previous.required == current.required && previous.painted == current.painted)
                }
                check(current.reservedBytes == state.input.reservedBytes)
                previous = current
            }
            check(first.drawCommands == commands)
            if (state.change == TiledImageBenchmarkInput.Change.SameRangePan || state.change == TiledImageBenchmarkInput.Change.SameRangeZoom) {
                check(opened == state.input.opened)
            }
            check(state.input.active <= state.input.policy.maxEntries)
            val clean = state.cleanFrame()
            check(clean === state.cleanFrame())
            state.completeProtocol()
        }
    }
}
