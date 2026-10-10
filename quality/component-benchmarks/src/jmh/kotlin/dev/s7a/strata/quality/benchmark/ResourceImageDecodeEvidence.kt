package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.performance.JmhFixtureSelection
import dev.s7a.strata.performance.JmhPerformanceRunner
import dev.s7a.strata.performance.JmhWorkloadInventory
import dev.s7a.strata.performance.JvmPerformanceInputs
import org.openjdk.jmh.annotations.Mode
import java.nio.file.Path

/**
 * Registers the complete actual native-decoder corpus with the shared standard JMH evidence adapter.
 */
public object ResourceImageDecodeEvidence {
    /**
     * Accepts a fresh output directory, independent repetition index, and unchanged standard JMH CLI options.
     */
    @JvmStatic
    public fun main(args: Array<String>) {
        require(2 < args.size)
        val fixtures = listOf(ResourceImageDecodeBenchmark::class.java)
        val includes = JmhFixtureSelection.includes(fixtures)
        val mode = Mode.deepValueOf(System.getProperty("strata.performance.mode", "avgt"))
        val expected = JmhWorkloadInventory.capture(fixtures, setOf(mode.shortLabel()))
        check(expected.size == 72)
        JmhFixtureSelection.verifyIncludes(expected, includes)
        JmhFixtureSelection.verifyWork(fixtures)
        JmhPerformanceRunner.run(
            (includes + args.drop(2)).toTypedArray(),
            fixtures,
            mapOf(
                "api" to "dev.s7a.strata.render.DrawImage",
                "core" to "dev.s7a.strata.runtime.UiSession",
                "headless" to "dev.s7a.strata.runtime.headless.HeadlessImage",
                "minecraft" to "dev.s7a.strata.runtime.minecraft.MinecraftUiHost",
                "fonts" to "dev.s7a.strata.runtime.minecraft.font.lwjgl.LwjglMinecraftFontBackendFactory",
                "fabric" to "dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftAssets",
            ),
            Path.of(args[0]),
            args[1].toInt(),
            expected,
            JvmPerformanceInputs.read(Path.of(checkNotNull(System.getProperty("strata.performance.inputs")))),
        )
    }
}
