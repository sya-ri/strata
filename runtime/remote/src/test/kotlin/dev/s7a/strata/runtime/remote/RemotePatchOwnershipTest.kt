@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.remote

import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.projection.ProjectionType
import dev.s7a.strata.projection.ProjectionValue
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Verifies private patch ownership preserves public snapshots, complete validation, ordering and client publication.
 */
internal class RemotePatchOwnershipTest {
    private val type = ProjectionType(ResourceId("test", "patch_ownership"))
    private val modifierType = ProjectionType(ResourceId("test", "patch_modifier"))

    @Test
    fun publicCollectionInputAndReturnedIndexesRemainIndependentlyImmutable() {
        val input = mutableListOf(node(1, listOf(2)), node(2))
        val before = RemoteTree(1, input)
        val original = before.nodes.toMap()
        input.clear()
        val after = RemotePatch(1, listOf(node(1, listOf(3)), node(3)), listOf(2)).apply(before)
        assertEquals(original, before.nodes)
        assertEquals(listOf(1L, 3L), after.nodes.keys.toList())
        assertNotSame(before.nodes, after.nodes)
        assertThrows(UnsupportedOperationException::class.java) { (after.nodes as MutableMap<Long, RemoteNode>).clear() }
        val next = RemotePatch(3, listOf(node(3)), listOf(1)).apply(after)
        assertEquals(listOf(1L, 3L), after.nodes.keys.toList())
        assertEquals(listOf(3L), next.nodes.keys.toList())
        assertEquals(original, before.nodes)
    }

    @Test
    fun patchChangesKeepExistingIndexOrderWhileNewRecordsAppendAndRootCanMove() {
        val before = RemoteTree(1, listOf(node(1, listOf(2, 3)), node(2), node(3)))
        val patch = RemotePatch(4, listOf(node(4, listOf(3, 2)), node(2, value = 2)), listOf(1))
        val result = patch.apply(before)
        assertEquals(listOf(2L, 3L, 4L), result.nodes.keys.toList())
        assertEquals(listOf(3L, 2L), result.nodes.getValue(4).children)
        assertEquals(4L, result.root)
        val expected = RemoteTree(4, listOf(node(2, value = 2), node(3), node(4, listOf(3, 2))))
        assertEquals(expected, result)
        assertEquals(expected.hashCode(), result.hashCode())
        assertEquals(listOf(1L, 2L, 3L), before.nodes.keys.toList())
    }

    @Test
    fun noChangePatchesStillRejectTighterNodeDepthAndDeclarationBounds() {
        val before = RemoteTree(1, listOf(node(1, listOf(2)), node(2, listOf(3)), node(3)))
        val patch = RemotePatch(1, emptyList(), emptyList())
        for (limits in listOf(RemoteLimits(treeNodes = 2), RemoteLimits(valueDepth = 2), RemoteLimits(treeNodes = 3, collectionEntries = 5))) {
            assertThrows(IllegalArgumentException::class.java) { patch.apply(before, limits) }
        }
        assertEquals(before, patch.apply(before))
        assertEquals(listOf(1L, 2L, 3L), before.nodes.keys.toList())
    }

    @Test
    fun ownedCandidatesRejectEveryMalformedGraphWithoutChangingPreviousTrees() {
        val before = RemoteTree(1, listOf(node(1, listOf(2)), node(2)))
        val original = before.nodes.toMap()
        val invalid =
            listOf(
                RemotePatch(99, emptyList(), emptyList()),
                RemotePatch(1, listOf(node(1, listOf(1))), emptyList()),
                RemotePatch(1, listOf(node(1, listOf(2, 2))), emptyList()),
                RemotePatch(1, listOf(node(1)), emptyList()),
                RemotePatch(1, listOf(node(1, listOf(3))), emptyList()),
                RemotePatch(1, listOf(node(1, listOf(2), modifier = 2)), emptyList()),
                RemotePatch(1, listOf(node(2, modifier = 100001)), emptyList()),
                RemotePatch(1, emptyList(), listOf(2)),
                RemotePatch(1, emptyList(), listOf(99)),
            )
        for (patch in invalid) {
            assertThrows(IllegalArgumentException::class.java) { patch.apply(before) }
            assertEquals(original, before.nodes)
            assertEquals(before, RemotePatch(1, emptyList(), emptyList()).apply(before))
        }
        assertThrows(IllegalArgumentException::class.java) { RemoteTree(1, listOf(node(1), node(1))) }
        assertThrows(IllegalArgumentException::class.java) { RemotePatch(1, listOf(node(1), node(1)), emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { RemotePatch(1, emptyList(), listOf(2, 2)) }
        assertThrows(IllegalArgumentException::class.java) { RemotePatch(1, listOf(node(2)), listOf(2)) }
    }

    @Test
    fun independentPatchesMayReadTheSameImmutablePreviousIndexConcurrently() {
        val before = RemoteTree(1, listOf(node(1, (2L..128L).toList())) + (2L..128L).map { node(it) })
        val original = before.nodes.toMap()
        val executor = Executors.newFixedThreadPool(4)
        try {
            val tasks = (2L..9L).map { identity ->
                Callable {
                    val result = RemotePatch(1, listOf(node(identity, value = identity)), emptyList()).apply(before)
                    assertEquals(ProjectionValue.Integer(identity), result.nodes.getValue(identity).declaration.value)
                    assertEquals(original, before.nodes)
                    assertEquals(before.nodes.keys.toList(), result.nodes.keys.toList())
                }
            }
            executor.invokeAll(tasks).forEach { it.get() }
        } finally {
            executor.shutdownNow()
            check(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun invalidCandidateClosesClientWithoutPublishingOrAcknowledgingANewerRevision() {
        var released = 0
        val outgoing = mutableListOf<RemoteMessage>()
        val registry = RemoteRegistry()
        val key = RemoteStateKey(Owned::class)
        registry.element(type, { it }, { _, context ->
            context.states.prepare(context.identity, key, { Owned() }, {}, { released++ })
        }) { _, context -> evaluateComponentTree { Spacer(context.modifier, context.key) } }
        registry.modifier(modifierType, { it }) { _, _ -> Modifier.Empty }
        val before = RemoteTree(1, listOf(node(1)))
        val original = before.nodes.toMap()
        val client = RemoteClientSession(RemoteMessage.Snapshot(1, 1, ProjectionValue.Absent, before), registry, send = outgoing::add)
        assertThrows(IllegalArgumentException::class.java) { client.receive(RemoteMessage.Update(1, 1, 2, RemotePatch(1, listOf(node(1, listOf(2))), emptyList()))) }
        assertEquals(RemoteSessionStatus.Closed(RemoteFailure.InvalidMessage), client.status)
        assertEquals(listOf(RemoteMessage.Applied(1, 1)), outgoing.filterIsInstance<RemoteMessage.Applied>())
        assertEquals(1, outgoing.filterIsInstance<RemoteMessage.Close>().size)
        assertEquals(original, before.nodes)
        assertEquals(1, released)
        client.close()
        assertEquals(1, released)
    }

    private fun node(
        identity: Long,
        children: List<Long> = emptyList(),
        value: Long = 0,
        modifier: Long = 100000 + identity,
    ): RemoteNode = RemoteNode(RemoteDeclaration(identity, type, ProjectionValue.Integer(value)), listOf(RemoteDeclaration(modifier, modifierType, ProjectionValue.Absent)), children)

    private class Owned
}
