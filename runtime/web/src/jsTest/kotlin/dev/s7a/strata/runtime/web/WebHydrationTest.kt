package dev.s7a.strata.runtime.web

import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.Text
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.state.mutableStateOf
import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * Verifies that serialized initial HTML is reused only when an independent screen factory reproduces its initial content.
 */
internal class WebHydrationTest {
    private val viewport = IntSize(320, 100)

    @Test
    fun serializedHtmlKeepsNativeIdentityWhenConnectedToFreshState() {
        val root = document.createElement("div") as HTMLElement
        root.innerHTML = initialHtml()
        val initial = checkNotNull(root.firstElementChild)
        val label = mutableStateOf("Initial")
        val host = mountWeb(ScreenDefinition("Hydrate") { Stack { Text(label.value) } }, root, viewport)
        try {
            assertSame(initial, root.firstElementChild)
            label.value = "Connected"
            host.render(viewport)
            assertSame(initial, root.firstElementChild)
            assertEquals("Connected", initial.textContent)
        } finally {
            host.close()
        }
    }

    @Test
    fun mismatchedInitialContentLeavesTheStaticHtmlIntact() {
        val root = document.createElement("div") as HTMLElement
        val html = initialHtml()
        root.innerHTML = html
        assertFailsWith<IllegalStateException> {
            mountWeb(ScreenDefinition("Mismatch") { Stack { Text("Different") } }, root, viewport)
        }
        assertEquals(html, root.innerHTML)
    }

    @Test
    fun matchingTextInsideUnexpectedMarkupIsRejectedWithoutMutation() {
        val root = document.createElement("div") as HTMLElement
        root.innerHTML = initialHtml()
        checkNotNull(root.firstElementChild).innerHTML = "<strong>Initial</strong>"
        val html = root.innerHTML
        assertFailsWith<IllegalStateException> {
            mountWeb(ScreenDefinition("Nested") { Stack { Text("Initial") } }, root, viewport)
        }
        assertEquals(html, root.innerHTML)
    }

    @Test
    fun formattingWhitespaceIsRemovedAfterSuccessfulValidation() {
        val root = document.createElement("div") as HTMLElement
        root.innerHTML = "\n  ${initialHtml()}\n"
        val initial = checkNotNull(root.firstElementChild)
        val host = mountWeb(ScreenDefinition("Whitespace") { Stack { Text("Initial") } }, root, viewport)
        try {
            assertEquals(1, root.childNodes.length)
            assertSame(initial, root.firstChild)
        } finally {
            host.close()
        }
    }

    private fun initialHtml(): String {
        val root = document.createElement("div") as HTMLElement
        val host = mountWeb(ScreenDefinition("Build") { Stack { Text("Initial") } }, root, viewport)
        return try {
            root.innerHTML
        } finally {
            host.close()
        }
    }
}
