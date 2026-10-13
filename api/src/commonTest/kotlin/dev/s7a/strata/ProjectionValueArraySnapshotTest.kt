package dev.s7a.strata

import dev.s7a.strata.projection.ProjectionValue
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame

/**
 * The internal array entry owns membership while preserving ordered immutable value references.
 */
class ProjectionValueArraySnapshotTest {
    @Test
    @OptIn(InternalStrataRuntimeApi::class)
    fun arraySequencesDetachEverySizeAndPreserveOrderedDuplicateReferences() {
        val shared = ProjectionValue.Text("shared")
        val nested = ProjectionValue.Sequence(listOf(ProjectionValue.Integer(7)))
        listOf(0, 1, 2, 4, 8, 128).forEach { count ->
            val input =
                Array<ProjectionValue>(count) { index ->
                    when (index % 3) {
                        0 -> shared
                        1 -> ProjectionValue.Absent
                        else -> nested
                    }
                }
            val expected = ProjectionValue.Sequence(input.toList())
            val old = ProjectionValue.Sequence.fromArray(input)
            val hash = old.hashCode()
            input.fill(ProjectionValue.Absent)
            val fresh = ProjectionValue.Sequence.fromArray(input)
            assertEquals(expected, old)
            assertEquals(expected.values, old.values)
            assertEquals(hash, old.hashCode())
            old.values.forEachIndexed { index, value -> assertSame(expected.values[index], value) }
            assertEquals(List(count) { ProjectionValue.Absent }, fresh.values)
            assertNotEquals<ProjectionValue.Sequence?>(old, null)
        }
    }
}
