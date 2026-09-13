package dev.s7a.strata

import dev.s7a.strata.text.UiText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Verifies public text snapshots remain detached and reject mutation on every target.
 */
internal class PortableSnapshotTest {
    @Test
    fun textPartsAndTheirViewsCannotMutateTheDescription() {
        val original = listOf(UiText.Literal("first"), UiText.Literal("second"))
        val source = original.toMutableList()
        val text = UiText.Concatenated(source)
        source.clear()
        assertEquals(original, text.parts)
        assertFailsWith<UnsupportedOperationException> { (text.parts as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (text.parts.subList(0, 1) as MutableList).clear() }
        val iterator = text.parts.listIterator() as MutableListIterator
        iterator.next()
        assertFailsWith<UnsupportedOperationException> { iterator.remove() }
        assertEquals(original, text.parts)
    }
}
