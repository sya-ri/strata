package dev.s7a.strata.runtime.minecraft.fabric

import net.minecraft.client.resources.metadata.gui.GuiSpriteScaling
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Verifies the typed resource-metadata boundary used before vanilla button scaling enters the common profile builder.
 */
internal class FabricMinecraftProfileMetadataTest {
    @Test
    fun acceptsThe26Point2NineSliceValues() {
        assertDoesNotThrow { validateMinecraftNineSliceScaling(nineSlice(3), 3) }
        assertDoesNotThrow { validateMinecraftNineSliceScaling(nineSlice(1), 1) }
    }

    @Test
    fun rejectsMissingOrMismatchedScalingContract() {
        val variants =
            listOf(
                GuiSpriteScaling.Stretch(),
                nineSlice(3, width = 199),
                nineSlice(3, height = 19),
                nineSlice(1),
                nineSlice(3, borderValue = GuiSpriteScaling.NineSlice.Border(3, 2, 3, 3)),
                nineSlice(3, stretchInner = true),
            )
        variants.forEach { scaling ->
            assertThrows(IllegalArgumentException::class.java) {
                validateMinecraftNineSliceScaling(scaling, 3)
            }
        }
    }

    private fun nineSlice(
        border: Int,
        width: Int = 200,
        height: Int = 20,
        stretchInner: Boolean = false,
        borderValue: GuiSpriteScaling.NineSlice.Border = GuiSpriteScaling.NineSlice.Border(border, border, border, border),
    ): GuiSpriteScaling.NineSlice = GuiSpriteScaling.NineSlice(width, height, borderValue, stretchInner)
}
