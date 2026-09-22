package dev.s7a.strata.runtime.remote

import dev.s7a.strata.projection.ProjectionType
import dev.s7a.strata.projection.ProjectionValue
import dev.s7a.strata.resource.ResourceId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Verifies atomic tree changes and rejects malformed graph topology independently of Minecraft.
 */
internal class RemoteTreeTest {
    private val type = ProjectionType(ResourceId("test", "node"))

    @Test
    fun `diff preserves identity while changing ordered children`() {
        val before = RemoteTree(1, listOf(node(1, listOf(2, 3)), node(2), node(3)))
        val after = RemoteTree(1, listOf(node(1, listOf(3, 4)), node(3), node(4)))
        val patch = RemotePatch.between(before, after)
        assertEquals(setOf(2L), patch.removed)
        assertEquals(listOf(1L, 4L), patch.changed.map { it.declaration.identity })
        assertEquals(after, patch.apply(before))
        assertEquals(3, before.nodes.size)
    }

    @Test
    fun `rejects cycles shared nodes and disconnected nodes`() {
        listOf(
            listOf(node(1, listOf(1))),
            listOf(node(1, listOf(2, 2)), node(2)),
            listOf(node(1), node(2)),
            listOf(node(1, listOf(2))),
        ).forEach { nodes -> assertThrows(IllegalArgumentException::class.java) { RemoteTree(1, nodes) } }
    }

    @Test
    fun `invalid patch leaves the previous tree usable`() {
        val before = RemoteTree(1, listOf(node(1, listOf(2)), node(2)))
        assertThrows(IllegalArgumentException::class.java) { RemotePatch(1, emptyList(), listOf(2)).apply(before) }
        assertEquals(listOf(2L), before.nodes.getValue(1).children)
    }

    private fun node(
        identity: Long,
        children: List<Long> = emptyList(),
    ): RemoteNode = RemoteNode(RemoteDeclaration(identity, type, ProjectionValue.Absent), children = children)
}
