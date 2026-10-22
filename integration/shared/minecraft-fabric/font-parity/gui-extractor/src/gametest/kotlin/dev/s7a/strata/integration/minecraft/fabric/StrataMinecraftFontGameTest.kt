package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.runtime.headless.HeadlessImage
import dev.s7a.strata.runtime.minecraft.MinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftScreen
import dev.s7a.strata.runtime.minecraft.fabric.createMinecraftScreen
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import java.nio.file.Files
import java.nio.file.Path

/**
 * Version-matched Minecraft entrypoint running independent native font parity before the existing suite.
 * All game and portable host operations run on the client thread; only detached evidence reaches the test thread.
 * Provider, dimension, texel, unexplained native pixel, or Fabric/headless pixel differences fail without a success receipt.
 */
internal class StrataMinecraftFontGameTest {
    /**
     * Runs native font verification before the complete shared loaded-client suite.
     */
    @OptIn(InternalStrataRuntimeApi::class)
    fun runTest(context: MinecraftLoadedTestContext) {
        context.restoreDefaultGameOptions()
        val output = Path.of(checkNotNull(System.getProperty("strata.minecraftParityOutput"))).resolve("font-parity")
        Files.createDirectories(output)
        Files.deleteIfExists(output.resolve("font-parity.txt"))
        reloadFonts(context, output)
        val (profile, nativeLayout) =
            context.computeOnClient {
                Files.writeString(output.resolve("native-gpu-state.txt"), MinecraftNativeFontOracle.graphicsState())
                Files.writeString(output.resolve("native-resource-inputs.tsv"), MinecraftNativeFontOracle.verifyResources())
                val snapshot = MinecraftFontParityFixture.snapshot()
                MinecraftFontParityChecks.verifyGlyphs(snapshot, output, MinecraftNativeFontOracle::glyph)
                val profile = MinecraftFontParityFixture.profile(snapshot)
                profile to MinecraftFontParityChecks.verifyLayout(profile, MinecraftNativeFontOracle::size)
            }
        val assertions = MinecraftFontPixelAssertions(output)
        val portableImages = captureNative(context, output, profile, assertions)
        context.setScreen { createMinecraftScreen(MinecraftFontParityFixture.definition(), profile, parent = null) }
        context.waitForScreen(FabricMinecraftScreen::class.java)
        for (scale in 1..3) {
            val size = IntSize(MinecraftFontParityFixture.viewport.width * scale, MinecraftFontParityFixture.viewport.height * scale)
            setScale(context, scale, size)
            assertions.compare(
                screenshot(context, output, "font-fabric-$scale", size),
                checkNotNull(portableImages[scale]),
                output.resolve("font-headless-$scale.png"),
            )
        }
        assertions.verify()
        MinecraftFontParityChecks.writeReceipt(output, nativeLayout)
        context.runOnClient { minecraft -> MinecraftClientScreenAccess.setScreen(minecraft, null) }
        MinecraftNumericFontParitySuite.run(context, output.resolve("numeric"))
        MinecraftTextReadabilitySuite.run(context, output.resolve("readability"))
        StrataMinecraftClientGameTest().runTest(context)
    }

    @OptIn(InternalStrataRuntimeApi::class)
    private fun captureNative(
        context: MinecraftLoadedTestContext,
        output: Path,
        profile: MinecraftUiProfile,
        assertions: MinecraftFontPixelAssertions,
    ): Map<Int, HeadlessImage> {
        val images = mutableMapOf<Int, HeadlessImage>()
        val capture = context.computeOnClient { MinecraftNativeFontFloatTarget() }
        try {
            context.setScreen { MinecraftNativeFontScreen() }
            context.waitForScreen(MinecraftNativeFontScreen::class.java)
            for (scale in 1..3) {
                val size = IntSize(MinecraftFontParityFixture.viewport.width * scale, MinecraftFontParityFixture.viewport.height * scale)
                setScale(context, scale, size)
                val nativePath = screenshot(context, output, "font-native-$scale", size)
                context.runOnClient { capture.captureScene(output, scale) }
                val portable =
                    context.computeOnClient { MinecraftFontParityFixture.render(profile, scale) }
                images[scale] = portable
                val portablePath = output.resolve("font-headless-$scale.png")
                assertions.compareNative(nativePath, portable, portablePath, profile, scale)
                captureWithoutDither(context, output, scale, size)
            }
        } finally {
            context.runOnClient { capture.close() }
        }
        return images
    }

    private fun captureWithoutDither(
        context: MinecraftLoadedTestContext,
        output: Path,
        scale: Int,
        size: IntSize,
    ) {
        val previousDither =
            context.computeOnClient { MinecraftNativeFontOracle.disableDither() }
        try {
            context.waitTicks(3)
            screenshot(context, output, "font-native-no-dither-$scale", size)
        } finally {
            context.runOnClient { MinecraftNativeFontOracle.restoreDither(previousDither) }
        }
    }

    private fun reloadFonts(
        context: MinecraftLoadedTestContext,
        output: Path,
    ) {
        val reload = context.computeOnClient { MinecraftNativeFontReload.start() }
        context.waitFor { reload.isDone }
        reload.join()
        // Startup resource-recovery notifications are not part of either font scene; their causes remain in the game log.
        context.runOnClient { minecraft -> minecraft.gui.toastManager().clear() }
        Files.writeString(output.resolve("native-reload.properties"), "preparationThreads=1\nimplementation=standard-font-manager\nresources=unchanged\n")
    }

    private fun setScale(
        context: MinecraftLoadedTestContext,
        scale: Int,
        size: IntSize,
    ) {
        resizeMinecraftTestWindow(context, IntSize(size.width, size.height))
        context.runOnClient { minecraft ->
            minecraft.options.guiScale().set(scale)
            minecraft.options.forceUnicodeFont().set(false)
            minecraft.resizeGui()
            check(minecraft.window.guiScale == scale) { "Minecraft did not retain the requested font parity GUI scale." }
        }
        context.movePointerRaw(0.0, 0.0)
        context.waitTicks(3)
    }

    private fun screenshot(
        context: MinecraftLoadedTestContext,
        output: Path,
        name: String,
        size: IntSize,
    ): Path =
        context.takeScreenshot(
            name,
            output,
            IntSize(size.width, size.height),
        )
}
