@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.remote

import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementType
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.Node
import dev.s7a.strata.projection.BuiltinProjection
import dev.s7a.strata.projection.DeclarationProjection
import dev.s7a.strata.projection.ProjectionAction
import dev.s7a.strata.projection.ProjectionType
import dev.s7a.strata.projection.ProjectionValue
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.mutableStateOf
import dev.s7a.strata.ui.UiPresentation
import dev.s7a.strata.ui.UiSessionStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Real unchanged wire trees still refresh opaque endpoint handlers, sequencing and independent control acknowledgements.
 */
internal class RemoteDeclarationReuseTest {
    @Test
    fun equalWireOutputStillInstallsTheCurrentOpaqueActionHandler() {
        var generation = 0
        val received = mutableListOf<Pair<Int, Long>>()
        val projection = DeclarationProjection(OPAQUE, Unit) { _, scope ->
            val captured = generation
            ProjectionValue.Integer(scope.action(ProjectionAction(EVENT, { value -> (value as ProjectionValue.Integer).value }) { value -> received.add(captured to value) }))
        }
        val messages = mutableListOf<RemoteMessage>()
        val root = OpaqueElement(projection)
        RemoteServerSession(1, ProjectionValue.Absent, setOf(OPAQUE, EVENT), send = messages::add) { root }.use { server ->
            server.tick()
            val snapshot = messages.single() as RemoteMessage.Snapshot
            val endpoint = (snapshot.tree.nodes.values.single().declaration.value as ProjectionValue.Integer).value
            val tree = previous(server)
            messages.clear()
            generation = 1
            server.tick()
            assertTrue(messages.isEmpty())
            assertSame(tree, previous(server))
            server.receive(RemoteMessage.Action(1, 1, endpoint, EVENT, ProjectionValue.Integer(7)))
            server.tick()
            assertEquals(listOf(1 to 7L), received)
            assertEquals(RemoteMessage.Acknowledgement(1, 1, snapshot.revision), messages.single())
            server.receive(RemoteMessage.Action(1, 1, endpoint, EVENT, ProjectionValue.Integer(7)))
            assertEquals(1, received.size)
        }
    }

    @Test
    fun endpointRemovalThenFixedTreeReuseStillAcknowledgesQueuedObsoleteInput() {
        val visible = mutableStateOf(true)
        var actions = 0
        val projection = DeclarationProjection(OPAQUE, Unit) { _, scope ->
            ProjectionValue.Integer(scope.action(ProjectionAction(EVENT, { Unit }) { actions++ }))
        }
        val root = OpaqueElement(projection)
        val messages = mutableListOf<RemoteMessage>()
        RemoteServerSession(1, ProjectionValue.Absent, setOf(OPAQUE, EVENT, BuiltinProjection.Spacer.type), send = messages::add) {
            if (visible.value) root else evaluateComponentTree { Spacer() }
        }.use { server ->
            server.tick()
            val snapshot = messages.single() as RemoteMessage.Snapshot
            val endpoint = (snapshot.tree.nodes.values.single().declaration.value as ProjectionValue.Integer).value
            messages.clear()
            visible.value = false
            server.tick()
            val replacement = messages.single() as RemoteMessage.Update
            val current = previous(server)
            messages.clear()
            server.tick()
            assertSame(current, previous(server))
            assertTrue(messages.isEmpty())
            server.receive(RemoteMessage.Action(1, 1, endpoint, EVENT, ProjectionValue.Absent))
            server.tick()
            assertEquals(0, actions)
            assertEquals(RemoteMessage.Acknowledgement(1, 1, replacement.revision), messages.single())
            server.receive(RemoteMessage.Applied(1, replacement.revision))
            server.receive(RemoteMessage.Applied(1, snapshot.revision))
            assertEquals(replacement.revision, server.appliedRevision)
        }
    }

    @Test
    fun cachedFixedDeclarationsKeepIndependentControlAndApplicationSequences() {
        val messages = mutableListOf<RemoteMessage>()
        RemoteServerSession(1, ProjectionValue.Absent, setOf(BuiltinProjection.Spacer.type), send = messages::add) { evaluateComponentTree { Spacer() } }.use { server ->
            server.tick()
            val snapshot = messages.single() as RemoteMessage.Snapshot
            server.receive(RemoteMessage.ControlApplied(1, requireNotNull(snapshot.control).sequence))
            val current = previous(server)
            repeat(4) { index ->
                messages.clear()
                val presentation = if (index % 2 == 0) UiPresentation.Hud else UiPresentation.Screen
                server.uiSession.switch(presentation)
                val control = messages.single() as RemoteMessage.Control
                server.receive(RemoteMessage.ControlApplied(1, control.state.sequence))
                server.tick()
                assertSame(current, previous(server))
                assertEquals(presentation, server.uiSession.presentation)
                assertEquals(UiSessionStatus.Ready(), server.uiSession.status)
                assertEquals(1, messages.size)
            }
        }
    }

    @Test
    fun sendFailureClearsThePrimitiveProjectionKeyBeforeTerminalNotification() {
        val primary = IllegalArgumentException("snapshot transport failed")
        var closes = 0
        lateinit var server: RemoteServerSession
        server = RemoteServerSession(1, ProjectionValue.Absent, setOf(BuiltinProjection.Spacer.type), send = { message ->
            when (message) {
                is RemoteMessage.Snapshot -> throw primary
                is RemoteMessage.Close -> {
                    closes++
                    assertEquals(0L, server.javaClass.getDeclaredField("projectedDeclarationRevision").apply { isAccessible = true }.getLong(server))
                    assertEquals(0, server.nodeCount)
                    assertTrue(server.status is RemoteSessionStatus.Closed)
                }
                else -> Unit
            }
        }) { evaluateComponentTree { Spacer() } }
        assertSame(primary, assertThrows(IllegalArgumentException::class.java, server::tick))
        server.close()
        server.close()
        assertEquals(1, closes)
    }

    private fun previous(server: RemoteServerSession): RemoteTree = server.javaClass.getDeclaredField("previous").apply { isAccessible = true }.get(server) as RemoteTree

    /**
     * Purpose-specific consumer declaration using only the existing optional Element/Node SPI.
     */
    private class OpaqueElement(override val projection: DeclarationProjection<*>) : Element(ElementIdentity.Positional, TYPE) {
        private class OpaqueNode : Node()
        private companion object {
            val TYPE = ElementType(OpaqueElement::class, OpaqueNode::class, { _ -> }, { _ -> OpaqueNode() }, { _, _, _ -> DirtyMask.None })
        }
    }

    private companion object {
        val OPAQUE = ProjectionType(ResourceId("test", "opaque_projection"))
        val EVENT = ProjectionType(ResourceId("test", "opaque_action"))
    }
}
