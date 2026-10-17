@file:Suppress("DEPRECATION") // Compatibility overloads and regression coverage retain the deprecated screen entry points.

package dev.s7a.strata.runtime.web

import dev.s7a.strata.component.ProgressBar
import dev.s7a.strata.component.Stack
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.runtime.spi.createRuntimeUiSession
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.spi.ComponentRuntimeBridge
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.UiText
import dev.s7a.strata.state.mutableStateOf
import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLProgressElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * Verifies native progress updates and deterministic connection to serialized HTML.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class WebProgressTest {
    @Test
    fun progressUpdatesSemanticsAndRetainsGeometry() {
        val progress = mutableStateOf(0.25)
        val runtime = WebComponentRuntime()
        val session =
            createRuntimeUiSession {
                ComponentRuntimeBridge.evaluate(runtime) { Stack { ProgressBar(progress.value) } }
            }
        try {
            session.attach()
            val before = session.frame(Constraints.fixed(320, 100))
            progress.value = 0.5
            val after = session.frame(Constraints.fixed(320, 100))
            assertEquals(before.semantics.map { it.bounds }, after.semantics.map { it.bounds })
            assertEquals(UiText.Literal("50%"), after.semantics.single().semantics.value)
            assertSame(after, session.frame(Constraints.fixed(320, 100)))
        } finally {
            session.close()
        }
    }

    @Test
    fun progressUsesNativeRangeAndRetainsItsElementAcrossStateUpdates() {
        val root = document.createElement("div") as HTMLElement
        val progress = mutableStateOf(0.25)
        val viewport = IntSize(320, 100)
        val host = mountWeb(ScreenDefinition("Progress") { Stack { ProgressBar(progress.value) } }, root, viewport)
        try {
            val element = root.firstElementChild as HTMLProgressElement
            assertEquals(1.0, element.max)
            assertEquals(0.25, element.value)
            progress.value = 2.0
            host.render(viewport)
            assertSame(element, root.firstElementChild)
            assertEquals(1.0, element.value)
            assertEquals("100%", element.textContent)
            val hydratedRoot = document.createElement("div") as HTMLElement
            hydratedRoot.innerHTML = root.innerHTML
            val initial = hydratedRoot.firstElementChild
            val hydrated = mountWeb(ScreenDefinition("Progress") { Stack { ProgressBar(1.0) } }, hydratedRoot, viewport)
            try {
                assertSame(initial, hydratedRoot.firstElementChild)
            } finally {
                hydrated.close()
            }
            hydratedRoot.innerHTML = root.innerHTML
            (hydratedRoot.firstElementChild as HTMLProgressElement).value = 0.5
            val mismatched = hydratedRoot.innerHTML
            assertFailsWith<IllegalStateException> {
                mountWeb(ScreenDefinition("Mismatch") { Stack { ProgressBar(1.0) } }, hydratedRoot, viewport)
            }
            assertEquals(mismatched, hydratedRoot.innerHTML)
        } finally {
            host.close()
        }
    }
}
