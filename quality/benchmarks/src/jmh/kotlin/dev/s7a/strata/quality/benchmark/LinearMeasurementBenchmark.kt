package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.performance.JmhWorkloadInventory
import dev.s7a.strata.quality.benchmark.LinearMeasurementCase.Operation
import dev.s7a.strata.runtime.spi.RuntimeUiFrame
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown

/** Complete invalidated linear operations and unchanged controls; no private timing or diagnostic boundary. */
@OptIn(InternalStrataRuntimeApi::class)
public open class LinearMeasurementBenchmark {
    /** Returns one complete frame; Lifecycle includes source/session/declarations, attach, both frames, and close. */
    @Benchmark
    public fun operation(state: Scene): RuntimeUiFrame = state.perform()

    /** One JMH worker owner; every new timed case runs with monitoring disabled. */
    @State(Scope.Thread)
    public open class Scene {
        /** Exactly one finite admitted case, avoiding an invalid Cartesian product. */
        @JvmField
        @Param
        public var linearCase: LinearMeasurementCase = LinearMeasurementCase.L001

        private var fixture: LinearMeasurementFixture? = null

        /** Primes only persistent sessions; lifecycle construction remains part of every measured invocation. */
        @Setup(Level.Trial)
        public fun setup() {
            if (linearCase.operation != Operation.Lifecycle) {
                fixture = LinearMeasurementFixture(linearCase.topology, linearCase.operation).also { it.open() }
            }
        }

        /** Executes the frozen complete operation with the supplied compiled case. */
        public fun perform(): RuntimeUiFrame =
            if (linearCase.operation == Operation.Lifecycle) {
                LinearMeasurementFixture(linearCase.topology, linearCase.operation).use {
                    it.open()
                    it.perform()
                }
            } else {
                checkNotNull(fixture).perform()
            }

        /** Closes the persistent owner after collection; lifecycle owners have already closed in each invocation. */
        @TearDown(Level.Trial)
        public fun close() {
            val previous = fixture
            fixture = null
            previous?.close()
        }
    }

    /** Original finite inventory and every actual new-cell work gate, discovered by the shared selector. */
    public companion object {
        /** Validates all 128 cases/256 mode rows, including the unchanged reactive controls, before collection. */
        @JvmStatic
        public fun verifyWork() {
            LinearMeasurementCorpus.verifyInventory()
            check(JmhWorkloadInventory.capture(listOf(LinearMeasurementBenchmark::class.java), setOf("avgt")).size == 110)
            for (cell in LinearMeasurementCase.entries) {
                LinearMeasurementFixture(cell.topology, cell.operation).use { it.verify() }
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
