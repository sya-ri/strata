package dev.s7a.strata.runtime.web

import dev.s7a.strata.component.Text
import dev.s7a.strata.component.TextShadow
import dev.s7a.strata.component.TextStyle
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.state.mutableStateOf
import dev.s7a.strata.ui.UiDefinition
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.parsing.DOMParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Checks Minecraft-compatible text colors in initial, adopted, and updated DOM for both themes.
 */
internal class WebTextStyleTest {
    @Test
    fun bothThemesRenderTheDeclaredStyleAndRetainTheAdoptedTextNode() {
        val viewport = IntSize(200, 100)
        for (theme in WebTheme.entries) {
            val style = mutableStateOf(TextStyle.Inactive.copy(shadow = null))

            fun definition(): UiDefinition = UiDefinition("Text styles") { Text("Guidance", style = style.value) }
            val root = document.createElement("div") as HTMLElement
            root.innerHTML = renderWebHtml(definition(), viewport, theme)
            val label = assertNotNull(root.firstElementChild) as HTMLElement
            assertEquals("rgb(160, 160, 160)", label.style.color)
            assertEquals("none", label.style.textShadow)
            assertNotNull(document.body).appendChild(root)
            try {
                mountWeb(definition(), root, viewport, theme).use { host ->
                    assertSame(label, root.firstElementChild)
                    assertEquals("rgb(41, 37, 34)", window.getComputedStyle(root).backgroundColor)
                    style.value = TextStyle(ArgbColor(0xff4080c0.toInt()), TextShadow.Color(ArgbColor(0xff206040.toInt())))
                    host.render(viewport)
                    assertSame(label, root.firstElementChild)
                    assertEquals("rgb(64, 128, 192)", label.style.color)
                    assertTrue(label.style.textShadow.contains("rgb(32, 96, 64)"))
                    assertTrue(label.style.textShadow.contains("1px 1px"))
                    style.value = TextStyle(ArgbColor(0x804080c0.toInt()))
                    host.render(viewport)
                    assertTrue(label.style.color.startsWith("rgba(64, 128, 192,"))
                    assertTrue(label.style.textShadow.contains("rgba(16, 32, 48,"))
                    style.value = style.value.copy(shadow = null)
                    host.render(viewport)
                    assertEquals("none", window.getComputedStyle(label).textShadow)
                }
            } finally {
                root.parentNode?.removeChild(root)
            }
        }
    }

    @Test
    fun standaloneNativeDocumentIncludesTheDarkDefaultBackgroundBeforeStartup() {
        val html = renderWebDocument(UiDefinition("Native") { Text("White text") }, IntSize(200, 100), "Native", "app.js")
        val parsed = DOMParser().parseFromString(html, "text/html")
        assertTrue(assertNotNull(parsed.head?.querySelector("style")).textContent.orEmpty().contains("#292522"))
        assertEquals("native", parsed.getElementById("strata-root")?.getAttribute("data-strata-theme-root"))
    }
}
