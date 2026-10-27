@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.web

import dev.s7a.strata.component.Button
import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Image
import dev.s7a.strata.component.ImageSource
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.Text
import dev.s7a.strata.component.TextField
import dev.s7a.strata.component.TextFieldState
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.onActivate
import dev.s7a.strata.modifier.size
import dev.s7a.strata.modifier.tooltip
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.FrameTime
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.runtime.spi.createRuntimeUiSession
import dev.s7a.strata.spi.ComponentRuntimeBridge
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.mutableStateOf
import kotlinx.browser.document
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Verifies real DOM overlay ordering, live text and native input suppression.
 */
internal class WebTooltipTest {
    @Test
    fun decorationKeepsOpaqueContentInsideItsFrame() {
        for (decorated in listOf(true, false)) {
            val root = document.createElement("div") as HTMLElement
            document.body?.appendChild(root)
            val runtime = WebComponentRuntime()
            val renderer = WebDomRenderer(root, resources = runtime.resources)
            val session =
                createRuntimeUiSession {
                    ComponentRuntimeBridge.evaluate(runtime) {
                        Stack(Modifier.size(80, 60)) {
                            Spacer(
                                Modifier.size(4, 4).tooltip(delayMillis = 0, decorated = decorated) {
                                    Image(ImageSource.Pixels(createDrawImage(IntSize(1, 1), intArrayOf(-65536))), size = IntSize(8, 6))
                                },
                            )
                        }
                    }
                }
            try {
                session.attach()
                val constraints = Constraints.fixed(80, 60)
                session.frame(constraints)
                session.dispatchPointer(PointerEvent.Move(IntOffset(1, 1)))
                val frame = session.frame(constraints)
                renderer.render(frame)
                val image = root.querySelector("canvas") as HTMLElement
                val inset = if (decorated) 3 else 0
                assertEquals("${inset}px", image.style.left)
                assertEquals("${8 + inset}px", image.style.top)
                val edges =
                    frame.drawCommands.mapIndexedNotNull { index, command ->
                        if (command is DrawCommand.FillRectangle && command.color == ArgbColor(0xFF808080.toInt())) {
                            root.querySelector("[data-strata-node='d$index']") as HTMLElement
                        } else {
                            null
                        }
                    }
                assertEquals(if (decorated) 4 else 0, edges.size)
                val contentBounds = image.getBoundingClientRect()
                edges.forEach { edge ->
                    val bounds = edge.getBoundingClientRect()
                    val horizontal = bounds.right <= contentBounds.left || contentBounds.right <= bounds.left
                    val vertical = bounds.bottom <= contentBounds.top || contentBounds.bottom <= bounds.top
                    assertTrue(horizontal || vertical)
                }
            } finally {
                session.close()
                renderer.close()
                root.remove()
            }
        }
    }

    @Test
    fun componentTooltipUpdatesItsDomWithoutCreatingInteractiveWidgets() {
        val root = document.createElement("div") as HTMLElement
        document.body?.appendChild(root)
        val runtime = WebComponentRuntime()
        val renderer = WebDomRenderer(root, resources = runtime.resources)
        val label = mutableStateOf("Title")
        val editorState = TextFieldState()
        var activations = 0
        val session =
            createRuntimeUiSession {
                ComponentRuntimeBridge.evaluate(runtime) {
                    Stack(Modifier.size(160, 100)) {
                        Spacer(
                            Modifier.size(20, 20).tooltip(delayMillis = 0, decorated = false) {
                                Column {
                                    Text(label.value)
                                    Button("Passive", width = 60, modifier = Modifier.onActivate { activations += 1 })
                                    TextField(editorState, size = IntSize(60, 20))
                                }
                            },
                        )
                    }
                }
            }
        try {
            session.attach()
            val constraints = Constraints.fixed(160, 100)
            renderer.render(session.frame(constraints))
            assertEquals("", root.textContent)
            session.dispatchPointer(PointerEvent.Move(IntOffset(1, 1)))
            renderer.render(session.frame(constraints))
            assertTrue(root.textContent.orEmpty().contains("Title"))
            val button = root.querySelector("button") as HTMLButtonElement
            val editor = root.querySelector("input") as HTMLInputElement
            assertEquals("none", button.style.getPropertyValue("pointer-events"))
            assertEquals(-1, button.tabIndex)
            assertTrue(button.asDynamic().inert as Boolean)
            assertTrue(editor.asDynamic().inert as Boolean)
            button.focus()
            assertNotEquals(button, document.activeElement)
            editor.focus()
            assertNotEquals(editor, document.activeElement)
            session.dispatchPointer(PointerEvent.Press(IntOffset(1, 45), PointerButton.Primary))
            assertEquals(0, activations)
            label.value = "Updated"
            renderer.render(session.frame(constraints))
            assertTrue(root.textContent.orEmpty().contains("Updated"))
            session.resetInputState()
            renderer.render(session.frame(constraints))
            assertFalse(root.textContent.orEmpty().contains("Updated"))
        } finally {
            session.close()
            renderer.close()
            root.remove()
        }
    }

    @Test
    fun stringTooltipHonorsItsDelayAndOptionalDecoration() {
        for (decorated in listOf(true, false)) {
            val root = document.createElement("div") as HTMLElement
            document.body?.appendChild(root)
            val runtime = WebComponentRuntime()
            val renderer = WebDomRenderer(root, resources = runtime.resources)
            val session =
                createRuntimeUiSession {
                    ComponentRuntimeBridge.evaluate(runtime) {
                        Spacer(Modifier.size(80, 60).tooltip("Tip", decorated = decorated))
                    }
                }
            try {
                session.attach()
                val constraints = Constraints.fixed(80, 60)
                session.frame(constraints, FrameTime(0))
                session.dispatchPointer(PointerEvent.Move(IntOffset(1, 1)))
                renderer.render(session.frame(constraints, FrameTime(0)))
                assertEquals("", root.textContent)
                renderer.render(session.frame(constraints, FrameTime(500_000_000)))
                val text = root.querySelector("[data-strata-node^='n']") as HTMLElement
                assertEquals("Tip", text.textContent)
                assertEquals("hidden", text.style.getPropertyValue("overflow"))
                assertEquals(if (decorated) "3px" else "0px", text.style.left)
            } finally {
                session.close()
                renderer.close()
                root.remove()
            }
        }
    }
}
