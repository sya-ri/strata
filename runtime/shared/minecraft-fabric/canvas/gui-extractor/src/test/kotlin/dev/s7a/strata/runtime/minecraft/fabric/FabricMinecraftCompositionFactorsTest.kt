package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.createDrawImage
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Checks every factor byte/component against independent Float32 arithmetic, ordered ownership and workspace bounds.
 */
internal class FabricMinecraftCompositionFactorsTest {
    @Test
    fun allByteColumnsAndComponentRowsRetainExactRawFloatBits() {
        val tints = listOf(0, -1, 0x01010203, 0x7FBFD7EF, 0x80AACC00.toInt(), 0xFE0102FF.toInt(), 0x0180FEFF)
        val workspace = FabricMinecraftCompositionFactors()
        for (key in listOf(emptyList(), tints, tints.reversed(), tints + tints.first())) {
            val image = workspace.get(key)
            for (row in 0 until image.size.height) {
                val tint = key.getOrNull(row) ?: 0
                for (component in 0 until 6) {
                    for (byte in 0 until 256) {
                        val normalized = byte.toFloat() / 255f
                        val alpha = (tint ushr 24).toFloat() / 255f
                        val value =
                            when (component) {
                                0 -> normalized
                                1 -> normalized * alpha
                                2 -> 1f - normalized * alpha
                                3 -> normalized * ((tint ushr 16 and 255).toFloat() / 255f)
                                4 -> normalized * ((tint ushr 8 and 255).toFloat() / 255f)
                                else -> normalized * ((tint and 255).toFloat() / 255f)
                            }
                        val argb = image.argbAt(component * 256 + byte, row)
                        val word = ((argb ushr 16 and 255)) or ((argb ushr 8 and 255) shl 8) or ((argb and 255) shl 16) or (argb ushr 24 shl 24)
                        assertEquals(value.toRawBits(), word, "tint=$tint component=$component byte=$byte")
                    }
                }
            }
            assertSame(image, workspace.get(key.toList()))
        }
    }

    @Test
    fun orderedKeysAndExtractedPixelsCannotMutateOtherTilesOrOwners() {
        val key = mutableListOf(0x80BFD7EF.toInt(), -1)
        val workspace = FabricMinecraftCompositionFactors()
        val first = workspace.get(key)
        val expected = first.copyArgb()
        val reversed = workspace.get(key.reversed())
        assertNotSame(first, reversed)
        val replacement = workspace.get(listOf(0x80BFD7EE.toInt(), -1))
        assertNotSame(first, replacement)
        val separate = FabricMinecraftCompositionFactors().get(key)
        assertEquals(first, separate)
        assertNotSame(first, separate)
        key.fill(0)
        first.copyArgb().fill(0)
        assertSame(first, workspace.get(listOf(0x80BFD7EF.toInt(), -1)))
        assertArrayEquals(expected, first.copyArgb())
        val supplied = createDrawImage(first.size, expected)
        val seeded = FabricMinecraftCompositionFactors()
        assertSame(supplied, seeded.get(listOf(0x80BFD7EF.toInt(), -1), supplied))
        assertSame(supplied, seeded.get(listOf(0x80BFD7EF.toInt(), -1)))
    }

    @Test
    fun fullKeyAndRowWorkspacesKeepOrdinaryMissesUncached() {
        val keys = FabricMinecraftCompositionFactors()
        repeat(256) { keys.get(listOf(it)) }
        assertEquals(256, keys.retainedKeys)
        assertEquals(256, keys.retainedRows)
        assertNotSame(keys.get(listOf(256)), keys.get(listOf(256)))
        assertEquals(256, keys.retainedKeys)
        val rows = FabricMinecraftCompositionFactors()
        repeat(4) { group -> rows.get(List(256) { group * 256 + it }) }
        assertEquals(1024, rows.retainedRows)
        assertNotSame(rows.get(listOf(1024)), rows.get(listOf(1024)))
        assertEquals(4, rows.retainedKeys)
        assertEquals(1024, rows.retainedRows)
    }

    @Test
    fun malformedSeedPublishesNoWorkspaceEntry() {
        val workspace = FabricMinecraftCompositionFactors()
        val invalid = createDrawImage(IntSize(1, 1), intArrayOf(0))
        assertThrows(IllegalArgumentException::class.java) { workspace.get(listOf(-1), invalid) }
        assertEquals(0, workspace.retainedKeys)
        assertEquals(0, workspace.retainedRows)
        val valid = workspace.get(listOf(-1))
        assertSame(valid, workspace.get(listOf(-1)))
    }
}
