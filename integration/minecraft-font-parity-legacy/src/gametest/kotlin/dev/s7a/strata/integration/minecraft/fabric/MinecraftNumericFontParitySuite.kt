package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.runtime.headless.HeadlessImage
import dev.s7a.strata.runtime.minecraft.MinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftScreen
import dev.s7a.strata.runtime.minecraft.fabric.createMinecraftScreen
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import java.nio.file.Files
import java.nio.file.Path

/**
 * Runs isolated numeric-provider evidence after the unchanged ordinary font suite.
 * The test thread orchestrates captures while all providers, hosts, and GPU resources remain client-thread confined.
 * Raw metrics, texels, signed widths, and Fabric pixels are exact; only independently proven native GPU differences are admitted.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal object MinecraftNumericFontParitySuite {
    /**
     * Creates fresh numeric evidence and closes every owned native resource after removing its screen, including on failure.
     */
    fun run(
        context: MinecraftLoadedTestContext,
        output: Path,
    ) {
        Files.createDirectories(output)
        Files.deleteIfExists(output.resolve("numeric-parity.properties"))
        val fonts =
            context.computeOnClient {
                Files.writeString(output.resolve("native-resource-inputs.tsv"), MinecraftNativeFontOracle.verifyResources())
                MinecraftNativeNumericFonts()
            }
        MinecraftNumericFontCleanup.preserving({
            val profile = observations(context, output, fonts)
            val portable = captureNative(context, output, fonts, profile)
            compareFabric(context, output, profile, portable)
            MinecraftNumericFontEvidence.writeReceipt(output)
        }) {
            context.computeOnClient { minecraft ->
                MinecraftNumericFontCleanup.preserving({ minecraft.setScreen(null) }, fonts::close)
            }
        }
    }

    private fun observations(
        context: MinecraftLoadedTestContext,
        output: Path,
        fonts: MinecraftNativeNumericFonts,
    ): MinecraftUiProfile =
        context.computeOnClient {
            Files.writeString(output.resolve("native-gpu-state.txt"), MinecraftNativeFontOracle.graphicsState())
            val compatibility = MinecraftFontParityFixture.target.compatibility
            val native = MinecraftNumericFontEvidence.capture(compatibility, fonts::glyph, fonts::width)
            MinecraftNumericFontEvidence.write(output.resolve("numeric-native.properties"), native)
            val portable = MinecraftNumericFontEvidence.portable(compatibility)
            MinecraftNumericFontEvidence.write(output.resolve("numeric-portable.properties"), portable)
            MinecraftNumericFontEvidence.verify(native, portable)
            MinecraftFontParityFixture.profile(MinecraftNumericFontFixture.snapshot(compatibility))
        }

    private fun captureNative(
        context: MinecraftLoadedTestContext,
        output: Path,
        fonts: MinecraftNativeNumericFonts,
        profile: MinecraftUiProfile,
    ): Map<Int, HeadlessImage> {
        val commands = context.computeOnClient { MinecraftNumericFontFixture.commands(profile) }
        val images = mutableMapOf<Int, HeadlessImage>()
        val failures = mutableListOf<String>()
        context.computeOnClient { minecraft -> minecraft.setScreen(MinecraftNativeNumericFontScreen(fonts, output)) }
        for (scale in 1..3) {
            val size = physicalSize(scale)
            setScale(context, scale, size)
            val native = context.takeScreenshot("font-native-$scale", output, size)
            val portable = context.computeOnClient { MinecraftNumericFontFixture.render(profile, scale) }
            images[scale] = portable
            val comparison = MinecraftFontGpuImageComparison.compare(native, portable, output.resolve("font-headless-$scale.png"), commands, scale)
            if (0 < comparison.unverified) failures += "scale=$scale unverified=${comparison.unverified}; ${comparison.firstFailure}"
        }
        check(failures.isEmpty()) { failures.joinToString("\n", prefix = "Unclassified native numeric pixels:\n") }
        return images
    }

    private fun compareFabric(
        context: MinecraftLoadedTestContext,
        output: Path,
        profile: MinecraftUiProfile,
        images: Map<Int, HeadlessImage>,
    ) {
        context.computeOnClient { minecraft -> minecraft.setScreen(createMinecraftScreen(MinecraftNumericFontFixture.definition(), profile, parent = null)) }
        context.waitFor { minecraft -> minecraft.screen is FabricMinecraftScreen }
        val assertions = MinecraftFontPixelAssertions(output)
        for (scale in 1..3) {
            val size = physicalSize(scale)
            setScale(context, scale, size)
            assertions.compare(context.takeScreenshot("font-fabric-$scale", output, size), checkNotNull(images[scale]), output.resolve("font-headless-$scale.png"))
        }
        assertions.verify()
    }

    private fun physicalSize(scale: Int): IntSize = IntSize(MinecraftFontParityFixture.viewport.width * scale, MinecraftFontParityFixture.viewport.height * scale)

    private fun setScale(
        context: MinecraftLoadedTestContext,
        scale: Int,
        size: IntSize,
    ) {
        context.computeOnClient { minecraft ->
            minecraft.window.setWindowed(size.width, size.height)
            minecraft.options.guiScale().set(scale)
            minecraft.options.forceUnicodeFont().set(false)
            minecraft.resizeDisplay()
        }
        context.waitFor { minecraft -> minecraft.window.width == size.width && minecraft.window.height == size.height && minecraft.window.guiScale == scale.toDouble() }
        context.movePointer(IntOffset.Zero)
        context.waitTicks(3)
    }
}
