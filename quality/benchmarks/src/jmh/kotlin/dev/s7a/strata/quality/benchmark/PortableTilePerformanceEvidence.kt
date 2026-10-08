package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.performance.JmhPerformanceRunner
import dev.s7a.strata.performance.JmhWorkloadInventory
import dev.s7a.strata.performance.JvmPerformanceInputs
import java.nio.file.Path

/**
 * Registers the independent portable-tile corpus with loaded-runtime fork provenance and shared JMH collection.
 */
public object PortableTilePerformanceEvidence {
    /**
     * Accepts a fresh output directory, repetition index and the ordinary JMH execution settings.
     * The caller supplies the actual Fabric archive independently of this unchanged fixture.
     */
    @JvmStatic
    public fun main(args: Array<String>) {
        require(2 < args.size)
        val fixtures = listOf(PortableTileBenchmark::class.java)
        val expected = JmhWorkloadInventory.capture(fixtures, setOf("avgt"))
        check(expected.size == 4)
        JmhPerformanceRunner.run(
            args.drop(2).toTypedArray(),
            fixtures,
            mapOf(
                "api" to "dev.s7a.strata.component.UiScope",
                "core" to "dev.s7a.strata.runtime.spi.RuntimeUiSession",
                "fabric" to "dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftPortableTilesKt",
            ),
            Path.of(args[0]),
            args[1].toInt(),
            expected,
            JvmPerformanceInputs.read(Path.of(checkNotNull(System.getProperty("strata.performance.inputs")))),
        )
    }
}
