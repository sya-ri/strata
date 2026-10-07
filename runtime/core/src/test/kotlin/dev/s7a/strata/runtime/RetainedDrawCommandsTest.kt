package dev.s7a.strata.runtime

import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies ordinary read-only list behavior for nested, sparse immutable paint snapshots.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class RetainedDrawCommandsTest {
    @Test
    fun deepConcatenationsPreserveIndexedIterationEqualityAndCursorExhaustion() {
        val expected = List(64) { DrawCommand.FillRectangle(IntRect(it, 0, it + 1, 1), ArgbColor(it)) }
        var commands = RetainedDrawCommands(emptyList())
        expected.forEach { commands = RetainedDrawCommands(listOf(emptyList(), commands, listOf(it), emptyList())) }
        val parts = mutableListOf<List<DrawCommand>>(commands)
        val detached = RetainedDrawCommands(parts)
        parts.clear()
        assertEquals(expected, detached)
        assertEquals(expected.hashCode(), detached.hashCode())
        for (index in expected.indices) assertEquals(expected[index], detached[index])
        assertEquals(expected.takeLast(17), detached.subList(47, 64))
        val iterator = detached.iterator()
        expected.forEach {
            assertTrue(iterator.hasNext())
            assertTrue(iterator.hasNext())
            assertEquals(it, iterator.next())
        }
        assertFalse(iterator.hasNext())
        assertFalse(iterator.hasNext())
        assertThrows(NoSuchElementException::class.java) { iterator.next() }
        assertThrows(IndexOutOfBoundsException::class.java) { detached[-1] }
        assertThrows(IndexOutOfBoundsException::class.java) { detached[64] }
        assertTrue(RetainedDrawCommands(listOf(emptyList(), RetainedDrawCommands(emptyList()))).isEmpty())
    }
}
