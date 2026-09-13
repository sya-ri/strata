package dev.s7a.strata.runtime.web

import dev.s7a.strata.component.Button
import dev.s7a.strata.component.Column
import dev.s7a.strata.component.ProgressBar
import dev.s7a.strata.component.Text
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.screen.ScreenDefinition
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLProgressElement
import org.w3c.dom.parsing.DOMParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Verifies themed initial content, native semantics, isolation, and stylesheet lifetime.
 */
internal class WebThemeTest {
    @Test
    fun minecraftThemeAdoptsNativeControlsAndReleasesItsStylesheet() {
        val initialStyles = document.querySelectorAll("style[data-strata-styles]").length
        val root = document.createElement("div") as HTMLElement
        root.innerHTML = renderWebHtml(definition(), viewport, WebTheme.Minecraft)
        assertEquals(initialStyles, document.querySelectorAll("style[data-strata-styles]").length)
        val button = assertNotNull(root.querySelector("button")) as HTMLButtonElement
        checkNotNull(document.body).appendChild(root)
        try {
            mountWeb(definition(), root, viewport, WebTheme.Minecraft).use {
                assertSame(button, root.querySelector("button"))
                assertEquals("0px", window.getComputedStyle(button).borderRadius)
                assertEquals("rgb(255, 255, 255)", window.getComputedStyle(button).color)
                assertTrue(window.getComputedStyle(button).fontFamily.contains("monospace"))
                assertTrue((root.querySelectorAll("button").item(1) as HTMLButtonElement).disabled)
                assertEquals(0.5, (root.querySelector("progress") as HTMLProgressElement).value)
                assertEquals(initialStyles + 1, document.querySelectorAll("style[data-strata-styles]").length)
            }
            assertEquals(initialStyles, document.querySelectorAll("style[data-strata-styles]").length)
            assertFalse(root.hasChildNodes())
            assertFalse(root.hasAttribute("data-strata-theme-root"))
        } finally {
            root.parentNode?.removeChild(root)
        }
    }

    @Test
    fun mismatchedThemePreservesInitialMarkup() {
        val root = document.createElement("div") as HTMLElement
        root.innerHTML = renderWebHtml(definition(), viewport)
        val initial = root.innerHTML
        assertFailsWith<IllegalStateException> { mountWeb(definition(), root, viewport, WebTheme.Minecraft) }
        assertEquals(initial, root.innerHTML)
    }

    @Test
    fun completeDocumentIncludesThemeBeforeApplicationStartup() {
        val html = renderWebDocument(definition(), viewport, "Menu", "app.js", WebTheme.Minecraft)
        val parsed = DOMParser().parseFromString(html, "text/html")
        assertTrue(assertNotNull(parsed.head?.querySelector("style")).textContent.orEmpty().contains("::-moz-progress-bar"))
        assertEquals("minecraft", parsed.getElementById("strata-root")?.getAttribute("data-strata-theme-root"))
        assertEquals("minecraft", parsed.querySelector("button")?.getAttribute("data-strata-theme"))
        assertTrue(assertNotNull(parsed.querySelector("script")).hasAttribute("defer"))
    }

    private fun definition(): ScreenDefinition =
        ScreenDefinition("Themed") {
            Column(spacing = 8) {
                Text("Menu")
                Button("Continue")
                Button("Unavailable", enabled = false)
                ProgressBar(0.5, IntSize(150, 12))
            }
        }

    private val viewport = IntSize(320, 180)
}
