@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.examples.paper

import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.projection.BuiltinProjection
import dev.s7a.strata.projection.ProjectionInputCodec
import dev.s7a.strata.projection.ProjectionType
import dev.s7a.strata.projection.ProjectionValue
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.runtime.remote.RemoteBindingSnapshot
import dev.s7a.strata.runtime.remote.RemoteBuiltins
import dev.s7a.strata.runtime.remote.RemoteComponentRuntime
import dev.s7a.strata.runtime.remote.RemoteMessage
import dev.s7a.strata.runtime.remote.RemoteRegistry
import dev.s7a.strata.runtime.remote.RemoteServerSession
import dev.s7a.strata.runtime.remote.RemoteTextCodec
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.UiText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Compiles and exercises the shipped external-plugin example through public remote APIs only.
 */
internal class PaperDemoScreensTest {
    @Test
    fun counterProjectsAndUpdatesWithoutLoadingMinecraftOrPaper() {
        val definition = PaperDemoScreens.counter().transfer()
        val registry = RemoteRegistry().also(RemoteBuiltins::register)
        val runtime = RemoteComponentRuntime()
        val messages = mutableListOf<RemoteMessage>()
        val textType = ProjectionType(ResourceId("strata", "profile/text"), 2)
        val buttonType = ProjectionType(ResourceId("strata", "profile/button"))
        val fieldType = ProjectionType(ResourceId("strata", "profile/text_field"), 2)
        RemoteServerSession(1, ProjectionValue.Text("Example"), registry.types, send = messages::add) { runtime.evaluate(definition.content) }.use { server ->
            server.tick()
            var tree = (messages.single() as RemoteMessage.Snapshot).tree

            fun applyUpdates() {
                server.tick()
                messages.filterIsInstance<RemoteMessage.Update>().forEach { tree = it.patch.apply(tree) }
                messages.clear()
            }

            fun label(type: ProjectionType): UiText {
                val node = tree.nodes.values.single { it.declaration.type == type }
                return RemoteTextCodec.decode((node.declaration.value as ProjectionValue.Sequence).values.first())
            }
            assertEquals(UiText.Literal("Count: 0"), label(buttonType))
            messages.clear()
            val field = tree.nodes.values.single { it.declaration.type == fieldType }
            val binding = RemoteBindingSnapshot.decode((field.declaration.value as ProjectionValue.Sequence).values.first())
            server.receive(RemoteMessage.Action(1, 1, binding.endpoint, fieldType, binding.edit(ProjectionValue.Text("Alex"))))
            applyUpdates()
            repeat(2) { index ->
                val press =
                    tree.nodes.values
                        .flatMap { it.modifiers }
                        .single { it.type == BuiltinProjection.PointerPress.type }
                val endpoint = ((press.value as ProjectionValue.Sequence).values[1] as ProjectionValue.Integer).value
                server.receive(RemoteMessage.Action(1, index.toLong() + 2, endpoint, press.type, ProjectionInputCodec.pointer(PointerEvent.Press(IntOffset.Zero, PointerButton.Primary), IntOffset.Zero)))
                applyUpdates()
                assertEquals(UiText.Literal("Count: ${index + 1}"), label(buttonType))
                assertEquals(UiText.Literal("Hello, Alex."), label(textType))
            }
        }
    }
}
