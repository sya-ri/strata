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

    @Test
    fun acceptsThe26Point2ScrollbarNineSliceValues() {
        assertDoesNotThrow { validateMinecraftScrollbarScaling(scrollbarNineSlice()) }
    }

    @Test
    fun rejectsMismatchedScrollbarScalingContract() {
        val variants =
            listOf(
                GuiSpriteScaling.Stretch(),
                scrollbarNineSlice(width = 5),
                scrollbarNineSlice(height = 31),
                scrollbarNineSlice(borderValue = GuiSpriteScaling.NineSlice.Border(1, 2, 1, 1)),
                scrollbarNineSlice(stretchInner = true),
            )
        variants.forEach { scaling ->
            assertThrows(IllegalArgumentException::class.java) {
                validateMinecraftScrollbarScaling(scaling)
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

    private fun scrollbarNineSlice(
        width: Int = 6,
        height: Int = 32,
        stretchInner: Boolean = false,
        borderValue: GuiSpriteScaling.NineSlice.Border = GuiSpriteScaling.NineSlice.Border(1, 1, 1, 1),
    ): GuiSpriteScaling.NineSlice = GuiSpriteScaling.NineSlice(width, height, borderValue, stretchInner)
}
