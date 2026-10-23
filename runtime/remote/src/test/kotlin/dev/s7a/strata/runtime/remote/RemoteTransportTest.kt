@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.remote

import dev.s7a.strata.projection.ProjectionType
import dev.s7a.strata.projection.ProjectionValue
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.runtime.spi.RuntimeUiControl
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.ui.UiCategory
import dev.s7a.strata.ui.UiInputPolicy
import dev.s7a.strata.ui.UiInteractionMode
import dev.s7a.strata.ui.UiPresentation
import dev.s7a.strata.ui.UiRejection
import dev.s7a.strata.ui.UiScreenKind
import dev.s7a.strata.ui.UiVisibility
import dev.s7a.strata.ui.UiVisibilityPolicy
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.HexFormat

/**
 * Exercises exact message schemas and fragmented delivery without any game networking implementation.
 */
internal class RemoteTransportTest {
    private val type = ProjectionType(ResourceId("test", "node"), 7)

    @Test
    fun allMessageSchemasPreserveFieldsAndRoundTripCanonically() {
        val tree =
            RemoteTree(
                101,
                listOf(
                    RemoteNode(RemoteDeclaration(101, type, ProjectionValue.Text("hello")), listOf(RemoteDeclaration(303, type, ProjectionValue.Flag(true))), listOf(202)),
                    RemoteNode(RemoteDeclaration(202, type, ProjectionValue.Integer(73))),
                ),
            )
        val updated =
            RemoteTree(
                101,
                listOf(
                    RemoteNode(RemoteDeclaration(101, type, ProjectionValue.Text("changed")), listOf(RemoteDeclaration(304, type, ProjectionValue.Flag(false))), listOf(505)),
                    RemoteNode(RemoteDeclaration(505, type, ProjectionValue.Bytes(byteArrayOf(9, 8, 7)))),
                ),
            )
        val codec = RemoteMessageCodec()
        val input = UiInputPolicy(movement = true, attack = true, swapHands = true)
        val control = RuntimeUiControl(61, UiPresentation.Hud, input, UiInteractionMode.Look)
        val category = UiCategory(ResourceId("test", "category"))
        val settings =
            RemoteUiSettings(
                presentation = UiPresentation.Hud,
                category = category,
                inputPolicy = input,
                visibility = UiVisibilityPolicy(UiVisibility.KeepVisible, mapOf(category to UiVisibility.Close), mapOf(UiScreenKind.Chat to UiVisibility.Hide)),
                hudOrder = -13,
            )
        val limits = RemoteLimits(frameBytes = 128, messageBytes = 4096, valueDepth = 12, collectionEntries = 123, treeNodes = 27, pendingBytes = 8192, assemblyMillis = 2345, valueEntries = 456, reconstructionMillis = 321, hudSessions = 5)
        val messages =
            listOf(
                RemoteMessage.Hello(3, limits, setOf(type, ProjectionType(ResourceId("other", "extension"), 11))),
                RemoteMessage.Snapshot(17, 23, ProjectionValue.Text("Screen"), tree),
                RemoteMessage.Update(17, 23, 31, RemotePatch.between(tree, updated)),
                RemoteMessage.Action(17, 29, 43, type, ProjectionValue.Text("payload")),
                RemoteMessage.Acknowledgement(17, 29, 31),
                RemoteMessage.Applied(17, 31),
                RemoteMessage.Control(17, control),
                RemoteMessage.ControlRequest(17, control),
                RemoteMessage.ControlApplied(17, 61, UiRejection.Capacity),
                RemoteMessage.ControlReceipt(17, 61, UiRejection.Capacity),
                RemoteMessage.Snapshot(19, 37, ProjectionValue.Text("HUD"), updated, pausesGame = true, settings = settings, control = control),
                RemoteMessage.Resynchronize(17),
                RemoteMessage.Close(17, RemoteFailure.OwnerClosed),
            )
        messages.forEach { message ->
            val encoded = codec.encode(message)
            val decoded = codec.decode(encoded)
            assertFields(message, decoded, tree, updated)
            assertArrayEquals(encoded, codec.encode(decoded))
        }
    }

    private fun assertFields(
        message: RemoteMessage,
        decoded: RemoteMessage,
        tree: RemoteTree,
        updated: RemoteTree,
    ) {
        when (message) {
            is RemoteMessage.Hello -> {
                val hello = decoded as RemoteMessage.Hello
                assertEquals(message.protocol, hello.protocol)
                assertEquals(message.limits, hello.limits)
                assertEquals(message.types, hello.types)
            }

            is RemoteMessage.Update -> {
                val update = decoded as RemoteMessage.Update
                assertEquals(message.session, update.session)
                assertEquals(message.baseRevision, update.baseRevision)
                assertEquals(message.revision, update.revision)
                assertEquals(message.patch.root, update.patch.root)
                assertEquals(message.patch.changed, update.patch.changed)
                assertEquals(message.patch.removed, update.patch.removed)
                assertEquals(updated, update.patch.apply(tree))
            }

            else -> {
                assertEquals(message, decoded)
            }
        }
    }

    @Test
    fun actionRetainsTheIndependentProtocolOneWireFixture() {
        val expected = HexFormat.of().parseHex("060000000602000000000000000402000000000000001102000000000000001d02000000000000002b060000000304000000047465737404000000046e6f646502000000000000000704000000077061796c6f6164")
        val message = RemoteMessage.Action(17, 29, 43, type, ProjectionValue.Text("payload"))
        val codec = RemoteMessageCodec()
        assertArrayEquals(expected, codec.encode(message))
        assertEquals(message, codec.decode(expected))
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
