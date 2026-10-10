package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.performance.JmhFixtureSelection
import dev.s7a.strata.performance.JmhPerformanceRunner
import dev.s7a.strata.performance.JmhWorkloadInventory
import dev.s7a.strata.performance.JvmPerformanceInputs
import org.openjdk.jmh.annotations.Mode
import java.nio.file.Path

/**
 * Collects explicitly selected generated fixtures against actual supplied native-free Fabric and common runtime archives.
 * No fixture registry or timing engine is added; the shared selector, JMH and provenance collector own those contracts.
 */
public object FabricFramePerformanceEvidence {
    /**
     * Accepts fresh output, repetition and ordinary JMH options; classpath archives are supplied independently.
     * Optional fixture inputs are preserved with resolved control libraries, without replacing the runtime target archives.
     */
    @JvmStatic
    public fun main(args: Array<String>) {
        require(2 < args.size)
        val fixtures = JmhFixtureSelection.select(emptyList())
        JmhFixtureSelection.verifyWork(fixtures)
        val methods = JmhFixtureSelection.methods(fixtures)
        val includes = methods.map { "^${Regex.escape(it)}$" }
        val parameters = JmhFixtureSelection.parameters()
        val mode = Mode.deepValueOf(System.getProperty("strata.performance.mode", "avgt"))
        val expected = JmhWorkloadInventory.capture(fixtures, setOf(mode.shortLabel()), parameters, includes)
        JmhFixtureSelection.verifyIncludes(expected, includes)
        val inputs = JvmPerformanceInputs.read(Path.of(checkNotNull(System.getProperty("strata.performance.inputs"))))
        val extra = JmhFixtureSelection.inputs()
        require(inputs.keys.intersect(extra.keys).isEmpty()) { "Duplicate external fixture input labels" }
        JmhPerformanceRunner.run(
            (includes + args.drop(2) + parameters.flatMap { (name, values) -> listOf("-p", "$name=${values.sorted().joinToString(",")}") }).toTypedArray(),
            fixtures,
            mapOf(
                "api" to "dev.s7a.strata.render.DrawImage",
                "core" to "dev.s7a.strata.runtime.UiSession",
                "headless" to "dev.s7a.strata.runtime.headless.HeadlessImage",
                "minecraft" to "dev.s7a.strata.runtime.minecraft.MinecraftUiHost",
                "fonts" to "dev.s7a.strata.runtime.minecraft.font.lwjgl.LwjglMinecraftFontBackendFactory",
                "fabric" to "dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftFrameInputs",
            ),
            Path.of(args[0]),
            args[1].toInt(),
            expected,
            inputs + extra,
        )
    }
}
