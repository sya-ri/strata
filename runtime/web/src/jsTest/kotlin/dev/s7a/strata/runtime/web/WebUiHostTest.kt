@file:Suppress("DEPRECATION") // Compatibility overloads and regression coverage retain the deprecated screen entry points.

package dev.s7a.strata.runtime.web

import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.Text
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.state.mutableStateOf
import dev.s7a.strata.ui.UiCloseReason
import dev.s7a.strata.ui.UiDefinition
import dev.s7a.strata.ui.UiInputPolicy
import dev.s7a.strata.ui.UiOperationResult
import dev.s7a.strata.ui.UiPresentation
import dev.s7a.strata.ui.UiRejection
import dev.s7a.strata.ui.UiSessionStatus
import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Exercises actual native DOM updates through the shared reactive session.
 */
internal class WebUiHostTest {
    @Test
    fun commonSessionClosesDomAndExplicitlyRejectsGameOnlyFeatures() {
        val root = document.createElement("div") as HTMLElement
        val host = mountWeb(UiDefinition { Text("Session") }, root, IntSize(100, 30))
        val session = host.uiSession
        assertEquals(UiPresentation.Screen, session.presentation)
        assertEquals(UiOperationResult.Rejected(UiRejection.Unsupported), session.switch(UiPresentation.Hud))
        assertEquals(UiOperationResult.Rejected(UiRejection.Unsupported), session.setInputPolicy(UiInputPolicy.All))
        assertEquals(UiPresentation.Screen, session.presentation)
        session.close()
        session.close()
        assertFalse(root.hasChildNodes())
        assertEquals(UiSessionStatus.Closed(UiCloseReason.Closed), session.status)
        val unsupported = UiDefinition(presentation = UiPresentation.Hud) { Text("HUD") }
        assertFailsWith<IllegalArgumentException> { mountWeb(unsupported, root, IntSize(100, 30)) }
        unsupported.close()
    }

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
