package dev.s7a.strata

import dev.s7a.strata.text.UiText
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifies public text snapshots remain detached from caller-owned collections on every target.
 */
internal class PortableSnapshotTest {
    @Test
    fun textPartsRemainDetachedFromTheSource() {
        val original = listOf(UiText.Literal("first"), UiText.Literal("second"))
        val source = original.toMutableList()
        val text = UiText.Concatenated(source)
        source.clear()
        assertEquals(original, text.parts)
        assertEquals(original.take(1), text.parts.subList(0, 1))
    }
}
