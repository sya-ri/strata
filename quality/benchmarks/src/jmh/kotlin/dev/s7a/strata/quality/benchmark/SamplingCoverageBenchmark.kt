package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.performance.JmhFixtureSelection
import dev.s7a.strata.performance.JmhPerformanceRunner
import dev.s7a.strata.performance.JmhWorkloadInventory
import dev.s7a.strata.performance.JvmPerformanceInputs
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import java.nio.file.Path

/**
 * Measures fresh exact metadata and the complete native-free frame-input preparation consumer separately.
 * Each operation invokes genuine archive members; source pixels, fixtures and reflection lookup are prepared outside timing.
 * Frame input preparation includes ordinary admission scans, ordered composition proofs, bounds/budgets, factor tables,
 * portable localization, all exact maps and complete sampled-source requests, before native borrowing or rendering.
 * It does not measure native texture-generation matching, CPU fallback rasterization, upload, GPU work or complete presentation.
 */
public open class SamplingCoverageBenchmark {
    /** Returns a newly allocated immutable three-row lookup from the actual runtime constructor. */
    @Benchmark
    public fun exactMapMetadata(state: Scene): Any = state.runtime.map(state.input.command, state.input.mapBounds, state.input.density)

    /** Returns complete fresh CPU frame inputs, including actual composition preparation on fallback paths. */
    @Benchmark
    public fun completeCpuFramePreparation(state: Scene): Any = state.runtime.prepare(state.input)

    /** Fixed geometry and the actual archive reflection boundary, owned by one JMH worker. */
    @State(Scope.Thread)
    public open class Scene {
        /** Complete typed 45-case inventory; no default subset is silently substituted. */
        @JvmField
        @Param
        public var geometry: SamplingCoverageFixture.Geometry = SamplingCoverageFixture.Geometry.OneByOneD1

        /** Actual immutable command input, prepared before sampling. */
        public lateinit var input: SamplingCoverageFixture.Input

        /** Genuine archive access resolved once per trial. */
        public lateinit var runtime: SamplingCoverageRuntime

        /** Resolves all target classes and validates this row's complete metadata before timing. */
        @Setup(Level.Trial)
        public fun setup() {
            input = SamplingCoverageFixture.geometry(geometry)
            runtime = SamplingCoverageRuntime()
            val map = runtime.map(input.command, input.mapBounds, input.density)
            SamplingCoverageOracle.metadata(runtime, map, input.command, input.mapBounds, input.density)
            SamplingCoverageWorkEvidence.admission(runtime, geometry, runtime.prepare(input))
        }
    }

    /** Source-free discovery and collection entry points reuse the existing shared inventory/collector. */
    public companion object {
        /** Runs automatically under generic fixture discovery without requiring a Fabric archive on that classpath. */
        @JvmStatic
        public fun verifyWork() {
            SamplingCoverageFrozenInputs.verify()
            check(SamplingCoverageFixture.Geometry.entries.size == 45)
            check(SamplingCoverageFixture.FrameOnly.entries.size == 8)
            check(JmhWorkloadInventory.capture(listOf(SamplingCoverageBenchmark::class.java, SamplingCoverageFrameBenchmark::class.java), setOf("avgt")).size == 98)
        }

        /**
         * Accepts a new output directory, independent repetition index and unchanged Standard JMH options.
         * The calling JVM supplies the actual Fabric archive on its normal classpath, plus identical external controls.
         * All native-independent controls execute before the collector creates receipts or starts any timing fork.
         */
        @JvmStatic
        public fun main(args: Array<String>) {
            require(2 < args.size)
            verifyWork()
            SamplingCoverageWorkEvidence.verify(SamplingCoverageRuntime())
            val fixtures = listOf(SamplingCoverageBenchmark::class.java, SamplingCoverageFrameBenchmark::class.java)
            val includes = JmhFixtureSelection.includes(fixtures)
            val expected = JmhWorkloadInventory.capture(fixtures, setOf("avgt"), includes = includes)
            check(expected.size == 98)
            JmhFixtureSelection.verifyIncludes(expected, includes)
            val inputs = JvmPerformanceInputs.read(Path.of(checkNotNull(System.getProperty("strata.performance.inputs"))))
            val fixtureInputs = JmhFixtureSelection.inputs()
            require(fixtureInputs.isNotEmpty() && inputs.keys.intersect(fixtureInputs.keys).isEmpty())
            SamplingCoverageFrozenInputs.verifyExternal(inputs + fixtureInputs)
            SamplingCoverageLoadedControls.verify(inputs + fixtureInputs)
            JmhPerformanceRunner.run(
                (includes + args.drop(2)).toTypedArray(),
                fixtures,
                mapOf(
                    "api" to "dev.s7a.strata.component.UiScope",
                    "core" to "dev.s7a.strata.runtime.spi.RuntimeUiSession",
                    "headless" to "dev.s7a.strata.runtime.headless.HeadlessImage",
                    "fabric" to "dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftSamplingMap",
                ),
                Path.of(args[0]),
                args[1].toInt(),
                expected,
                inputs + fixtureInputs,
            )
        }
    }
}
