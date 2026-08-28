package dev.s7a.strata.integration.minecraft.fabric

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * Supplies the identical native-only scene to the ordinary screen and the owned floating-point capture target.
 * Both methods borrow the active game font and extractor on the client thread and retain no render resources.
 */
internal object MinecraftNativeFontScene {
    /**
     * Extracts the fixture's opaque background through the standard GUI fill path.
     */
    fun background(graphics: GuiGraphicsExtractor) {
        graphics.fill(0, 0, graphics.guiWidth(), graphics.guiHeight(), MinecraftFontParityFixture.background)
    }

    /**
     * Extracts every unchanged resource-font row through the standard native Component text path.
     */
    fun text(graphics: GuiGraphicsExtractor) {
        MinecraftFontParityFixture.rows.forEach { row ->
            graphics.text(Minecraft.getInstance().font, MinecraftNativeFontOracle.component(row), MinecraftFontParityFixture.LEFT, row.top, row.color, row.shadow)
        }
    }
}
