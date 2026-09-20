package dev.s7a.strata.examples.web

import dev.s7a.strata.runtime.web.mountWeb
import dev.s7a.strata.runtime.web.renderWebHtml
import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Checks that every compiled factory independently reproduces the initial retained tree.
 */
internal class DemoAdoptionTest {
    @Test
    fun everyDemoAdoptsIndependentlyRenderedInitialElements() {
        Demo.entries.forEach { demo ->
            val root = document.createElement("div") as HTMLElement
            val initial = renderWebHtml(demo.definition(), Demo.viewport)
            root.innerHTML = initial
            val children = (0 until root.children.length).map { root.children.item(it) }
            mountWeb(demo.definition(), root, Demo.viewport).use {
                assertEquals(children.size, root.children.length)
                children.forEachIndexed { index, child -> assertSame(child, root.children.item(index)) }
            }
            assertEquals(initial, renderWebHtml(demo.definition(), Demo.viewport))
        }
    }

    @Test
    fun documentGenerationIsRepeatableAndRejectsUnknownInputs() {
        assertEquals(DemoDocuments.render("master"), DemoDocuments.render("master"))
        assertFailsWith<IllegalArgumentException> { Demo.decode("missing") }
        assertFailsWith<IllegalArgumentException> { DemoDocuments.render("../unexpected") }
    }

    @Test
    fun sourceLinksUseTheRequestedRevision() {
        val revision = "1234567890abcdef1234567890abcdef12345678"
        val documents = DemoDocuments.render(revision)
        Demo.entries.forEach { demo ->
            assertTrue(documents.contains("https://github.com/sya-ri/strata/blob/$revision/examples/web/src/jsMain/kotlin/dev/s7a/strata/examples/web/${demo.sourceFile}"))
        }
    }
}
