package dev.s7a.strata.runtime.web

import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.Text
import dev.s7a.strata.component.TextArea
import dev.s7a.strata.component.TextAreaState
import dev.s7a.strata.component.TextAreaViewport
import dev.s7a.strata.component.TextField
import dev.s7a.strata.component.TextFieldState
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.onActivate
import dev.s7a.strata.modifier.semantics
import dev.s7a.strata.modifier.size
import dev.s7a.strata.semantics.Semantics
import dev.s7a.strata.semantics.SemanticsRole
import dev.s7a.strata.state.mutableStateOf
import dev.s7a.strata.text.UiText
import dev.s7a.strata.ui.UiDefinition
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLTextAreaElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.EventListener
import org.w3c.dom.events.KeyboardEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame

/**
 * Exercises public modifier composition through native accessibility, focus and paint order.
 */
internal class WebSemanticsTest {
    @Test
    fun pendingFrameCannotRestoreThePreviousOwnerDuringNativeEditorFocus() {
        val root = document.createElement("div") as HTMLElement
        document.body?.appendChild(root)
        val revision = mutableStateOf(0)
        val state = TextAreaState()
        val host =
            mountWeb(
                UiDefinition {
                    Column {
                        Text("Revision ${revision.value}")
                        Stack(Modifier.size(100, 24).semantics(Semantics(UiText.Literal("Action"), SemanticsRole.Button)).onActivate {}) { Text("Action") }
                        TextArea(state, TextAreaViewport.Size(IntSize(100, 30)))
                    }
                },
                root,
                IntSize(120, 120),
            )
        try {
            val button = root.querySelector("button") as HTMLButtonElement
            val editor = root.querySelector("textarea") as HTMLTextAreaElement
            button.focus()
            assertSame(button, document.activeElement)
            revision.value += 1
            editor.focus()
            assertSame(editor, document.activeElement)
            editor.value = "日本語の下書き"
            editor.dispatchEvent(Event("input", js("({bubbles: true})")))
            editor.setSelectionRange(1, 3)
            revision.value += 1
            host.render(IntSize(120, 120))
            assertEquals("日本語の下書き", state.value)
            assertEquals(state.value, editor.value)
            assertSame(editor, document.activeElement)
            assertEquals(1, editor.selectionStart)
            assertEquals(3, editor.selectionEnd)
        } finally {
            host.close()
            root.remove()
        }
    }

    @Test
    fun composedActionsExposeNamesAndRetainNativeFocusThroughKeyedReorder() {
        val root = document.createElement("div") as HTMLElement
        document.body?.appendChild(root)
        val reverse = mutableStateOf(false)
        val disabled = mutableStateOf(false)
        val revision = mutableStateOf(0)
        var actions = 0
        val host =
            mountWeb(
                UiDefinition {
                    Column {
                        Text("Revision ${revision.value}")
                        for (id in if (reverse.value) listOf("Second", "First") else listOf("First", "Second")) {
                            Stack(Modifier.size(80, 24).semantics(Semantics(UiText.Literal(id), SemanticsRole.Button, disabled = disabled.value)).onActivate(enabled = disabled.value.not()) { actions += 1 }, key = ElementKey(id)) {
                                Text(id)
                            }
                        }
                    }
                },
                root,
                IntSize(120, 100),
            )
        try {
            val first = root.querySelector("button[aria-label='First']") as HTMLButtonElement
            assertEquals(0, first.tabIndex)
            first.focus()
            assertSame(first, document.activeElement)
            var focusLosses = 0
            first.addEventListener("focusout", EventListener { focusLosses += 1 })
            revision.value += 1
            host.render(IntSize(120, 100))
            assertEquals(0, focusLosses)
            assertSame(first, document.activeElement)
            assertEquals("none", window.getComputedStyle(first).getPropertyValue("outline-style"))
            first.click()
            assertEquals(1, actions)
            first.dispatchEvent(KeyboardEvent("keydown", js("({code: 'Tab', bubbles: true, cancelable: true})")))
            val second = root.querySelector("button[aria-label='Second']") as HTMLButtonElement
            assertSame(second, document.activeElement)
            second.dispatchEvent(KeyboardEvent("keydown", js("({code: 'Enter', bubbles: true, cancelable: true})")))
            second.dispatchEvent(KeyboardEvent("keyup", js("({code: 'Enter', bubbles: true, cancelable: true})")))
            assertEquals(2, actions)
            reverse.value = true
            host.render(IntSize(160, 100))
            assertSame(second, root.querySelector("button[aria-label='Second']"))
            assertSame(second, document.activeElement)
            disabled.value = true
            host.render(IntSize(160, 100))
            assertEquals(-1, first.tabIndex)
            assertEquals("true", first.getAttribute("aria-disabled"))
            first.click()
            assertEquals(2, actions)
        } finally {
            host.close()
            assertFalse(root.hasChildNodes())
            root.remove()
        }
    }

    @Test
    fun semanticSurfacesRespectNativeEditorPaintOrder() {
        val root = document.createElement("div") as HTMLElement
        document.body?.appendChild(root)
        val reverse = mutableStateOf(false)
        val state = TextFieldState()
        val host =
            mountWeb(
                UiDefinition {
                    Stack {
                        if (reverse.value) TextField(state, IntSize(100, 30), key = ElementKey("editor"))
                        Stack(Modifier.size(100, 30).semantics(Semantics(UiText.Literal("Surface"), SemanticsRole.Button)).onActivate {}, key = ElementKey("action")) { Text("Surface") }
                        if (reverse.value.not()) TextField(state, IntSize(100, 30), key = ElementKey("editor"))
                    }
                },
                root,
                IntSize(120, 80),
            )
        try {
            val bounds = root.getBoundingClientRect()
            assertSame(root.querySelector("input"), document.elementFromPoint(bounds.left + 10, bounds.top + 10))
            reverse.value = true
            host.render(IntSize(120, 80))
            assertSame(root.querySelector("button"), document.elementFromPoint(bounds.left + 10, bounds.top + 10))
        } finally {
            host.close()
            root.remove()
        }
    }
}
