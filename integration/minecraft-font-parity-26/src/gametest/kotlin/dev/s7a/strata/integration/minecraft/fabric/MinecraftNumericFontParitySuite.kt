package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.runtime.headless.HeadlessImage
import dev.s7a.strata.runtime.minecraft.MinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftScreen
import dev.s7a.strata.runtime.minecraft.fabric.createMinecraftScreen
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions
import net.minecraft.client.Minecraft
import org.apache.commons.lang3.function.FailableConsumer
import org.apache.commons.lang3.function.FailableFunction
import java.nio.file.Files
import java.nio.file.Path

/**
 * Runs current native numeric-provider and final-render evidence separately from the unchanged ordinary scene.
 * Client-thread-owned providers and bounded capture pipelines are released after the screen is removed on every exit.
 * Raw metrics, texels, signed widths, calibration pixels, and Fabric pixels remain exact requirements.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal object MinecraftNumericFontParitySuite {
    /**
     * Recreates numeric evidence using standard native providers and Font rendering without a global resource reload.
     */
    fun run(
        context: ClientGameTestContext,
        output: Path,
    ) {
        Files.createDirectories(output)
        Files.deleteIfExists(output.resolve("numeric-parity.properties"))
        val fonts =
            context.computeOnClient(
                FailableFunction<Minecraft, MinecraftNativeNumericFonts, RuntimeException> {
                    Files.writeString(output.resolve("native-resource-inputs.tsv"), MinecraftNativeFontOracle.verifyResources())
                    MinecraftNativeNumericFonts()
                },
            )
        MinecraftNumericFontCleanup.preserving({
            val profile = observations(context, output, fonts)
            val portable = captureNative(context, output, fonts, profile)
            compareFabric(context, output, profile, portable)
            MinecraftNumericFontEvidence.writeReceipt(output)
        }) {
            context.runOnClient(
                FailableConsumer<Minecraft, RuntimeException> { minecraft ->
                    MinecraftNumericFontCleanup.preserving({ MinecraftClientScreenAccess.setScreen(minecraft, null) }, fonts::close)
                },
            )
        }
    }

    private fun observations(
        context: ClientGameTestContext,
        output: Path,
        fonts: MinecraftNativeNumericFonts,
    ): MinecraftUiProfile =
        context.computeOnClient(
            FailableFunction<Minecraft, MinecraftUiProfile, RuntimeException> {
                Files.writeString(output.resolve("native-gpu-state.txt"), MinecraftNativeFontOracle.graphicsState())
                val compatibility = MinecraftFontParityFixture.target.compatibility
                val native = MinecraftNumericFontEvidence.capture(compatibility, fonts::glyph, fonts::width)
                MinecraftNumericFontEvidence.write(output.resolve("numeric-native.properties"), native)
                val portable = MinecraftNumericFontEvidence.portable(compatibility)
                MinecraftNumericFontEvidence.write(output.resolve("numeric-portable.properties"), portable)
                MinecraftNumericFontEvidence.verify(native, portable)
                MinecraftFontParityFixture.profile(MinecraftNumericFontFixture.snapshot(compatibility))
            },
        )

    private fun captureNative(
        context: ClientGameTestContext,
        output: Path,
        fonts: MinecraftNativeNumericFonts,
        profile: MinecraftUiProfile,
    ): Map<Int, HeadlessImage> {
        val capture = context.computeOnClient(FailableFunction<Minecraft, MinecraftNativeFontFloatTarget, RuntimeException> { MinecraftNativeFontFloatTarget() })
        val images = mutableMapOf<Int, HeadlessImage>()
        val failures = mutableListOf<String>()
        try {
            context.setScreen { MinecraftNativeNumericFontScreen(fonts) }
            context.waitForScreen(MinecraftNativeNumericFontScreen::class.java)
            for (scale in 1..3) {
                val size = physicalSize(scale)
                setScale(context, scale, size)
                val native = screenshot(context, output, "font-native-$scale", size)
                val comparison =
                    context.computeOnClient(
                        FailableFunction<Minecraft, MinecraftFontGpuImageComparison.Result, RuntimeException> {
                            capture.captureScene(output, scale, fonts::draw, fonts::atlasSize)
                            val portable = MinecraftNumericFontFixture.render(profile, scale)
                            images[scale] = portable
                            MinecraftFontGpuImageComparison.compare(native, portable, output.resolve("font-headless-$scale.png"), MinecraftNumericFontFixture.commands(profile), scale)
                        },
                    )
                if (0 < comparison.unverified) failures += "scale=$scale unverified=${comparison.unverified}; ${comparison.firstFailure}"
            }
        } finally {
            context.runOnClient(FailableConsumer<Minecraft, RuntimeException> { capture.close() })
        }
        check(failures.isEmpty()) { failures.joinToString("\n", prefix = "Unclassified native numeric pixels:\n") }
        return images
    }

    private fun compareFabric(
        context: ClientGameTestContext,
        output: Path,
        profile: MinecraftUiProfile,
        images: Map<Int, HeadlessImage>,
    ) {
        context.setScreen { createMinecraftScreen(MinecraftNumericFontFixture.definition(), profile, parent = null) }
        context.waitForScreen(FabricMinecraftScreen::class.java)
        val assertions = MinecraftFontPixelAssertions(output)
        for (scale in 1..3) {
            val size = physicalSize(scale)
            setScale(context, scale, size)
            assertions.compare(screenshot(context, output, "font-fabric-$scale", size), checkNotNull(images[scale]), output.resolve("font-headless-$scale.png"))
        }
        assertions.verify()
    }

    private fun physicalSize(scale: Int): IntSize = IntSize(MinecraftFontParityFixture.viewport.width * scale, MinecraftFontParityFixture.viewport.height * scale)

    private fun setScale(
        context: ClientGameTestContext,
        scale: Int,
        size: IntSize,
    ) {
        context.input.resizeWindow(size.width, size.height)
        context.runOnClient(
            FailableConsumer<Minecraft, RuntimeException> { minecraft ->
                minecraft.options.guiScale().set(scale)
                minecraft.options.forceUnicodeFont().set(false)
                minecraft.resizeGui()
                check(minecraft.window.guiScale == scale) { "Numeric native GUI density differs from the requested scene." }
            },
        )
        context.input.setCursorPos(0.0, 0.0)
        context.waitTicks(3)
    }

    private fun screenshot(
        context: ClientGameTestContext,
        output: Path,
        name: String,
        size: IntSize,
    ): Path =
        context.takeScreenshot(
            TestScreenshotOptions
                .of(name)
                .disableCounterPrefix()
                .withSize(size.width, size.height)
                .withDestinationDir(output),
        )
}
