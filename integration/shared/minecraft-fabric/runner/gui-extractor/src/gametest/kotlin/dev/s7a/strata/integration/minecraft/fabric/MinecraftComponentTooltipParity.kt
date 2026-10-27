package dev.s7a.strata.integration.minecraft.fabric

import com.mojang.blaze3d.platform.NativeImage
import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Image
import dev.s7a.strata.component.ImageSource
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.Text
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.integration.minecraft.fabric.MinecraftParityPresentation.closeFabricScreen
import dev.s7a.strata.integration.minecraft.fabric.MinecraftParityRendering.renderHeadless
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.padding
import dev.s7a.strata.modifier.size
import dev.s7a.strata.modifier.tooltip
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.minecraft.MinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftScreen
import dev.s7a.strata.runtime.minecraft.fabric.createMinecraftScreen
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.ui.UiDefinition
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.inputStream

/**
 * Compares actual Fabric tooltip presentation with detached Headless pixels with and without decoration.
 */
internal object MinecraftComponentTooltipParity {
    /**
     * Borrows the ordinary 320 by 180 scale-one fixture viewport and writes fresh full-frame evidence.
     */
    @OptIn(InternalStrataRuntimeApi::class)
    fun run(
        context: MinecraftLoadedTestContext,
        profile: MinecraftUiProfile,
        output: Path,
    ) {
        assertNativeSurfaceCoverage(context, profile, output)
        val viewport = IntSize(320, 180)
        val pointer = IntOffset(10, 10)
        for (decorated in listOf(true, false)) {
            val name = if (decorated) "strata-component-tooltip-decorated" else "strata-component-tooltip-bare"
            val expected = context.computeOnClient { renderHeadless(profile, definition(decorated), viewport, pointer) }
            val path = output.resolve("$name-headless.png")
            Files.write(path, expected.encodePng())
            context.setScreen { createMinecraftScreen(definition(decorated), profile, parent = null) }
            context.waitForScreen(FabricMinecraftScreen::class.java)
            context.movePointerRaw(pointer.x.toDouble(), pointer.y.toDouble())
            context.waitTicks(2)
            NativeImage.read(path.inputStream()).use { image ->
                context.assertScreenshotEquals(image, "$name-fabric", output, viewport)
            }
            closeFabricScreen(context)
        }
    }

    /**
     * Checks the full-viewport footprint against the game's atlas-backed renderer.
     * Native atlas and portable textures can differ by one gradient-channel level at a sampling boundary.
     * The separate Fabric/Headless comparisons retain exact ARGB assertions.
     */
    @OptIn(InternalStrataRuntimeApi::class)
    private fun assertNativeSurfaceCoverage(
        context: MinecraftLoadedTestContext,
        profile: MinecraftUiProfile,
        output: Path,
    ) {
        val viewport = IntSize(320, 180)
        for ((width, height) in listOf(IntSize(8, 9), IntSize(40, 18))) {
            val definition =
                UiDefinition("Tooltip surface parity") {
                    Stack(Modifier.size(320, 180).background(ArgbColor(0xFF000000.toInt())).padding(left = 40, top = 40)) {
                        Spacer(Modifier.size(4, 4).tooltip(delayMillis = 0) { Spacer(Modifier.size(width, height)) })
                    }
                }
            val name = "strata-tooltip-surface-${width}x$height"
            val expected = context.computeOnClient { renderHeadless(profile, definition, viewport, IntOffset(41, 41)) }
            val path = output.resolve("$name-headless.png")
            Files.write(path, expected.encodePng())
            context.setScreen { MinecraftTooltipSurfaceReferenceScreen(IntRect(43, 51, 43 + width, 51 + height)) }
            try {
                context.waitForScreen(MinecraftTooltipSurfaceReferenceScreen::class.java)
                context.waitTicks(2)
                val nativePath = context.takeScreenshot("$name-native", output, viewport)
                NativeImage.read(path.inputStream()).use { image ->
                    NativeImage.read(nativePath.inputStream()).use { native ->
                        assertSurfaceCoverage(image, native, nativePath)
                    }
                }
            } finally {
                context.setScreen { null }
                context.waitFor { minecraft -> (MinecraftClientScreenAccess.currentScreen(minecraft) is MinecraftTooltipSurfaceReferenceScreen).not() }
            }
        }
    }

    private fun assertSurfaceCoverage(
        expected: NativeImage,
        actual: NativeImage,
        path: Path,
    ) {
        check(expected.width == actual.width && expected.height == actual.height) { "Native tooltip surface dimensions differ: $path" }
        for (y in 0 until expected.height) {
            for (x in 0 until expected.width) {
                val expectedCovered = expected.getPixel(x, y) != 0xFF000000.toInt()
                val actualCovered = actual.getPixel(x, y) != 0xFF000000.toInt()
                check(expectedCovered == actualCovered) { "Native tooltip surface coverage differs at ($x, $y): $path" }
            }
        }
    }

    private fun definition(decorated: Boolean): UiDefinition =
        UiDefinition("Component tooltip parity") {
            Stack(Modifier.size(320, 180).background(ArgbColor(0xFF000000.toInt()))) {
                Stack(
                    modifier =
                        Modifier.size(40, 20).tooltip(delayMillis = 0, decorated = decorated) {
                            Column(spacing = 2) {
                                Text("Details")
                                Image(ImageSource.Pixels(createDrawImage(IntSize(2, 2), intArrayOf(-65536, -16711936, -16776961, -1))), size = IntSize(12, 12))
                            }
                        },
                ) { Text("Hover") }
            }
        }
}
