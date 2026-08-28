package dev.s7a.strata.integration.minecraft.fabric

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.resources.ResourceLocation
import java.nio.file.Files
import java.nio.file.Path

/**
 * Draws six additional native-only color probes using the unchanged original bitmap provider.
 * These diagnostic rows are separate from every acceptance scene and never supply pixels or measurements to the candidate renderer.
 * The render-thread screen borrows native resources and writes its applied shader state once to the caller-owned output directory.
 */
internal class MinecraftNativeFontColorProbeScreen(
    private val output: Path,
) : Screen(Component.literal("Native font color diagnostics")) {
    private val tints = intArrayOf(22, 154, 162, 171, 144, 250)
    private val text = Component.literal("A日한🙂").withStyle(Style.EMPTY.withFont(ResourceLocation("strata_font_test", "bitmap")))
    private val edgeText = Component.literal("日").withStyle(Style.EMPTY.withFont(ResourceLocation("strata_font_test", "bitmap")))
    private var captured = false

    override fun render(
        graphics: GuiGraphics,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        drawScene(graphics)
        if (captured.not()) {
            graphics.flush()
            Files.writeString(output.resolve("native-color-probe-state.txt"), "tints=${tints.joinToString()}; left=8; firstTop=8; rowSpacing=12; shadow=false\n${MinecraftNativeFontShaderState.capture()}")
            Files.writeString(output.resolve("native-half-edge-state.txt"), "font=strata_font_test:bitmap; codePoint=U+65E5; x=8; y=96.5; tint=FFFFFFFF; shadow=false; topProbe=14,96; bottomProbe=15,104\n")
            MinecraftNativeFontFloatTarget.capture(output, ::drawScene)
            captured = true
        }
    }

    private fun drawScene(graphics: GuiGraphics) {
        graphics.fill(0, 0, width, height, MinecraftFontParityFixture.background)
        tints.forEachIndexed { index, tint ->
            graphics.drawString(font, text, 8, 8 + index * 12, 0xFF000000.toInt() or (tint * 0x010101), false)
        }
        graphics.pose().pushPose()
        try {
            graphics.pose().translate(0.0, 0.5, 0.0)
            graphics.drawString(font, edgeText, 8, 96, 0xFFFFFFFF.toInt(), false)
        } finally {
            graphics.pose().popPose()
        }
    }
}
