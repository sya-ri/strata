package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.performance.JmhPerformanceRunner
import dev.s7a.strata.performance.JmhWorkloadInventory
import dev.s7a.strata.performance.JvmPerformanceInputs
import org.openjdk.jmh.annotations.Mode
import java.nio.file.Path

/**
 * Registers the complete actual-Fabric clip corpus with the existing shared JMH receipt collector.
 */
public object FrameClipPerformanceEvidence {
    /**
     * Accepts a fresh output directory, repetition index and ordinary unchanged JMH CLI settings.
     * The frozen launcher supplies actual API/core/headless/Minecraft/Fabric archives on the normal classpath.
     * The prepared control measures frame-input resolution; native clean-frame identity bypass is measured separately.
     */
    @JvmStatic
    public fun main(args: Array<String>) {
        require(2 < args.size)
        val fixtures = listOf(FrameClipBenchmark::class.java)
        val mode = Mode.deepValueOf(System.getProperty("strata.performance.mode", "avgt"))
        val expected = JmhWorkloadInventory.capture(fixtures, setOf(mode.shortLabel()))
        check(expected.size == 126)
        JmhPerformanceRunner.run(
            args.drop(2).toTypedArray(),
            fixtures,
            mapOf(
                "api" to "dev.s7a.strata.render.DrawImage",
                "core" to "dev.s7a.strata.runtime.UiSession",
                "headless" to "dev.s7a.strata.runtime.headless.HeadlessImage",
                "minecraft" to "dev.s7a.strata.runtime.minecraft.MinecraftUiHost",
                "fonts" to "dev.s7a.strata.runtime.minecraft.font.lwjgl.LwjglMinecraftFontBackendFactory",
                "fabric" to "dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftFrameLayerKt",
            ),
            Path.of(args[0]),
            args[1].toInt(),
            expected,
            JvmPerformanceInputs.read(Path.of(checkNotNull(System.getProperty("strata.performance.inputs")))),
        )
    }
}
