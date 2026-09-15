package dev.s7a.strata.runtime.web

import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.Text
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.screen.ScreenDefinition
import org.w3c.dom.HTMLElement
import org.w3c.dom.parsing.DOMParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Verifies standalone initial documents preserve content, loading order, and the geometry needed for adoption.
 */
internal class WebDocumentTest {
    @Test
    fun documentContainsEscapedInitialContentAndDeferredApplication() {
        val title = "</title><script>unexpected()</script>"
        val scriptUrl = "./app.js?value=\"quoted\"&other=1"
        val html = renderWebDocument(definition(), IntSize(320, 100), title, scriptUrl)
        assertTrue(html.startsWith("<!doctype html>"))
        val parsed = DOMParser().parseFromString(html, "text/html")
        assertEquals(title, parsed.title)
        assertEquals(1, parsed.querySelectorAll("script").length)
        val script = assertNotNull(parsed.querySelector("script"))
        assertEquals(scriptUrl, script.getAttribute("src"))
        assertTrue(script.hasAttribute("defer"))
        val root = assertNotNull(parsed.getElementById("strata-root")) as HTMLElement
        assertEquals("relative", root.style.position)
        assertEquals("320px", root.style.width)
        assertEquals("100px", root.style.height)
        assertEquals("<initial>", root.textContent)
        val initialNode = assertNotNull(root.firstElementChild)
        mountWeb(definition(), root, IntSize(320, 100)).use {
            assertTrue(root.firstElementChild === initialNode)
        }
    }

    @Test
    fun blankScriptUrlFailsBeforeConsumingTheDefinition() {
        val definition = definition()
        assertFailsWith<IllegalArgumentException> {
            renderWebDocument(definition, IntSize(320, 100), "Title", " ")
        }
        assertTrue(renderWebHtml(definition, IntSize(320, 100)).contains("&lt;initial&gt;"))
    }

    private fun definition(): ScreenDefinition = ScreenDefinition("Document") { Stack { Text("<initial>") } }
}
