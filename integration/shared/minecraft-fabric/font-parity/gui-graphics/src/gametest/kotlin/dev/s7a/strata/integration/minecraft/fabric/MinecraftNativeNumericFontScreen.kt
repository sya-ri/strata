package dev.s7a.strata.integration.minecraft.fabric

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import java.nio.file.Path

/**
 * Draws an owned numeric-font session without changing the native renderer or the ordinary parity scene.
 * The caller retains and closes the borrowed session after the screen has been removed.
 * Each physical density captures matching native float evidence once, on the render thread.
 */
internal class MinecraftNativeNumericFontScreen(
    private val fonts: MinecraftNativeNumericFonts,
    private val output: Path,
) : Screen(Component.literal("Independent numeric font oracle")) {
    private val capturedDensities = mutableSetOf<Int>()

    override fun render(
        graphics: GuiGraphics,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        fonts.draw(graphics)
        val window = checkNotNull(minecraft).window
        val scale = window.guiScale.toInt()
        val viewport = MinecraftFontParityFixture.viewport
        val matchesViewport = scale in 1..3 && window.width == viewport.width * scale && window.height == viewport.height * scale
        if (matchesViewport.not() || capturedDensities.add(scale).not()) return
        graphics.flush()
        MinecraftNativeFontFloatTarget.captureScene(output, scale, fonts::draw, fonts::atlasSize)
    }
}
