@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.remote

import dev.s7a.strata.component.Canvas
import dev.s7a.strata.component.CanvasBinding
import dev.s7a.strata.component.CanvasSource
import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.size
import dev.s7a.strata.projection.ProjectionType
import dev.s7a.strata.projection.ProjectionValue
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.PaintScope
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.runtime.spi.createRuntimeUiSession
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.mutableStateOf
import dev.s7a.strata.text.UiText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/**
 * Tests the current-tree factory cache through real Canvas attachment ownership and visible output.
 * Unrelated updates, changed properties, removal, and terminal close have independent acquisition/release assertions.
 */
internal class RemoteCanvasLifetimeTest {
    @Test
    @Suppress("LongMethod") // One ordered lifecycle retains the same counters through reuse, replacement, removal, and close.
    fun installedRendererKeepsItsBindingAcrossUnrelatedUpdatesAndReleasesOnReplacementAndRemoval() {
        val type = ProjectionType(ResourceId("example", "native_canvas"))
        val registry = RemoteRegistry().also(RemoteBuiltins::register)
        var decoded = 0
        var opened = 0
        var closed = 0
        RemoteCanvas.register(registry, type, { value ->
            decoded++
            ArgbColor((value as ProjectionValue.Integer).value.toInt())
        }) { color ->
            CanvasSource {
                opened++
                object : CanvasBinding {
                    override fun paint(scope: PaintScope) {
                        scope.fillRectangle(IntRect(0, 0, scope.size.width, scope.size.height), color)
                    }

                    override fun close() {
                        closed++
                    }
                }
            }
        }
        val color = mutableStateOf(-65536)
        val background = mutableStateOf(-1)
        val visible = mutableStateOf(true)
        val messages = ArrayDeque<RemoteMessage>()
        RemoteServerSession(1, ProjectionValue.Absent, registry.types, send = messages::addLast) {
            evaluateComponentTree {
                Column {
                    if (visible.value) Canvas(RemoteCanvas.source(type, color.value) { ProjectionValue.Integer(it.toLong()) }, IntSize(10, 10))
                    Spacer(Modifier.Empty.size(10, 1).background(ArgbColor(background.value)))
                }
            }
        }.use { server ->
            server.tick()
            RemoteClientSession(messages.removeFirst() as RemoteMessage.Snapshot, registry, send = {}).use { client ->
                val content = client.definition(UiText.Literal("Canvas")).transfer().content
                createRuntimeUiSession { evaluateComponentTree(content) }.use { host ->
                    host.attach()
                    val first = host.frame(Constraints(maxWidth = 20, maxHeight = 20))
                    assertEquals(1, opened)
                    background.value = -16777216
                    server.tick()
                    client.receive(messages.removeFirst() as RemoteMessage.Update)
                    host.frame(Constraints(maxWidth = 20, maxHeight = 20))
                    assertEquals(1, decoded)
                    assertEquals(1, opened)
                    assertEquals(0, closed)
                    color.value = -16711936
                    server.tick()
                    client.receive(messages.removeFirst() as RemoteMessage.Update)
                    val changed = host.frame(Constraints(maxWidth = 20, maxHeight = 20))
                    assertEquals(2, opened)
                    assertEquals(1, closed)
                    assertNotEquals(first.drawCommands, changed.drawCommands)
                    assertEquals(
                        ArgbColor(color.value),
                        changed.drawCommands
                            .filterIsInstance<DrawCommand.FillRectangle>()
                            .first()
                            .color,
                    )
                    visible.value = false
                    server.tick()
                    client.receive(messages.removeFirst() as RemoteMessage.Update)
                    host.frame(Constraints(maxWidth = 20, maxHeight = 20))
                    assertEquals(2, closed)
                    visible.value = true
                    server.tick()
                    client.receive(messages.removeFirst() as RemoteMessage.Update)
                    host.frame(Constraints(maxWidth = 20, maxHeight = 20))
                    assertEquals(3, decoded)
                    assertEquals(3, opened)
                    assertEquals(2, closed)
                }
            }
        }
        assertEquals(opened, closed)
    }
}
