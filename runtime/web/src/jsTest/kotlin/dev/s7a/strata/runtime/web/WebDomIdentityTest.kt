package dev.s7a.strata.runtime.web

import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.Text
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.state.mutableStateOf
import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Verifies DOM identity and terminal release while retained descriptions change.
 */
internal class WebDomIdentityTest {
    private val viewport = IntSize(320, 200)

    @Test
    fun keyedReorderingRetainsNodesAndReinsertionGetsFreshOwnership() {
        val order = mutableStateOf(listOf(1, 2, 3))
        val definition =
            ScreenDefinition("Keyed") {
                Column { order.value.forEach { item -> Text(item.toString(), key = ElementKey(item)) } }
            }
        withHost(definition) { host, root ->
            val first = root.children.item(0)
            val removed = checkNotNull(root.children.item(1))
            val third = root.children.item(2)
            order.value = listOf(3, 1)
            host.render(viewport)
            assertEquals(2, root.children.length)
            assertSame(third, root.children.item(0))
            assertSame(first, root.children.item(1))
            assertNull(removed.parentNode)
            order.value = listOf(3, 1, 2)
            host.render(viewport)
            assertNotSame(removed, root.children.item(2))
        }
    }

    @Test
    fun reactiveFailureClearsDomAndPreservesTheApplicationFailure() {
        val fail = mutableStateOf(false)
        val expected = IllegalStateException("Web content failure")
        val definition =
            ScreenDefinition("Failure") {
                if (fail.value) throw expected
                Stack { Text("Ready") }
            }
        withHost(definition) { host, root ->
            fail.value = true
            assertSame(expected, assertFailsWith<IllegalStateException> { host.render(viewport) })
            assertFalse(root.hasChildNodes())
            assertFailsWith<IllegalStateException> { host.render(viewport) }
            fail.value = false
        }
    }

    private fun withHost(
        definition: ScreenDefinition,
        action: (WebUiHost, HTMLElement) -> Unit,
    ) {
        val root = document.createElement("div") as HTMLElement
        checkNotNull(document.body).appendChild(root)
        var host: WebUiHost? = null
        try {
            val mounted = mountWeb(definition, root, viewport)
            host = mounted
            action(mounted, root)
        } finally {
            host?.close()
            root.parentNode?.removeChild(root)
        }
    }
}
