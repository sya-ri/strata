package dev.s7a.strata.integration.minecraft.fabric

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import java.nio.file.Files
import java.nio.file.Path

/**
 * Native legacy Font rendering oracle for the shared original-font scene.
 * It borrows the game's font manager and draws no portable command or captured candidate pixels.
 * The screen writes one detached shader-state diagnostic per physical density to its caller-owned build directory.
 */
internal class MinecraftNativeFontScreen(
    private val output: Path,
) : Screen(Component.literal("Independent native font oracle")) {
    private val capturedDensities = mutableSetOf<Double>()

    override fun render(
        graphics: GuiGraphics,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        drawScene(graphics)
        val window = checkNotNull(minecraft).window
        val density = window.guiScale
        val matchesFixture =
            density.toInt() in 1..3 && window.width == MinecraftFontParityFixture.viewport.width * density.toInt() &&
                window.height == MinecraftFontParityFixture.viewport.height * density.toInt()
        if (matchesFixture && capturedDensities.add(density)) {
            graphics.flush()
            Files.writeString(output.resolve("native-shader-state-${density.toInt()}.txt"), MinecraftNativeFontShaderState.capture())
            MinecraftNativeFontFloatTarget.captureScene(output, density.toInt(), ::drawScene)
        }
    }

    private fun drawScene(graphics: GuiGraphics) {
        graphics.fill(0, 0, width, height, MinecraftFontParityFixture.background)
        MinecraftFontParityFixture.rows.forEach { row ->
            val text = MinecraftNativeFontOracle.component(row)
            graphics.drawString(font, text, MinecraftFontParityFixture.LEFT, row.top, row.color, row.shadow)
        }
    }
}
