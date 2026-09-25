package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.geometry.IntSize
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Verifies the typed resource-metadata boundary used before vanilla button scaling enters the common profile builder.
 */
internal class FabricMinecraftProfileMetadataTest {
    @Test
    fun acceptsThe26Point2NineSliceValues() {
        assertDoesNotThrow { validateMinecraftNineSliceScaling(nineSlice(3), IntSize(200, 20), 3, false) }
        assertDoesNotThrow { validateMinecraftNineSliceScaling(nineSlice(1), IntSize(200, 20), 1, false) }
    }

    @Test
    fun rejectsMissingOrMismatchedScalingContract() {
        val variants =
            listOf(
                nineSlice(3, width = 199),
                nineSlice(3, height = 19),
                nineSlice(1),
                nineSlice(3, borderTop = 2),
                nineSlice(3, stretchInner = true),
            )
        variants.forEach { scaling ->
            assertThrows(IllegalArgumentException::class.java) {
                validateMinecraftNineSliceScaling(scaling, IntSize(200, 20), 3, false)
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
                scrollbarNineSlice(width = 5),
                scrollbarNineSlice(height = 31),
                scrollbarNineSlice(borderTop = 2),
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
        borderTop: Int = border,
    ): FabricMinecraftGuiScaling =
        FabricMinecraftGuiScaling(
            width,
            height,
            border,
            borderTop,
            border,
            border,
            stretchInner,
        )

    private fun scrollbarNineSlice(
        width: Int = 6,
        height: Int = 32,
        stretchInner: Boolean = false,
        borderTop: Int = 1,
    ): FabricMinecraftGuiScaling =
        FabricMinecraftGuiScaling(
            width,
            height,
            1,
            borderTop,
            1,
            1,
            stretchInner,
        )
}
