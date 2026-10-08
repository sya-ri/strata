package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.minecraft.MinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasDevices
import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftScreen
import dev.s7a.strata.runtime.minecraft.fabric.createMinecraftScreen
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import java.nio.file.Files
import javax.imageio.ImageIO

/**
 * Verifies newly admitted native masks, touching and overlapping controls, and distant translucent rounding at every GUI density.
 * The actual complete native framebuffer is compared with original ordered commands and literal mask/rounding pixels.
 * Adapter capability is read from the loaded implementation; adapters without exact lookup retain their existing CPU path.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal object MinecraftSamplingCompositionGameTest {
    private val physical = IntSize(1920, 1080)

    /** Runs two immutable destination revisions per scene, with complete terminal release before the next screen. */
    internal fun run(
        context: MinecraftCanvasTestContext,
        profile: MinecraftUiProfile,
    ) {
        val cases = listOf(MinecraftSamplingCompositionCorpus.Case.OneMaskFewChanged, MinecraftSamplingCompositionCorpus.Case.DisjointManyChanged, MinecraftSamplingCompositionCorpus.Case.TouchingChanged, MinecraftSamplingCompositionCorpus.Case.OverlappingChanged, MinecraftSamplingCompositionCorpus.Case.LateBlockedChanged)
        for (scale in 1..4) {
            context.configureViewport(physical, scale)
            cases.forEach { verify(context, profile, scale, it) }
        }
    }

    @Suppress("TooGenericExceptionCaught") // Keep the original native/assertion failure while independently attempting terminal cleanup.
    private fun verify(
        context: MinecraftCanvasTestContext,
        profile: MinecraftUiProfile,
        scale: Int,
        case: MinecraftSamplingCompositionCorpus.Case,
    ) {
        context.waitFor { released() }
        val logical = IntSize(physical.width / scale, physical.height / scale)
        val scene = context.onClient { MinecraftSamplingCompositionScene(case, logical) }
        val screen = context.onClient { createMinecraftScreen(scene.definition(), profile, parent = null) }
        var failure: Throwable? = null
        try {
            context.onClient { context.setScreen(screen) }
            verifyFrame(context, screen, case, logical, scale, 0)
            val before = context.onClient { preparations(screen) }
            context.onClient { scene.update() }
            verifyFrame(context, screen, case, logical, scale, 1)
            check(context.onClient { preparations(screen) } == before + 1)
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            runCanvasTestCleanup(
                failure,
                { context.onClient { context.setScreen(null) } },
                { context.onClient { close(screen) } },
                { context.waitFor { released() } },
            )
        }
    }

    private fun verifyFrame(
        context: MinecraftCanvasTestContext,
        screen: FabricMinecraftScreen,
        case: MinecraftSamplingCompositionCorpus.Case,
        logical: IntSize,
        scale: Int,
        revision: Int,
    ) {
        repeat(2) { MinecraftCanvasFrameFence.awaitCompletedFrame(context, screen, MinecraftCanvasFrameFence.hostFrameCount(context, screen)) }
        val before = context.onClient { preparations(screen) }
        val commands = context.onClient { screen.captureCanvasFrame() }
        check(commands.count { it is DrawCommand.SampledImage } == case.masks)
        val admitted = context.onClient { admitted(screen, case) }
        val expected = context.onClient { rasterizeHeadless(commands, logical, scale).copyArgb() }
        val screenshot = context.takeScreenshot("sampling-composition-${case.name}-$scale-$revision", physical)
        val actual = checkNotNull(ImageIO.read(screenshot.toFile()))
        check(actual.width == physical.width && actual.height == physical.height)
        for (y in 0 until physical.height) {
            for (x in 0 until physical.width) check(actual.getRGB(x, y) == expected[y * physical.width + x]) { "Complete sampling-composition pixels differ at ($x, $y) in $screenshot" }
        }
        check(actual.getRGB((10 + revision * 2) * scale, 10 * scale) == 0xFF00FFFF.toInt())
        check(actual.getRGB((28 + revision * 2) * scale, 10 * scale) == 0xFF7195B3.toInt())
        check(actual.getRGB((10 + revision * 2) * scale, 22 * scale) == 0xFF7195B3.toInt())
        if (case == MinecraftSamplingCompositionCorpus.Case.LateBlockedChanged) check(actual.getRGB(401 * scale, 181 * scale) == 0xFF2E6DA0.toInt())
        check(context.onClient { preparations(screen) } == before)
        Files.writeString(context.outputDirectory.resolve("sampling-composition-${case.name}-$scale-$revision.properties"), "originalCommands=${commands.size}\nmaskOccurrences=${case.masks}\nadmittedMasks=$admitted\ntolerance=0\nresult=passed\n")
    }

    private fun admitted(
        screen: FabricMinecraftScreen,
        case: MinecraftSamplingCompositionCorpus.Case,
    ): Int {
        val holder = MinecraftCompositionParityInputs.presenter(screen)
        val inputs = MinecraftCompositionParityInputs.member(holder, "preparedInputs")
        val layers = MinecraftCompositionParityInputs.member(inputs, "layers") as List<*>
        val sampled = Class.forName("dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftFrameLayer\$Sampled")
        val admitted = layers.count(sampled::isInstance)
        val blocked = case == MinecraftSamplingCompositionCorpus.Case.OverlappingChanged || case == MinecraftSamplingCompositionCorpus.Case.LateBlockedChanged
        val expected = if (blocked || exactSampling().not()) 0 else case.masks
        check(admitted == expected)
        check(MinecraftCompositionParityInputs.member(inputs, "capacitySampledImages") == 0L)
        return admitted
    }

    private fun exactSampling(): Boolean {
        val holder =
            try {
                Class.forName("dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftSampledTextureLimitsKt")
            } catch (_: ClassNotFoundException) {
                Class.forName("dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftPortableTextureFactoryKt")
            }
        val method = holder.getDeclaredMethod("supportsFabricMinecraftExactSampling")
        check(method.trySetAccessible())
        return method.invoke(null) as Boolean
    }

    private fun preparations(screen: FabricMinecraftScreen): Long = MinecraftCompositionParityInputs.member(MinecraftCompositionParityInputs.presenter(screen), "framePreparationCount") as Long

    private fun close(screen: FabricMinecraftScreen) {
        screen.close()
        val holder = MinecraftCompositionParityInputs.presenter(screen)
        val field = holder.javaClass.getDeclaredField("preparedInputs")
        check(field.trySetAccessible())
        check(field.get(holder) == null) { "A closed screen retained sampling-composition inputs." }
    }

    private fun released(): Boolean = NativeCanvasDevices.retainedTargetCount() == 0 && NativeCanvasDevices.retainedGuiResourceSetCount() == 0 && NativeCanvasDevices.retainedManagedGuiResourceCount() == 0 && NativeCanvasDevices.retainedManagedGuiResourceBytes() == 0L
}
