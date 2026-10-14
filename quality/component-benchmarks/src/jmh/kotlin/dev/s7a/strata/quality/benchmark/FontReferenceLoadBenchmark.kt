package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.performance.JmhWorkloadInventory
import dev.s7a.strata.performance.JvmOperationReportAdapter
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontSnapshot
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State

/**
 * Complete public font snapshot loading; prepared source encoding is excluded, while parsing and copying remain sampled.
 * This eight-case load corpus supplies no glyph-rendering, native-upload or frame-rate measurement.
 */
public open class FontReferenceLoadBenchmark {
    /** Loads one new snapshot from the worker's already encoded immutable source. */
    @Benchmark
    public fun load(state: Source): MinecraftFontSnapshot = state.load()

    /** Owns one prepared source per worker; no result snapshot is cached across measured invocations. */
    @State(Scope.Thread)
    public open class Source {
        /** Complete finite graph shapes, including three unchanged-path controls. */
        @Param
        public lateinit var case: FontReferenceLoadCase
        private lateinit var fixture: FontReferenceLoadFixture

        /** Encodes inputs and checks a fresh public load outside JMH timing. */
        @Setup(Level.Trial)
        public fun setup() {
            fixture = FontReferenceLoadFixture(case)
            fixture.verify(load())
        }

        /** Executes the full public snapshot load without engines or native services. */
        public fun load(): MinecraftFontSnapshot = fixture.load()
    }

    /** Shared discovery hooks for the original complete matrix and supplemental operation reports. */
    public companion object {
        /** Verifies all eight generated rows and every complete load outside timing. */
        @JvmStatic
        public fun verifyWork() {
            check(JmhWorkloadInventory.capture(listOf(FontReferenceLoadBenchmark::class.java), setOf("avgt")).size == 8)
            FontReferenceLoadCase.entries.forEach { case ->
                val fixture = FontReferenceLoadFixture(case)
                val first = fixture.load()
                val second = fixture.load()
                check(first !== second)
                fixture.verify(first)
                fixture.verify(second)
            }
        }

        /** Returns the same eight public load operations to the shared owner-thread collector. */
        @JvmStatic
        public fun operationReport(): JvmOperationReportAdapter = FontReferenceLoadOperationReport
    }
}
