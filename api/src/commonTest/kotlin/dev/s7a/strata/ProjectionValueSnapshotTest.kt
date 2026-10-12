package dev.s7a.strata

import dev.s7a.strata.projection.BuiltinProjection
import dev.s7a.strata.projection.ProjectionAction
import dev.s7a.strata.projection.ProjectionBinding
import dev.s7a.strata.projection.ProjectionScope
import dev.s7a.strata.projection.ProjectionType
import dev.s7a.strata.projection.ProjectionValue
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.text.UiText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * Collection membership snapshots preserve ordered immutable values on both shared targets.
 */
class ProjectionValueSnapshotTest {
    @Test
    fun ordinarySequencesDetachMutableMembershipAndPreserveDuplicateReferences() {
        val shared = ProjectionValue.Text("shared")
        val nestedInput = mutableListOf<ProjectionValue>(ProjectionValue.Integer(7))
        val nested = ProjectionValue.Sequence(nestedInput)
        val input = mutableListOf<ProjectionValue>(shared, nested, shared)
        val old = ProjectionValue.Sequence(input)
        val hash = old.hashCode()
        input[0] = ProjectionValue.Absent
        input.removeAt(1)
        nestedInput.clear()
        val fresh = ProjectionValue.Sequence(input)
        assertEquals(listOf(shared, nested, shared), old.values)
        assertEquals(listOf(ProjectionValue.Integer(7)), nested.values)
        assertSame(old.values[0], old.values[2])
        assertEquals(hash, old.hashCode())
        assertEquals(listOf(ProjectionValue.Absent, shared), fresh.values)
    }

    @Test
    fun positionalFactoriesPreserveIndependentSmallLargeAndNestedSnapshots() {
        val scope = Scope()
        listOf(0, 1, 2, 4, 8, 128).forEach { count ->
            val values = Array<ProjectionValue>(count) { ProjectionValue.Integer(it.toLong()) }
            val declaration = BuiltinProjection.Grid.properties(*values)
            val expected = ProjectionValue.Sequence(values.toList())
            values.fill(ProjectionValue.Absent)
            assertEquals(BuiltinProjection.Grid.type, declaration.type)
            assertEquals(expected, declaration.encode(scope))
            assertEquals(expected, declaration.encode(scope))
        }
        val bytes = byteArrayOf(3, 4)
        val nested = ProjectionValue.Sequence(listOf(ProjectionValue.Bytes(bytes)))
        val old = BuiltinProjection.Stack.properties(nested, nested)
        bytes.fill(0)
        val fresh = BuiltinProjection.Stack.properties(ProjectionValue.Absent)
        assertEquals(ProjectionValue.Sequence(listOf(nested, nested)), old.encode(scope))
        assertEquals(ProjectionValue.Sequence(listOf(ProjectionValue.Absent)), fresh.encode(scope))
        assertEquals(ProjectionValue.Bytes(byteArrayOf(3, 4)), nested.values.single())
    }

    /**
     * Property-only projection must never request an endpoint or retain a resource capability.
     */
    private class Scope : ProjectionScope {
        override fun action(
            action: ProjectionAction<*>,
            key: ProjectionValue,
        ): Long = error("Unexpected action")

        override fun <T : Any> binding(binding: ProjectionBinding<T>): ProjectionValue = error("Unexpected binding")

        override fun image(image: DrawImage): ProjectionValue = error("Unexpected image")

        override fun text(text: UiText): ProjectionValue = error("Unexpected text")

        override fun requireType(type: ProjectionType): Unit = error("Unexpected nested type")
    }
}
