package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.runtime.headless.HeadlessImage
import dev.s7a.strata.runtime.minecraft.MinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftScreen
import dev.s7a.strata.runtime.minecraft.fabric.createMinecraftScreen
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions
import net.minecraft.client.Minecraft
import org.apache.commons.lang3.function.FailableConsumer
import org.apache.commons.lang3.function.FailableFunction
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.function.Predicate

/**
 * Representative Minecraft 26.2 entrypoint running independent native font parity before the existing suite.
 * All game and portable host operations run on the client thread; only detached evidence reaches the test thread.
 * Provider, dimension, texel, unexplained native pixel, or Fabric/headless pixel differences fail without a success receipt.
 */
public class StrataMinecraftFontGameTest : FabricClientGameTest {
    @OptIn(InternalStrataRuntimeApi::class)
    override fun runTest(context: ClientGameTestContext) {
        context.restoreDefaultGameOptions()
        val output = Path.of(checkNotNull(System.getProperty("strata.minecraftParityOutput"))).resolve("font-parity")
        Files.createDirectories(output)
        Files.deleteIfExists(output.resolve("font-parity.txt"))
        reloadFonts(context, output)
        val (profile, nativeLayout) =
            context.computeOnClient(
                FailableFunction<Minecraft, Pair<MinecraftUiProfile, Map<String, String>>, RuntimeException> {
                    Files.writeString(output.resolve("native-gpu-state.txt"), MinecraftNativeFontOracle.graphicsState())
                    Files.writeString(output.resolve("native-resource-inputs.tsv"), MinecraftNativeFontOracle.verifyResources())
                    val snapshot = MinecraftFontParityFixture.snapshot()
                    MinecraftFontParityChecks.verifyGlyphs(snapshot, output, MinecraftNativeFontOracle::glyph)
                    val profile = MinecraftFontParityFixture.profile(snapshot)
                    profile to MinecraftFontParityChecks.verifyLayout(profile, MinecraftNativeFontOracle::size)
                },
            )
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
        context.runOnClient(FailableConsumer<Minecraft, RuntimeException> { minecraft -> MinecraftClientScreenAccess.setScreen(minecraft, null) })
        MinecraftNumericFontParitySuite.run(context, output.resolve("numeric"))
        MinecraftTextReadabilitySuite.run(context, output.resolve("readability"))
        StrataMinecraftClientGameTest().runTest(context)
    }

    @OptIn(InternalStrataRuntimeApi::class)
    private fun captureNative(
        context: ClientGameTestContext,
        output: Path,
        profile: MinecraftUiProfile,
        assertions: MinecraftFontPixelAssertions,
    ): Map<Int, HeadlessImage> {
        val images = mutableMapOf<Int, HeadlessImage>()
        val capture = context.computeOnClient(FailableFunction<Minecraft, MinecraftNativeFontFloatTarget, RuntimeException> { MinecraftNativeFontFloatTarget() })
        try {
            context.setScreen { MinecraftNativeFontScreen() }
            context.waitForScreen(MinecraftNativeFontScreen::class.java)
            for (scale in 1..3) {
                val size = IntSize(MinecraftFontParityFixture.viewport.width * scale, MinecraftFontParityFixture.viewport.height * scale)
                setScale(context, scale, size)
                val nativePath = screenshot(context, output, "font-native-$scale", size)
                context.runOnClient(FailableConsumer<Minecraft, RuntimeException> { capture.captureScene(output, scale) })
                val portable =
                    context.computeOnClient(
                        FailableFunction<Minecraft, HeadlessImage, RuntimeException> { MinecraftFontParityFixture.render(profile, scale) },
                    )
                images[scale] = portable
                val portablePath = output.resolve("font-headless-$scale.png")
                assertions.compareNative(nativePath, portable, portablePath, profile, scale)
                captureWithoutDither(context, output, scale, size)
            }
        } finally {
            context.runOnClient(FailableConsumer<Minecraft, RuntimeException> { capture.close() })
        }
        return images
    }

    private fun captureWithoutDither(
        context: ClientGameTestContext,
        output: Path,
        scale: Int,
        size: IntSize,
    ) {
        val previousDither = context.computeOnClient(FailableFunction<Minecraft, Boolean, RuntimeException> { MinecraftNativeFontOracle.setDither(false) })
        try {
            context.waitTicks(3)
            screenshot(context, output, "font-native-no-dither-$scale", size)
        } finally {
            context.runOnClient(FailableConsumer<Minecraft, RuntimeException> { MinecraftNativeFontOracle.setDither(previousDither) })
        }
    }

    private fun reloadFonts(
        context: ClientGameTestContext,
        output: Path,
    ) {
        val reload = context.computeOnClient(FailableFunction<Minecraft, CompletableFuture<Void>, RuntimeException> { MinecraftNativeFontReload.start() })
        context.waitFor(Predicate<Minecraft> { reload.isDone })
        reload.join()
        // Startup resource-recovery notifications are not part of either font scene; their causes remain in the game log.
        context.runOnClient(FailableConsumer<Minecraft, RuntimeException> { minecraft -> minecraft.gui.toastManager().clear() })
        Files.writeString(output.resolve("native-reload.properties"), "preparationThreads=1\nimplementation=standard-font-manager\nresources=unchanged\n")
    }

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
                check(minecraft.window.guiScale == scale) { "Minecraft did not retain the requested font parity GUI scale." }
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
