@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.remote

import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.projection.ProjectionType
import dev.s7a.strata.projection.ProjectionValue
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Verifies extension preparation and cleanup failures cannot retain partially installed client state.
 */
internal class RemoteClientLifetimeTest {
    @Test
    fun preparationFailureReleasesExistingAndNewStateAndClosesTheSession() {
        val released = mutableListOf<Long>()
        val outgoing = mutableListOf<RemoteMessage>()
        val failure = IllegalStateException("extension preparation failed")
        val registry = RemoteRegistry()
        val key = RemoteStateKey(Owned::class)
        registry.element(TYPE, { requireNotNull(it as? ProjectionValue.Integer).value }, { value, context ->
            context.states.prepare(context.identity, key, { Owned(context.identity) }, {}, { released.add(it.identity) })
            if (value == 2L) throw failure
        }) { _, context -> evaluateComponentTree { Spacer(context.modifier, context.key) } }
        val first = tree(listOf(node(1, 1)))
        val client = RemoteClientSession(RemoteMessage.Snapshot(1, 1, ProjectionValue.Absent, first), registry, send = outgoing::add)
        val next = tree(listOf(node(1, 1, listOf(2)), node(2, 2)))
        assertSame(failure, assertThrows(IllegalStateException::class.java) { client.receive(RemoteMessage.Update(1, 1, 2, RemotePatch.between(first, next))) })
        assertEquals(listOf(1L, 2L), released)
        assertEquals(RemoteSessionStatus.Closed(RemoteFailure.InvalidMessage), client.status)
        assertEquals(listOf(RemoteMessage.Applied(1, 1)), outgoing.filterIsInstance<RemoteMessage.Applied>())
        assertEquals(RemoteFailure.InvalidMessage, outgoing.filterIsInstance<RemoteMessage.Close>().single().reason)
        client.close()
        assertEquals(2, released.size)
    }

    @Test
    fun releaseFailureStillNotifiesPeerAndPreservesFirstFailure() {
        val outgoing = mutableListOf<RemoteMessage>()
        val failure = IllegalStateException("extension release failed")
        val registry = RemoteRegistry()
        val key = RemoteStateKey(Owned::class)
        registry.element(TYPE, { it }, { _, context ->
            context.states.prepare(context.identity, key, { Owned(context.identity) }, {}, { throw failure })
        }) { _, context -> evaluateComponentTree { Spacer(context.modifier, context.key) } }
        val client = RemoteClientSession(RemoteMessage.Snapshot(1, 1, ProjectionValue.Absent, tree(listOf(node(1, 1)))), registry, send = outgoing::add)
        assertSame(failure, assertThrows(IllegalStateException::class.java) { client.close() })
        assertEquals(RemoteFailure.PeerClosed, outgoing.filterIsInstance<RemoteMessage.Close>().single().reason)
        client.close()
        assertEquals(2, outgoing.size)
    }

    @Test
    fun retirementFailureClosesSurvivingAndNewEditableEntriesWithoutReleasingTheFailedEntryTwice() {
        val released = mutableListOf<Long>()
        val outgoing = mutableListOf<RemoteMessage>()
        val failure = IllegalStateException("retirement failed")
        val registry = RemoteRegistry()
        val key = RemoteStateKey(Owned::class)
        registry.element(TYPE, { it }, { _, context ->
            context.states.prepare(context.identity, key, { Owned(context.identity) }, {}, { value ->
                released.add(value.identity)
                if (value.identity == 2L) throw failure
            })
        }) { _, context -> evaluateComponentTree { Spacer(context.modifier, context.key) } }
        val first = tree(listOf(node(1, 1, listOf(2)), node(2, 2)))
        val client = RemoteClientSession(RemoteMessage.Snapshot(1, 1, ProjectionValue.Absent, first), registry, send = outgoing::add)
        val next = tree(listOf(node(1, 1, listOf(3)), node(3, 3)))
        assertSame(
            failure,
            assertThrows(IllegalStateException::class.java) {
                client.receive(RemoteMessage.Update(1, 1, 2, RemotePatch.between(first, next)))
            },
        )
        assertEquals(listOf(2L, 1L, 3L), released)
        assertEquals(RemoteSessionStatus.Closed(RemoteFailure.InvalidMessage), client.status)
        assertEquals(RemoteFailure.InvalidMessage, outgoing.filterIsInstance<RemoteMessage.Close>().single().reason)
        client.close()
        assertEquals(3, released.size)
    }

    @Test
    fun closingTheStoreFromAnUpdateCallbackClosesTheSessionAndCannotPublishTheNextRevision() {
        val released = mutableListOf<Long>()
        val outgoing = mutableListOf<RemoteMessage>()
        val registry = RemoteRegistry()
        val key = RemoteStateKey(Owned::class)
        registry.element(TYPE, { requireNotNull(it as? ProjectionValue.Integer).value }, { value, context ->
            context.states.prepare(context.identity, key, { Owned(context.identity) }, {
                if (value == 2L) context.states.close()
            }, { released.add(it.identity) })
        }) { _, context -> evaluateComponentTree { Spacer(context.modifier, context.key) } }
        val first = tree(listOf(node(1, 1)))
        val client = RemoteClientSession(RemoteMessage.Snapshot(1, 1, ProjectionValue.Absent, first), registry, send = outgoing::add)
        val next = tree(listOf(node(1, 2)))
        assertThrows(IllegalStateException::class.java) {
            client.receive(RemoteMessage.Update(1, 1, 2, RemotePatch.between(first, next)))
        }
        assertEquals(listOf(1L), released)
        assertEquals(RemoteSessionStatus.Closed(RemoteFailure.InvalidMessage), client.status)
        assertEquals(listOf(RemoteMessage.Applied(1, 1)), outgoing.filterIsInstance<RemoteMessage.Applied>())
        assertEquals(RemoteFailure.InvalidMessage, outgoing.filterIsInstance<RemoteMessage.Close>().single().reason)
        client.close()
        assertEquals(1, released.size)
    }

    private fun node(
        identity: Long,
        value: Long,
        children: List<Long> = emptyList(),
    ): RemoteNode = RemoteNode(RemoteDeclaration(identity, TYPE, ProjectionValue.Integer(value)), emptyList(), children)

    private fun tree(nodes: List<RemoteNode>): RemoteTree = RemoteTree(1, nodes)

    private class Owned(
        val identity: Long,
    ) : RemoteEditableValue {
        override fun flushEdits(actions: RemoteClientActions) = Unit
    }

    private companion object {
        val TYPE = ProjectionType(ResourceId("test", "owned"))
    }
}
