@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.remote

import dev.s7a.strata.projection.ProjectionType
import dev.s7a.strata.projection.ProjectionValue
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.runtime.spi.RuntimeUiControl
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.ui.UiInputPolicy
import dev.s7a.strata.ui.UiInteractionMode
import dev.s7a.strata.ui.UiPresentation
import dev.s7a.strata.ui.UiRejection
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Exercises exact message schemas and fragmented delivery without any game networking implementation.
 */
internal class RemoteTransportTest {
    private val type = ProjectionType(ResourceId("test", "node"))

    @Test
    fun allMessageSchemasRoundTripCanonically() {
        val node = RemoteNode(RemoteDeclaration(1, type, ProjectionValue.Text("hello")))
        val tree = RemoteTree(1, listOf(node))
        val codec = RemoteMessageCodec()
        val control = RuntimeUiControl(3, UiPresentation.Hud, UiInputPolicy.All, UiInteractionMode.Look)
        val messages =
            listOf(
                RemoteMessage.Hello(1, RemoteLimits(), setOf(type)),
                RemoteMessage.Snapshot(1, 1, ProjectionValue.Text("Screen"), tree),
                RemoteMessage.Update(1, 1, 2, RemotePatch.between(tree, tree)),
                RemoteMessage.Action(1, 1, 1, type, ProjectionValue.Absent),
                RemoteMessage.Acknowledgement(1, 1, 1),
                RemoteMessage.Applied(1, 1),
                RemoteMessage.Control(1, control),
                RemoteMessage.ControlRequest(1, control),
                RemoteMessage.ControlApplied(1, 3, UiRejection.Capacity),
                RemoteMessage.ControlReceipt(1, 3),
                RemoteMessage.Snapshot(1, 1, ProjectionValue.Text("HUD"), tree, settings = RemoteUiSettings(presentation = UiPresentation.Hud), control = control),
                RemoteMessage.Resynchronize(1),
                RemoteMessage.Close(1, RemoteFailure.OwnerClosed),
            )
        messages.forEach { message ->
            val encoded = codec.encode(message)
            assertArrayEquals(encoded, codec.encode(codec.decode(encoded)))
        }
    }

    @Test
    fun fragmentedPayloadCompletesExactlyOnce() {
        val limits = RemoteLimits(frameBytes = 64)
        val fragments = mutableListOf<ByteArray>()
        val message = ByteArray(251) { it.toByte() }
        RemoteFraming(limits).use { it.send(message, fragments::add) }
        assertEquals(6, fragments.size)
        RemoteFraming(limits).use { receiver ->
            fragments.dropLast(1).forEach { assertNull(receiver.receive(it, 0)) }
            assertArrayEquals(message, receiver.receive(fragments.last(), 1))
            assertThrows(IllegalArgumentException::class.java) { receiver.receive(fragments.first(), 2) }
        }
    }

    @Test
    fun incompleteOutOfOrderAndClosedTransfersFail() {
        val limits = RemoteLimits(frameBytes = 64, assemblyMillis = 10)
        val fragments = mutableListOf<ByteArray>()
        RemoteFraming(limits).use { it.send(ByteArray(80), fragments::add) }
        RemoteFraming(limits).use { receiver ->
            assertThrows(IllegalArgumentException::class.java) { receiver.receive(fragments.last(), 0) }
            assertNull(receiver.receive(fragments.first(), 1))
            assertThrows(IllegalArgumentException::class.java) { receiver.expire(11) }
            receiver.close()
            assertThrows(IllegalStateException::class.java) { receiver.receive(fragments.first(), 12) }
        }
    }
}
