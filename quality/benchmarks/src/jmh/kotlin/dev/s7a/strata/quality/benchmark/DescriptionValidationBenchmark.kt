package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.performance.JmhPerformanceRunner
import dev.s7a.strata.performance.JmhWorkloadInventory
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
 * Public frame consumers for declaration validation, collected by the existing ordinary shared JMH selector.
 */
@OptIn(InternalStrataRuntimeApi::class)
public open class DescriptionValidationBenchmark {
    /**
     * Completes one genuine assignment/publication and retained frame with monitoring disabled.
     */
    @Benchmark
    public fun validationFrame(state: Scene): RuntimeUiFrame = state.nextFrame()

    /**
     * One settled session per JMH worker; declaration topology is fixed before timing.
     */
    @State(Scope.Thread)
    public open class Scene {
        /**
         * Complete 76-case parameter inventory inferred from the compiled enum by JMH.
         */
        @JvmField
        @Param
        public var case: DescriptionValidationCase = DescriptionValidationCase.RootDefinitionWide1None

        private lateinit var fixture: DescriptionValidationFixture

        /**
         * Attaches and settles the public session outside sampling.
         */
        @Setup(Level.Trial)
        public fun setup() {
            fixture = DescriptionValidationFixture(case)
        }

        /**
         * Runs the complete frame operation without diagnostic instrumentation.
         */
        public fun nextFrame(): RuntimeUiFrame = fixture.nextFrame()

        /**
         * Releases every session/source owner outside sampling.
         */
        @TearDown(Level.Trial)
        public fun close() {
            fixture.close()
        }
    }

    /**
     * Generated matrix admission and independent untimed work controls use the existing kit.
     */
    public companion object {
        /**
         * Rejects missing, extra and duplicate method/parameter rows for both complete fixture families.
         */
        @JvmStatic
        public fun verifyWork() {
            val modes = setOf("avgt", "sample")
            val expected =
                buildSet {
                    modes.forEach { mode ->
                        DescriptionValidationCase.entries.forEach { case ->
                            add(JmhPerformanceRunner.workloadIdentity(DescriptionValidationBenchmark::class.java.name + ".validationFrame", mode, mapOf("case" to case.name)))
                        }
                        ReactiveWorkload.entries.forEach { scenario ->
                            listOf(false, true).forEach { monitoring ->
                                add(JmhPerformanceRunner.workloadIdentity(ReactiveRenderingBenchmark::class.java.name + ".reactiveFrame", mode, mapOf("scenario" to scenario.name, "monitoring" to monitoring.toString())))
                            }
                        }
                    }
                }
            check(expected.size == 188)
            check(JmhWorkloadInventory.capture(listOf(DescriptionValidationBenchmark::class.java, ReactiveRenderingBenchmark::class.java), modes) == expected)
            DescriptionValidationCase.entries.forEach { case ->
                DescriptionValidationFixture(case).use { it.verifyWork() }
            }
            ReactiveWorkEvidence.main(emptyArray())
        }
    }
}
