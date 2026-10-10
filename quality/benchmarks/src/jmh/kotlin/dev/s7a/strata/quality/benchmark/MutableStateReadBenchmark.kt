package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.performance.JmhWorkloadInventory
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.StateObservation
import dev.s7a.strata.state.mutableStateOf
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown

/**
 * Independent state-read controls include the capture hook's cost outside any virtual-row construction.
 */
@OptIn(InternalStrataRuntimeApi::class)
public open class MutableStateReadBenchmark {
    /**
     * Reads caller-owned state without dependency evaluation or row capture.
     */
    @Benchmark
    public fun unobservedReads(scene: Scene): Int = scene.read()

    /**
     * Reads the same state while an existing evaluator records its dependencies.
     */
    @Benchmark
    public fun observedReads(scene: Scene): Int = scene.observedRead()

    /**
     * Owner-confined read loop and dependency observation, primed outside measured operations.
     */
    @State(Scope.Thread)
    public open class Scene {
        /**
         * Fixed read counts matching the row cardinality controls.
         */
        @JvmField
        @Param("8", "40", "128")
        public var reads: Int = 40

        private val state = mutableStateOf(7)
        private var invalidations = 0
        private val observation = StateObservation({}, {}, { invalidations += 1 }, {})

        /**
         * Checks both sum references and their existing dependency invalidation before timings.
         */
        @Setup(Level.Trial)
        public fun setUp() {
            check(read() == reads * 7 && observedRead() == reads * 7)
            state.value = 8
            check(invalidations == 1)
            check(read() == reads * 8 && observedRead() == reads * 8)
            state.value = 7
        }

        /**
         * Includes every public state getter and returns its consumed sum.
         */
        public fun read(): Int {
            var result = 0
            repeat(reads) { result += state.value }
            return result
        }

        /**
         * Includes dependency collection with the same number of actual state reads.
         */
        public fun observedRead(): Int = observation.evaluate(::read)

        /**
         * Releases dependency ownership and leaves the caller-owned state usable.
         */
        @TearDown(Level.Trial)
        public fun close() {
            observation.close()
            val before = invalidations
            state.value = 9
            check(invalidations == before)
        }
    }

    /**
     * Automatic control acceptance through generated benchmark metadata.
     */
    public companion object {
        /**
         * Requires all six controls alongside the complete virtual-window work hook.
         */
        @JvmStatic
        public fun verifyWork() {
            check(JmhWorkloadInventory.capture(listOf(MutableStateReadBenchmark::class.java), setOf("avgt")).size == 6)
            for (reads in listOf(8, 40, 128)) {
                val scene = Scene()
                scene.reads = reads
                try {
                    scene.setUp()
                } finally {
                    scene.close()
                }
            }
        }
    }
}
