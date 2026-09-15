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
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Exercises actual native DOM updates through the shared reactive session.
 */
internal class WebUiHostTest {
    @Test
    fun conditionalTextUpdatesExistingDomAndSerializesAsEscapedHtml() {
        val root = document.createElement("div") as HTMLElement
        checkNotNull(document.body).appendChild(root)
        val changed = mutableStateOf(false)
        val viewport = IntSize(320, 100)
        val host =
            mountWeb(
                ScreenDefinition("Web") {
                    Stack { Text(if (changed.value) "<script>changed</script>" else "Initial") }
                },
                root,
                viewport,
            )
        try {
            val initial = checkNotNull(root.firstElementChild)
            assertEquals("Initial", initial.textContent)
            changed.value = true
            host.render(viewport)
            assertSame(initial, root.firstElementChild)
            assertEquals("<script>changed</script>", initial.textContent)
            assertTrue(root.innerHTML.contains("&lt;script&gt;"))
            assertEquals(0, root.querySelectorAll("script").length)
        } finally {
            host.close()
            assertFalse(root.hasChildNodes())
            root.parentNode?.removeChild(root)
        }
        changed.value = false
    }
}
