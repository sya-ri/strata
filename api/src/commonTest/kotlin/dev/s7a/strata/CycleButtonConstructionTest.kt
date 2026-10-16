package dev.s7a.strata

import dev.s7a.strata.component.CycleButtonState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Verifies collection construction retains detached ordered options and canonical formatting on both targets.
 */
internal class CycleButtonConstructionTest {
    @Test
    fun listAndNonListCallersKeepIndependentSnapshotsAndCanonicalFormatting() {
        val first = Option(1)
        val second = Option(2)
        val alias = Option(2)
        val list = mutableListOf(first, second)
        val collection = linkedSetOf(first, second)
        var formatted: Option? = null
        val convert: (Option) -> String = { value ->
            formatted = value
            "option-${value.number}"
        }
        val states = listOf(CycleButtonState(list, alias, convert), CycleButtonState(collection, alias, convert))
        assertNull(formatted)
        list.clear()
        collection.clear()

        for (state in states) {
            assertEquals(listOf(first, second), state.values)
            assertSame(first, state.values[0])
            assertSame(second, state.values[1])
            assertSame(alias, state.value)
            assertEquals("option-2", state.format(alias))
            assertSame(second, formatted)
            assertSame(first, state.next())
            assertSame(second, state.previous())
            assertFailsWith<IllegalArgumentException> { state.value = Option(3) }
            assertSame(second, state.value)
        }
    }

    @Test
    fun listAndNonListConstructionPreserveValidationAndConversionFailures() {
        val convert: (Int) -> String = Int::toString
        val emptyListFailure = assertFailsWith<IllegalArgumentException> { CycleButtonState(emptyList<Int>(), 0, convert) }
        val emptyCollectionFailure = assertFailsWith<IllegalArgumentException> { CycleButtonState(emptySet<Int>(), 0, convert) }
        assertEquals("CycleButton values must not be empty.", emptyListFailure.message)
        assertEquals(emptyListFailure.message, emptyCollectionFailure.message)
        val duplicate = assertFailsWith<IllegalArgumentException> { CycleButtonState(listOf(1, 1), 1, convert) }
        assertEquals("CycleButton values must be unique.", duplicate.message)
        val outside = assertFailsWith<IllegalArgumentException> { CycleButtonState(listOf(1, 2), 3, convert) }
        assertEquals("CycleButton initial value must belong to its values.", outside.message)
        val expected = IllegalArgumentException("display conversion")
        val state = CycleButtonState(mutableListOf(1, 2), 1) { throw expected }
        assertSame(expected, assertFailsWith<IllegalArgumentException> { state.format(1) })
        assertEquals(1, state.value)
    }

    private data class Option(
        val number: Int,
    )
}
