package dev.s7a.strata.integration.minecraft.fabric

import com.mojang.blaze3d.platform.NativeImage
import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Image
import dev.s7a.strata.component.ImageSource
import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.Text
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.integration.minecraft.fabric.MinecraftParityPresentation.closeFabricScreen
import dev.s7a.strata.integration.minecraft.fabric.MinecraftParityRendering.renderHeadless
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
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
 * Compares actual Fabric tooltip presentation with detached Headless pixels for both inset policies.
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
        val viewport = IntSize(320, 180)
        val pointer = IntOffset(10, 10)
        for (padding in listOf(true, false)) {
            val name = if (padding) "strata-component-tooltip-padded" else "strata-component-tooltip-flush"
            val expected = context.computeOnClient { renderHeadless(profile, definition(padding), viewport, pointer) }
            val path = output.resolve("$name-headless.png")
            Files.write(path, expected.encodePng())
            context.setScreen { createMinecraftScreen(definition(padding), profile, parent = null) }
            context.waitForScreen(FabricMinecraftScreen::class.java)
            context.movePointerRaw(pointer.x.toDouble(), pointer.y.toDouble())
            context.waitTicks(2)
            NativeImage.read(path.inputStream()).use { image ->
                context.assertScreenshotEquals(image, "$name-fabric", output, viewport)
            }
            closeFabricScreen(context)
        }
    }

    private fun definition(padding: Boolean): UiDefinition =
        UiDefinition("Component tooltip parity") {
            Stack(Modifier.size(320, 180).background(ArgbColor(0xFF000000.toInt()))) {
                Stack(
                    modifier =
                        Modifier.size(40, 20).tooltip(delayMillis = 0, padding = padding) {
                            Column(spacing = 2) {
                                Text("Details")
                                Image(ImageSource.Pixels(createDrawImage(IntSize(2, 2), intArrayOf(-65536, -16711936, -16776961, -1))), size = IntSize(12, 12))
                            }
                        },
                ) { Text("Hover") }
            }
        }
}
