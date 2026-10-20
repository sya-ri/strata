package dev.s7a.strata.runtime.web

import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.Text
import dev.s7a.strata.component.TextArea
import dev.s7a.strata.component.TextAreaState
import dev.s7a.strata.component.TextAreaViewport
import dev.s7a.strata.component.TextField
import dev.s7a.strata.component.TextFieldState
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.TextInputEvent
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.onPress
import dev.s7a.strata.modifier.onTextInput
import dev.s7a.strata.modifier.scaleToFit
import dev.s7a.strata.modifier.size
import dev.s7a.strata.state.mutableStateOf
import dev.s7a.strata.ui.UiDefinition
import dev.s7a.strata.ui.UiInputPolicy
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLTextAreaElement
import org.w3c.dom.events.Event
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame

/**
 * Exercises native editing, composition retention and exclusive state release in a real browser.
 */
internal class WebEditorTest {
    @Test
    fun nativeFocusAndConsumedEditingDoNotActivateAncestorActions() {
        val root = document.createElement("div") as HTMLElement
        checkNotNull(document.body).appendChild(root)
        val state = TextFieldState("retained")
        var pressed = 0
        val host =
            mountWeb(
                UiDefinition {
                    Stack(Modifier.onPress { pressed += 1 }) {
                        TextField(
                            state,
                            IntSize(180, 30),
                            modifier =
                                Modifier.onTextInput {
                                    if (it is TextInputEvent.Character) InputResult.Consumed else InputResult.Ignored
                                },
                        )
                    }
                },
                root,
                IntSize(200, 60),
            )
        try {
            val input = root.querySelector("input") as HTMLInputElement
            input.focus()
            window.dispatchEvent(Event("blur"))
            window.dispatchEvent(Event("focus"))
            assertEquals(0, pressed)
            val before = js("new InputEvent('beforeinput', {data:'x', inputType:'insertText', bubbles:true, cancelable:true})") as Event
            assertFalse(input.dispatchEvent(before))
            assertEquals("retained", state.value)
        } finally {
            host.close()
            root.remove()
        }
    }

    @Test
    fun replacingAnEarlierSiblingDoesNotReinsertTheComposingEditor() {
        val root = document.createElement("div") as HTMLElement
        checkNotNull(document.body).appendChild(root)
        val shown = mutableStateOf(true)
        val state = TextFieldState("original")
        val host =
            mountWeb(
                UiDefinition {
                    Column {
                        if (shown.value) Text("Before") else Spacer(Modifier.size(2, 24))
                        TextField(state, IntSize(160, 30), key = ElementKey("retained"))
                    }
                },
                root,
                IntSize(200, 100),
            )
        try {
            val input = root.querySelector("input") as HTMLInputElement
            input.focus()
            var blurred = 0
            input.addEventListener("blur", { blurred += 1 })
            input.dispatchEvent(Event("compositionstart", js("({bubbles: true})")))
            input.value = "変換中"
            shown.value = false
            host.render(IntSize(200, 100))
            assertEquals(0, blurred)
            assertSame(input, root.querySelector("input"))
            assertSame(input, document.activeElement)
            assertEquals("変換中", input.value)
        } finally {
            host.close()
            root.remove()
        }
    }

    @Test
    fun scaledNativeEditorsKeepIdentityFocusAndSelectionDuringResize() {
        val root = document.createElement("div") as HTMLElement
        checkNotNull(document.body).appendChild(root)
        val state = TextFieldState("scaled")
        val host =
            mountWeb(
                UiDefinition {
                    Column(Modifier.scaleToFit(IntSize(100, 80), allowUpscaling = true)) {
                        Text("Scaled")
                        TextField(state, IntSize(100, 20), key = ElementKey("scaled-editor"))
                    }
                },
                root,
                IntSize(200, 160),
            )
        try {
            val input = root.querySelector("input") as HTMLInputElement
            input.focus()
            input.setSelectionRange(1, 3)
            assertEquals(200.0, input.getBoundingClientRect().width)
            host.render(IntSize(300, 240))
            assertSame(input, root.querySelector("input"))
            assertSame(input, document.activeElement)
            assertEquals(1, input.selectionStart)
            assertEquals(3, input.selectionEnd)
            assertEquals(300.0, input.getBoundingClientRect().width)
        } finally {
            host.close()
            root.remove()
        }
    }

    @Test
    fun retainedEditorKeepsSelectionCompositionAndFocusAcrossUpdatesAndResize() {
        val root = document.createElement("div") as HTMLElement
        checkNotNull(document.body).appendChild(root)
        val field = TextFieldState("initial", 100)
        val revision = mutableStateOf(0)
        val host =
            mountWeb(
                UiDefinition(inputPolicy = UiInputPolicy.Movement) {
                    Column {
                        Text("Revision ${revision.value}")
                        TextField(field, IntSize(160, 30), key = ElementKey("editor"))
                    }
                },
                root,
                IntSize(200, 100),
            )
        try {
            val input = root.querySelector("input") as HTMLInputElement
            input.focus()
            input.value = "日本語"
            input.dispatchEvent(Event("input", js("({bubbles: true})")))
            assertEquals("日本語", field.value)
            input.setSelectionRange(1, 2)
            input.dispatchEvent(Event("compositionstart", js("({bubbles: true})")))
            input.value = "日ほん語"
            revision.value += 1
            host.render(IntSize(240, 120))
            assertSame(input, root.querySelector("input"))
            assertSame(input, document.activeElement)
            assertEquals("日ほん語", input.value)
            assertEquals("日本語", field.value)
            input.value = "日本語"
            input.dispatchEvent(Event("compositionend", js("({bubbles: true})")))
            assertEquals("日本語", field.value)
            input.setSelectionRange(1, 2)
            revision.value += 1
            host.render(IntSize(260, 120))
            assertEquals(1, input.selectionStart)
            assertEquals(2, input.selectionEnd)
        } finally {
            host.close()
            assertFalse(root.hasChildNodes())
            root.remove()
        }
        val next = mountWeb(UiDefinition { TextField(field, IntSize(160, 30)) }, root, IntSize(200, 100))
        next.close()
    }

    @Test
    fun nativeMultilineInputNormalizesPasteAndKeepsDomThroughExternalValueUpdates() {
        val root = document.createElement("div") as HTMLElement
        val area = TextAreaState(maxLength = 100)
        val host = mountWeb(UiDefinition { TextArea(area, TextAreaViewport.Size(IntSize(200, 80))) }, root, IntSize(220, 100))
        try {
            val input = root.querySelector("textarea") as HTMLTextAreaElement
            assertEquals("textbox", input.getAttribute("role"))
            assertEquals("true", input.getAttribute("aria-multiline"))
            assertEquals(1, root.querySelectorAll("[role='textbox']").length)
            input.value = "日本語\nsecond line"
            input.dispatchEvent(Event("input", js("({bubbles: true})")))
            assertEquals("日本語\nsecond line", area.value)
            area.value = "server update\n日本語"
            host.render(IntSize(220, 100))
            assertSame(input, root.querySelector("textarea"))
            assertEquals(area.value, input.value)
        } finally {
            host.close()
        }
    }
}
