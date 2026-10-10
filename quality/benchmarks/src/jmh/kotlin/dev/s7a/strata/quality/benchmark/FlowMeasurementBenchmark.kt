package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.quality.benchmark.FlowMeasurementCase.Operation
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
 * Complete FlowRow invalidations and unchanged controls through the ordinary generated selector and collector.
 */
@OptIn(InternalStrataRuntimeApi::class)
public open class FlowMeasurementBenchmark {
    /**
     * Returns a full painted frame; Lifecycle includes declarations, attach, both frames and terminal close.
     */
    @Benchmark
    public fun operation(state: Scene): RuntimeUiFrame = state.perform()

    /**
     * One real session per worker, with monitoring disabled in every new timed cell.
     */
    @State(Scope.Thread)
    public open class Scene {
        /**
         * One exact finite complete-operation cell without speculative parameter combinations.
         */
        @JvmField
        @Param
        public var flowCase: FlowMeasurementCase = FlowMeasurementCase.F001

        private var fixture: FlowMeasurementFixture? = null

        /**
         * Primes persistent owners outside timing; lifecycle creates its complete owner inside each operation.
         */
        @Setup(Level.Trial)
        public fun setup() {
            if (flowCase.operation != Operation.Lifecycle) {
                fixture = FlowMeasurementFixture(flowCase.topology, flowCase.operation).also { it.open() }
            }
        }

        /**
         * Executes the unchanged complete boundary on the selected compiled cell.
         */
        public fun perform(): RuntimeUiFrame =
            if (flowCase.operation == Operation.Lifecycle) {
                FlowMeasurementFixture(flowCase.topology, flowCase.operation).use {
                    it.open()
                    it.perform()
                }
            } else {
                checkNotNull(fixture).perform()
            }

        /**
         * Releases a persistent worker owner; one-shot lifecycles have already closed before return.
         */
        @TearDown(Level.Trial)
        public fun close() {
            val previous = fixture
            fixture = null
            previous?.close()
        }
    }

    /**
     * Untimed admission discovered by the existing generated fixture selection.
     */
    public companion object {
        /**
         * Checks all 88 cases/176 mode rows, actual new work/ordered output, and unchanged reactive ownership.
         */
        @JvmStatic
        public fun verifyWork() {
            FlowMeasurementCorpus.verifyInventory()
            for (cell in FlowMeasurementCase.entries) {
                FlowMeasurementFixture(cell.topology, cell.operation).use { it.verify() }
            }
            for (scenario in ReactiveWorkload.entries) {
                for (monitoring in listOf(false, true)) {
                    val state = ReactiveRenderingBenchmark.ReactiveSession()
                    state.scenario = scenario
                    state.monitoring = monitoring
                    try {
                        state.setup()
                        repeat(64) { state.nextFrame() }
                        if (monitoring) state.workSnapshot()
                    } finally {
                        state.close()
                    }
                }
            }
        }
    }
}
