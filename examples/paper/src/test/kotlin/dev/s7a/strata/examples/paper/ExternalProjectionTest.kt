@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.examples.paper

import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.projection.ProjectionValue
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.runtime.remote.RemoteBuiltins
import dev.s7a.strata.runtime.remote.RemoteClientSession
import dev.s7a.strata.runtime.remote.RemoteFailure
import dev.s7a.strata.runtime.remote.RemoteMessage
import dev.s7a.strata.runtime.remote.RemoteMessageCodec
import dev.s7a.strata.runtime.remote.RemoteProtocolException
import dev.s7a.strata.runtime.remote.RemoteRegistry
import dev.s7a.strata.runtime.remote.RemoteServerSession
import dev.s7a.strata.runtime.remote.RemoteSessionStatus
import dev.s7a.strata.runtime.spi.createRuntimeUiSession
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.UiText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Separate-module consumer verifying custom properties/events, missing capabilities, and registration-free local use.
 */
internal class ExternalProjectionTest {
    @Test
    fun customComponentAndModifierRoundTripThroughOnlyPublicContracts() {
        var calls = 0
        val element = DemoRemoteExtensions.marker(ArgbColor(-65536), DemoRemoteExtensions.activation { calls++ })
        val registry = RemoteRegistry().also(RemoteBuiltins::register).also(DemoRemoteExtensions::registerClient)
        val messages = mutableListOf<RemoteMessage>()
        val actions = mutableListOf<RemoteMessage>()
        val codec = RemoteMessageCodec()
        RemoteServerSession(1, ProjectionValue.Absent, registry.types, send = { messages.add(codec.decode(codec.encode(it))) }) { element }.use { server ->
            server.tick()
            RemoteClientSession(messages.single() as RemoteMessage.Snapshot, registry, send = { actions.add(codec.decode(codec.encode(it))) }).use { client ->
                val content = client.definition(UiText.Literal("Extension")).transfer().content
                createRuntimeUiSession { evaluateComponentTree(content) }.use { remote ->
                    remote.attach()
                    val actual = remote.frame(Constraints.fixed(20, 20))
                    createRuntimeUiSession { element }.use { local ->
                        local.attach()
                        assertEquals(local.frame(Constraints.fixed(20, 20)).drawCommands, actual.drawCommands)
                    }
                    remote.dispatchPointer(PointerEvent.Press(IntOffset(2, 2), PointerButton.Primary))
                    assertEquals(0, calls)
                    actions.forEach(server::receive)
                    assertEquals(1, calls)
                }
            }
        }
    }

    @Test
    fun absentClientFactoryRejectsTheEntireScreen() {
        val registry = RemoteRegistry().also(RemoteBuiltins::register)
        RemoteServerSession(1, ProjectionValue.Absent, registry.types, send = {}) { DemoRemoteExtensions.marker(ArgbColor(-1)) }.use { server ->
            assertEquals(RemoteFailure.UnsupportedType, assertThrows(RemoteProtocolException::class.java) { server.tick() }.reason)
            assertEquals(RemoteSessionStatus.Closed(RemoteFailure.UnsupportedType), server.status)
        }
    }
}
