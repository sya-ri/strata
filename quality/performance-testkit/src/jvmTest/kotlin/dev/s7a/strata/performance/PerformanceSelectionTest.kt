package dev.s7a.strata.performance

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Targeted evidence cannot hide misspelled IDs or masquerade as a complete registered matrix.
 */
internal class PerformanceSelectionTest {
    @Test
    internal fun omittedAndExplicitFullSelectionsRetainRegistrationOrder() {
        val available = linkedSetOf("TextField", "NativeCanvas")
        listOf(null, "NativeCanvas,TextField").forEach { requested ->
            val selection = PerformanceSelection(available, requested)
            assertEquals(available.toList(), selection.ids.toList())
            assertFalse(selection.narrowed)
        }
    }

    @Test
    internal fun subsetIsDetachedAndMarkedNarrow() {
        val available = linkedSetOf("TextField", "NativeCanvas")
        val selection = PerformanceSelection(available, " TextField ")
        available.clear()
        assertEquals(setOf("TextField"), selection.ids)
        assertTrue(selection.narrowed)
    }

    @Test
    internal fun invalidSelectionsCannotProduceEvidence() {
        listOf("", " ", ",", "TextField,", "TextFiled", "TextField,TextField").forEach { requested ->
            assertFailsWith<IllegalArgumentException> { PerformanceSelection(setOf("TextField"), requested) }
        }
        assertFailsWith<IllegalArgumentException> { PerformanceSelection(emptySet()) }
    }
}
