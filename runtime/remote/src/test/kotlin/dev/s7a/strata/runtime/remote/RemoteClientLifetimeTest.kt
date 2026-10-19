@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.remote

import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.projection.ProjectionType
import dev.s7a.strata.projection.ProjectionValue
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.ui.UiInputPolicy
import dev.s7a.strata.ui.UiInteractionMode
import dev.s7a.strata.ui.UiOperationResult
import dev.s7a.strata.ui.UiPresentation
import dev.s7a.strata.ui.UiRejection
import dev.s7a.strata.ui.UiSession
import dev.s7a.strata.ui.UiSessionStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Verifies extension preparation and cleanup failures cannot retain partially installed client state.
 */
internal class RemoteClientLifetimeTest {
    @Test
    fun failedControlRequestsReleaseStateAndRejectLaterRequests() {
        ControlOperation.entries.forEach { operation ->
            val released = mutableListOf<Long>()
            val outgoing = mutableListOf<RemoteMessage>()
            val failure = RemoteProtocolException(RemoteFailure.ResourceLimit, "Control queue is full.")
            val registry = RemoteRegistry()
            val key = RemoteStateKey(Owned::class)
            registry.element(TYPE, { it }, { _, context ->
                context.states.prepare(context.identity, key, { Owned(context.identity) }, {}, { released.add(it.identity) })
            }) { _, context -> evaluateComponentTree { Spacer(context.modifier, context.key) } }
            val client =
                RemoteClientSession(RemoteMessage.Snapshot(1, 1, ProjectionValue.Absent, tree(listOf(node(1, 1)))), registry, send = {
                    if (it is RemoteMessage.ControlRequest) throw failure
                    outgoing.add(it)
                })
            try {
                assertSame(failure, assertThrows(RemoteProtocolException::class.java) { operation.request(client.uiSession) })
                assertEquals(RemoteSessionStatus.Closed(RemoteFailure.ResourceLimit), client.status)
                assertEquals(UiSessionStatus.Closed(RemoteFailure.ResourceLimit.uiReason), client.uiSession.status)
                assertEquals(0, client.nodeCount)
                assertEquals(listOf(1L), released)
                assertEquals(listOf(RemoteMessage.Applied(1, 1), RemoteMessage.Close(1, RemoteFailure.ResourceLimit)), outgoing)
                assertEquals(UiOperationResult.Rejected(UiRejection.Closed), operation.request(client.uiSession))
            } finally {
                client.close()
            }
            assertEquals(listOf(1L), released)
            assertEquals(2, outgoing.size)
        }
    }

    @Test
    fun failedControlRequestRemainsPrimaryWhenReleaseAndCloseNotificationAlsoFail() {
        val failure = IllegalStateException("Control send failed.")
        val cleanup = IllegalStateException("Extension release failed.")
        val notification = IllegalStateException("Close send failed.")
        var releases = 0
        var notifications = 0
        val registry = RemoteRegistry()
        val key = RemoteStateKey(Owned::class)
        registry.element(TYPE, { it }, { _, context ->
            context.states.prepare(context.identity, key, { Owned(context.identity) }, {}, {
                releases++
                throw cleanup
            })
        }) { _, context -> evaluateComponentTree { Spacer(context.modifier, context.key) } }
        val client =
            RemoteClientSession(RemoteMessage.Snapshot(1, 1, ProjectionValue.Absent, tree(listOf(node(1, 1)))), registry, send = {
                val sendFailure =
                    when (it) {
                        is RemoteMessage.ControlRequest -> failure
                        is RemoteMessage.Close -> notification.also { notifications++ }
                        else -> null
                    }
                sendFailure?.let { throw it }
            })
        try {
            assertSame(failure, assertThrows(IllegalStateException::class.java) { client.uiSession.switch(UiPresentation.Hud) })
            assertEquals(RemoteSessionStatus.Closed(RemoteFailure.InvalidMessage), client.status)
            assertEquals(listOf(cleanup), failure.suppressed.toList())
            assertEquals(listOf(notification), cleanup.suppressed.toList())
            assertEquals(0, client.nodeCount)
        } finally {
            client.close()
        }
        assertEquals(1, releases)
        assertEquals(1, notifications)
    }

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

    private enum class ControlOperation {
        Presentation,
        InputPolicy,
        Interaction,
        ;

        fun request(session: UiSession): UiOperationResult =
            when (this) {
                Presentation -> session.switch(UiPresentation.Hud)
                InputPolicy -> session.setInputPolicy(UiInputPolicy.All)
                Interaction -> session.setInteractionMode(UiInteractionMode.Cursor)
            }
    }

    private companion object {
        val TYPE = ProjectionType(ResourceId("test", "owned"))
    }
}
