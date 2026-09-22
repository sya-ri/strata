@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.examples.velocity

import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.projection.BuiltinProjection
import dev.s7a.strata.projection.ProjectionInputCodec
import dev.s7a.strata.projection.ProjectionValue
import dev.s7a.strata.runtime.remote.RemoteBuiltins
import dev.s7a.strata.runtime.remote.RemoteComponentRuntime
import dev.s7a.strata.runtime.remote.RemoteMessage
import dev.s7a.strata.runtime.remote.RemoteRegistry
import dev.s7a.strata.runtime.remote.RemoteServerSession
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Compiles and exercises the shipped external-plugin example through public remote APIs only.
 */
internal class VelocityDemoScreensTest {
    @Test
    fun counterProjectsAndUpdatesWithoutLoadingMinecraftOrVelocity() {
        val definition = VelocityDemoScreens.counter().transfer()
        val registry = RemoteRegistry().also(RemoteBuiltins::register)
        val runtime = RemoteComponentRuntime()
        val messages = mutableListOf<RemoteMessage>()
        RemoteServerSession(1, ProjectionValue.Text("Example"), registry.types, send = messages::add) { runtime.evaluate(definition.content) }.use { server ->
            server.tick()
            val snapshot = messages.single() as RemoteMessage.Snapshot
            val press =
                snapshot.tree.nodes.values
                    .flatMap { it.modifiers }
                    .single { it.type == BuiltinProjection.PointerPress.type }
            server.receive(RemoteMessage.Action(1, 1, (press.value.let { (it as ProjectionValue.Sequence).values[1] as ProjectionValue.Integer }).value, press.type, ProjectionInputCodec.pointer(PointerEvent.Press(IntOffset.Zero, PointerButton.Primary), IntOffset.Zero)))
            server.tick()
            assertTrue(messages.any { it is RemoteMessage.Update })
        }
    }
}
