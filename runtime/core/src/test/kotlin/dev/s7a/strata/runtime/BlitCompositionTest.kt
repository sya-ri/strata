package dev.s7a.strata.runtime

import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.createDrawImage
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
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

    @Test
    fun privateTemplatesPreservePublicCopyIsolationEqualityAndOldOwnerPixels() {
        val supplied = intArrayOf(0x00123456, 0x80335577.toInt(), -1, 0x01010203, 0xFE123456.toInt(), 0)
        val image = createDrawImage(IntSize(3, 2), supplied)
        val commands = grid(image, IntRect(0, 0, 3, 2), 8, 8)
        val first = (composeDenseBlits(commands).single() as LocalDrawCommand.ComposedBlits).commands.single().image
        val second = (composeDenseBlits(commands).single() as LocalDrawCommand.ComposedBlits).commands.single().image
        val expected = IntArray(24 * 16) { index -> supplied[index / 24 % 2 * 3 + index % 24 % 3] }
        val snapshot = createDrawImage(first.size, expected)
        assertEquals(snapshot, first)
        assertEquals(snapshot.hashCode(), first.hashCode())
        assertEquals(first, second)
        assertNotSame(first, second)
        supplied.fill(0)
        first.copyArgb().fill(0)
        assertArrayEquals(expected, first.copyArgb())
        assertArrayEquals(expected, second.copyArgb())
        val replacement = createDrawImage(image.size, IntArray(6) { 0xFF123456.toInt() })
        val next = (composeDenseBlits(grid(replacement, IntRect(0, 0, 3, 2), 8, 8)).single() as LocalDrawCommand.ComposedBlits).commands.single().image
        assertEquals(0xFF123456.toInt(), next.argbAt(0, 0))
        assertArrayEquals(expected, first.copyArgb())
        assertEquals(snapshot.hashCode(), first.hashCode())
    }

    @Test
    fun thirtyTwoAtlasGroupsKeepTheirBoundAndThirtyThreeKeepTheOriginalList() {
        val image = createDrawImage(IntSize(33, 1)) { x, _ -> 0x80000000.toInt() or x }
        for (groups in listOf(32, 33)) {
            val commands =
                (0 until groups).flatMap { group ->
                    grid(image, IntRect(group, 0, group + 1, 1), 4, 4, left = group * 4)
                }
            val owner = composeDenseBlits(commands).single() as LocalDrawCommand.ComposedBlits
            if (groups == 33) {
                assertSame(commands, owner.commands)
            } else {
                assertEquals(32, owner.commands.size)
                owner.commands.forEachIndexed { group, command ->
                    assertEquals(IntSize(4, 4), command.image.size)
                    assertArrayEquals(IntArray(16) { image.argbAt(group, 0) }, command.image.copyArgb())
                }
            }
            assertSame(owner.commands, owner.commands)
        }
    }

    @Test
    fun conservativeTemplateBudgetAndOversizedOrStretchedSourcesRemainUnchanged() {
        val image = createDrawImage(IntSize(65, 65)) { x, y -> 0x80000000.toInt() or (y * 65 + x) }
        val source = IntRect(0, 0, 4, 4)
        val commands = grid(image, source, 8, 8)
        val compacted = (composeDenseBlits(commands).single() as LocalDrawCommand.ComposedBlits).commands
        assertEquals(IntSize(16, 20), compacted.first().image.size)
        assertEquals(4, compacted.size)
        for (extent in listOf(64, 65)) {
            val large = grid(image, IntRect(0, 0, extent, extent), 8, 8)
            assertSame(large, (composeDenseBlits(large).single() as LocalDrawCommand.ComposedBlits).commands)
        }
        val stretched = commands.map { it.copy(destination = IntRect(it.destination.left * 2, it.destination.top * 2, it.destination.right * 2, it.destination.bottom * 2)) }
        assertSame(stretched, (composeDenseBlits(stretched).single() as LocalDrawCommand.ComposedBlits).commands)
    }

    @Test
    fun aFailedPrivateTemplatePublishesNothingAndTheLazyOwnerCanRetry() {
        val failure = IllegalStateException("template source failed")
        var fail = true
        val image =
            object : DrawImage {
                override val size: IntSize = IntSize(3, 2)

                override fun argbAt(
                    x: Int,
                    y: Int,
                ): Int {
                    if (fail && x == 2) throw failure
                    return 0x80000000.toInt() or (y * 3 + x)
                }

                override fun copyArgb(): IntArray = IntArray(6) { argbAt(it % 3, it / 3) }
            }
        val owner = composeDenseBlits(grid(image, IntRect(0, 0, 3, 2), 8, 8)).single() as LocalDrawCommand.ComposedBlits
        assertSame(failure, assertThrows(IllegalStateException::class.java) { owner.commands })
        fail = false
        val result = owner.commands.single().image
        for (y in 0 until 16) for (x in 0 until 24) assertEquals(image.argbAt(x % 3, y % 2), result.argbAt(x, y))
        assertSame(owner.commands, owner.commands)
    }

    private fun grid(
        image: DrawImage,
        source: IntRect,
        columns: Int,
        rows: Int,
        left: Int = 0,
    ): List<LocalDrawCommand.BlitImage> =
        List(columns * rows) { index ->
            val x = left + index % columns * source.width
            val y = index / columns * source.height
            LocalDrawCommand.BlitImage(image, source, IntRect(x, y, x + source.width, y + source.height))
        }
}
