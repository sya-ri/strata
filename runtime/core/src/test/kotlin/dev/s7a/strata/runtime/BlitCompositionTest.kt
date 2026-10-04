package dev.s7a.strata.runtime

import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.createDrawImage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/**
 * Verifies bounded, nonoverlapping logical composition without changing source alpha or nearest sampling.
 */
internal class BlitCompositionTest {
    @Test
    fun densePatternRetainsExactSourcePixelsAndOneOwnedImage() {
        val image = createDrawImage(IntSize(3, 2), intArrayOf(0x00123456, 0x80335577.toInt(), -1, 0x01010203, 0xFE123456.toInt(), 0))
        val commands =
            (0 until 64).map { index ->
                val x = 100 + index % 8 * 3
                val y = -20 + index / 8 * 2
                LocalDrawCommand.BlitImage(image, IntRect(0, 0, 3, 2), IntRect(x, y, x + 3, y + 2))
            }
        val composed = composeDenseBlits(commands).single() as LocalDrawCommand.ComposedBlits
        assertEquals(commands, composed.original)
        assertEquals(IntRect(100, -20, 124, -4), composed.destination)
        val result = composed.commands.single().image
        assertSame(composed.commands, composed.commands)
        for (y in 0 until 16) for (x in 0 until 24) assertEquals(image.argbAt(x % 3, y % 2), result.argbAt(x, y))
    }

    @Test
    fun fullHdPatternBoundsImageStorageAndPreservesEveryTilePhase() {
        val image = createDrawImage(IntSize(4, 4), IntArray(16) { (it * 0x10203) or 0x80335500.toInt() })
        val original =
            (0 until 480 * 270).map { index ->
                val x = index % 480 * 4
                val y = index / 480 * 4
                LocalDrawCommand.BlitImage(image, IntRect(0, 0, 4, 4), IntRect(x, y, x + 4, y + 4))
            }
        val composed = composeDenseBlits(original).single() as LocalDrawCommand.ComposedBlits
        val result = composed.commands
        assertEquals(510, result.size)
        val template = result.first().image
        assertEquals(IntSize(64, 64), template.size)
        result.forEach { assertSame(template, it.image) }
        result.forEach { command ->
            for (y in 0 until command.source.height) {
                for (x in 0 until command.source.width) {
                    assertEquals(image.argbAt(x % 4, y % 4), command.image.argbAt(x, y))
                }
            }
        }
    }

    @Test
    fun overlapsGapsAndOversizedGridsKeepTheirOriginalCommands() {
        val image = createDrawImage(IntSize(1, 1), intArrayOf(-1))
        val commands = (0 until 64).map { index -> LocalDrawCommand.BlitImage(image, IntRect(0, 0, 1, 1), IntRect(index, 0, index + 1, 1)) }
        val overlap = commands.toMutableList().apply { this[63] = this[0] }
        assertEquals(overlap, (composeDenseBlits(overlap).single() as LocalDrawCommand.ComposedBlits).commands)
        val gap = commands.mapIndexed { index, command -> if (index == 63) command.copy(destination = IntRect(64, 0, 65, 1)) else command }
        assertEquals(gap, (composeDenseBlits(gap).single() as LocalDrawCommand.ComposedBlits).commands)
        val reversed = commands.reversed()
        assertSame(reversed, (composeDenseBlits(reversed).single() as LocalDrawCommand.ComposedBlits).commands)
        val oversized = commands.mapIndexed { index, command -> command.copy(destination = IntRect(index * 32768, 0, (index + 1) * 32768, 1)) }
        assertEquals(oversized, (composeDenseBlits(oversized).single() as LocalDrawCommand.ComposedBlits).commands)
    }
}
