package dev.s7a.strata.runtime.web

import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Text
import dev.s7a.strata.component.TextArea
import dev.s7a.strata.component.TextAreaState
import dev.s7a.strata.component.TextAreaViewport
import dev.s7a.strata.component.TextField
import dev.s7a.strata.component.TextFieldState
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.state.mutableStateOf
import dev.s7a.strata.ui.UiDefinition
import dev.s7a.strata.ui.UiInputPolicy
import kotlinx.browser.document
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
