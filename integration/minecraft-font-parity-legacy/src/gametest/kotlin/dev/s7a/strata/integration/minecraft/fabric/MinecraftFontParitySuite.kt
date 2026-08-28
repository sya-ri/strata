package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.runtime.headless.HeadlessImage
import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftScreen
import dev.s7a.strata.runtime.minecraft.fabric.createMinecraftScreen
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import java.nio.file.Files
import java.nio.file.Path

/**
 * Runs independent legacy provider and final native Text comparisons before the existing loaded-client suite.
 * Client work is marshalled through the borrowed test context; detached PNGs and the success receipt belong to the current build output.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal object MinecraftFontParitySuite {
    private const val SCALE_TIMEOUT_TICKS = 1_200

    /**
     * Fails on metric, texel, density, or unexplained native pixel differences, and on any Fabric/headless pixel difference.
     */
    fun run(context: MinecraftLoadedTestContext) {
        val output = Path.of(checkNotNull(System.getProperty("strata.minecraftLegacyOutput"))).resolve("font-parity")
        Files.createDirectories(output)
        Files.deleteIfExists(output.resolve("font-parity.txt"))
        reloadFonts(context, output)
        val (profile, nativeLayout) =
            context.computeOnClient {
                Files.writeString(
                    output.resolve("native-run-inputs.properties"),
                    MinecraftFontParityFixture.evidenceMetadata(MinecraftFontParityFixture.target.compatibility).entries.joinToString("\n", postfix = "\n") { (key, value) -> "$key=$value" },
                )
                Files.writeString(output.resolve("native-gpu-state.txt"), MinecraftNativeFontOracle.graphicsState())
                Files.writeString(output.resolve("native-resource-inputs.tsv"), MinecraftNativeFontOracle.verifyResources())
                Files.writeString(output.resolve("native-face-before.tsv"), MinecraftNativeFontAccess.faceState())
                val snapshot = MinecraftFontParityFixture.snapshot()
                MinecraftFontParityChecks.verifyGlyphs(snapshot, output, MinecraftNativeFontOracle::glyph)
                Files.writeString(output.resolve("native-face-after-isolated.tsv"), MinecraftNativeFontAccess.faceState())
                val profile = MinecraftFontParityFixture.profile(snapshot)
                val layout = MinecraftFontParityChecks.verifyLayout(profile, MinecraftNativeFontOracle::size)
                Files.writeString(output.resolve("native-vertices.tsv"), MinecraftNativeFontVertices.capture())
                MinecraftNativeFontAtlases.capture(output)
                Files.writeString(output.resolve("native-face-after-bake.tsv"), MinecraftNativeFontAccess.faceState())
                profile to layout
            }
        val assertions = MinecraftFontPixelAssertions(output)
        val portableImages = mutableMapOf<Int, HeadlessImage>()
        context.computeOnClient { minecraft -> minecraft.setScreen(MinecraftNativeFontScreen(output)) }
        for (scale in 1..3) {
            val size = IntSize(MinecraftFontParityFixture.viewport.width * scale, MinecraftFontParityFixture.viewport.height * scale)
            setScale(context, scale, size)
            val nativePath = context.takeScreenshot("font-native-$scale", output, size)
            val portable = context.computeOnClient { MinecraftFontParityFixture.render(profile, scale) }
            portableImages[scale] = portable
            val portablePath = output.resolve("font-headless-$scale.png")
            assertions.compareNative(nativePath, portable, portablePath, profile, scale)
            val previousDither = context.computeOnClient { MinecraftNativeFontOracle.setDither(false) }
            try {
                context.waitTicks(3)
                context.takeScreenshot("font-native-no-dither-$scale", output, size)
            } finally {
                context.computeOnClient { MinecraftNativeFontOracle.setDither(previousDither) }
            }
        }
        context.computeOnClient { minecraft -> minecraft.setScreen(createMinecraftScreen(MinecraftFontParityFixture.definition(), profile, parent = null)) }
        context.waitFor { minecraft -> minecraft.screen is FabricMinecraftScreen }
        for (scale in 1..3) {
            val size = IntSize(MinecraftFontParityFixture.viewport.width * scale, MinecraftFontParityFixture.viewport.height * scale)
            setScale(context, scale, size)
            assertions.compare(
                context.takeScreenshot("font-fabric-$scale", output, size),
                checkNotNull(portableImages[scale]),
                output.resolve("font-headless-$scale.png"),
            )
        }
        captureColorProbes(context, output)
        assertions.verify()
        MinecraftFontParityChecks.writeReceipt(output, nativeLayout)
        context.computeOnClient { minecraft -> minecraft.setScreen(null) }
        MinecraftNumericFontParitySuite.run(context, output.resolve("numeric"))
    }

    private fun reloadFonts(
        context: MinecraftLoadedTestContext,
        output: Path,
    ) {
        val reload = context.computeOnClient { MinecraftNativeFontReload.start() }
        context.waitFor { reload.isDone }
        reload.join()
        // Startup resource-recovery notifications are not part of either font scene; their causes remain in the game log.
        context.computeOnClient { minecraft -> minecraft.toasts.clear() }
        Files.writeString(output.resolve("native-reload.properties"), "preparationThreads=1\nimplementation=standard-font-manager\nresources=unchanged\n")
    }

    private fun captureColorProbes(
        context: MinecraftLoadedTestContext,
        output: Path,
    ) {
        context.computeOnClient { minecraft -> minecraft.setScreen(MinecraftNativeFontColorProbeScreen(output)) }
        setScale(context, 1, MinecraftFontParityFixture.viewport)
        context.takeScreenshot("font-native-color-probes", output, MinecraftFontParityFixture.viewport)
    }

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
        context.waitFor(SCALE_TIMEOUT_TICKS) { minecraft ->
            minecraft.window.width == size.width && minecraft.window.height == size.height &&
                minecraft.window.guiScale == scale.toDouble()
        }
        context.movePointer(IntOffset.Zero)
        context.waitTicks(3)
    }
}
