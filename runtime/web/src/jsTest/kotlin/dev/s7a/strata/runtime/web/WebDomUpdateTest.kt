package dev.s7a.strata.runtime.web

import dev.s7a.strata.component.TextStyle
import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementType
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.layout.MeasureScope
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.MeasureNode
import dev.s7a.strata.node.PaintNode
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.PaintScope
import dev.s7a.strata.runtime.spi.createRuntimeUiSession
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.mutableStateOf
import kotlinx.browser.document
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLProgressElement
import org.w3c.dom.MutationObserver
import org.w3c.dom.MutationObserverInit
import kotlin.js.json
import kotlin.js.unsafeCast
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import dev.s7a.strata.node.Node as RetainedNode

/**
 * Verifies native mutations against a fresh full render, with exact current-owner bounds and no elapsed-time gates.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class WebDomUpdateTest {
    @Test
    fun localizedUpdatesMutateOnlyOneElementAtEveryScreenSize() {
        for (theme in WebTheme.entries) {
            for (count in listOf(1, 100, 1_000)) {
                val initial = Snapshot(visuals = List(count) { text(it) })
                Fixture(initial, theme).use { fixture ->
                    val identities = fixture.elements()
                    val observer = observe(fixture.root)
                    try {
                        val changed = initial.copy(visuals = initial.visuals.mapIndexed { index, visual -> if (index == count / 2) visual.withLabel("After!") else visual })
                        fixture.render(changed)
                        val records = observer.takeRecords()
                        assertEquals(1, records.size)
                        assertSame(identities[count / 2], records.single().target)
                        assertEquals(null, records.single().attributeName)
                        assertEquals(identities, fixture.elements())
                        assertEquals(count, fixture.renderer.retainedElementCount)
                        fixture.assertMatchesFresh(changed)

                        fixture.render(changed)
                        assertTrue(observer.takeRecords().isEmpty())
                        val allChanged = initial.copy(visuals = initial.visuals.map { it.withLabel("Every!") })
                        fixture.render(allChanged)
                        assertEquals(count, observer.takeRecords().size)
                        assertEquals(identities, fixture.elements())
                        fixture.assertMatchesFresh(allChanged)
                    } finally {
                        observer.disconnect()
                    }
                }
            }
        }
    }

    @Test
    fun geometryClipBackgroundAndTextStyleMatchFreshRendering() {
        for (theme in WebTheme.entries) {
            val bounds = IntRect(5, 7, 69, 19)
            val decoration = Visual(bounds = bounds, background = ArgbColor(0x80804020.toInt()))
            val initial = Snapshot(visuals = listOf(text(1).copy(bounds = bounds), decoration))
            Fixture(initial, theme).use { fixture ->
                val nodes = fixture.elements()
                val changes = listOf(
                    initial.copy(size = IntSize(280, 140)),
                    initial.copy(visuals = initial.visuals.map { it.copy(bounds = IntRect(9, 11, 89, 31)) }),
                    initial.copy(visuals = initial.visuals.map { it.copy(clip = IntRect(10, 12, 50, 16)) }),
                    initial.copy(visuals = listOf(text(1).copy(presentation = text(1).presentation?.copy(style = TextStyle.Inactive)), decoration.copy(background = ArgbColor(-1)))),
                    initial.copy(visuals = listOf(text(1).copy(presentation = text(1).presentation?.copy(style = TextStyle.ContainerLabel)), decoration)),
                    initial,
                )
                for (changed in changes) {
                    fixture.render(changed)
                    assertSame(nodes.first(), fixture.elements().first())
                    fixture.assertMatchesFresh(changed)
                }
            }
        }
    }

    @Test
    fun orderingNativeControlsFocusAndChurnKeepCurrentOwnership() {
        val first = native(1, WebPresentation.Kind.Button)
        val second = native(2, WebPresentation.Kind.Progress).copy(presentation = native(2, WebPresentation.Kind.Progress).presentation?.copy(progress = 0.25))
        val third = native(3, WebPresentation.Kind.Button)
        val initial = Snapshot(visuals = listOf(first, second, third))
        Fixture(initial).use { fixture ->
            val nodes = fixture.elements()
            val focused = nodes.last() as HTMLButtonElement
            focused.focus()
            assertSame(focused, document.activeElement)
            val reordered = initial.copy(visuals = listOf(second, first.withLabel("After!"), third))
            fixture.render(reordered)
            assertEquals(listOf(nodes[1], nodes[0], nodes[2]), fixture.elements())
            assertSame(focused, document.activeElement)

            val updated = reordered.copy(visuals = listOf(second.copy(presentation = second.presentation?.copy(progress = 0.75)), first.copy(presentation = first.presentation?.copy(enabled = false)), third))
            fixture.render(updated)
            assertTrue((nodes[0] as HTMLButtonElement).disabled)
            assertEquals(0.75, (nodes[1] as HTMLProgressElement).value)
            fixture.assertMatchesFresh(updated)

            fixture.render(initial.copy(visuals = listOf(third)))
            assertEquals(1, fixture.renderer.retainedElementCount)
            assertEquals(null, nodes[0].parentNode)
            fixture.render(initial)
            assertNotSame(nodes[0], fixture.elements()[0])
            assertSame(focused, fixture.elements()[2])
            val replacement = initial.copy(visuals = listOf(native(1, WebPresentation.Kind.Progress), second, third))
            val oldButton = fixture.elements()[0]
            fixture.render(replacement)
            assertNotSame(oldButton, fixture.elements()[0])
            assertEquals(null, oldButton.parentNode)
            fixture.assertMatchesFresh(replacement)

            for (index in 10 until 110) {
                fixture.render(Snapshot(visuals = listOf(text(index))))
                assertEquals(1, fixture.renderer.retainedElementCount)
            }
            fixture.renderer.close()
            assertEquals(0, fixture.renderer.retainedElementCount)
            assertFalse(fixture.root.hasChildNodes())
            fixture.renderer.close()
            assertFailsWith<IllegalStateException> { fixture.render(initial) }
        }
    }

    @Test
    fun adoptionValidatesNativeStateThenReappliesUntrustedStyles() {
        for (theme in WebTheme.entries) {
            val initial = Snapshot(visuals = listOf(native(1, WebPresentation.Kind.Button), native(2, WebPresentation.Kind.Progress)))
            val html = Fixture(initial, theme).use { it.root.innerHTML }
            val root = document.createElement("div") as HTMLElement
            root.innerHTML = html
            val adopted = (0 until root.children.length).map { root.children.item(it) }
            (adopted[0] as HTMLElement).style.cssText = "color: red; left: 999px;"
            (adopted[0] as HTMLButtonElement).type = "submit"
            Fixture(initial, theme, root).use { fixture ->
                assertEquals(adopted, fixture.elements())
                assertEquals("button", (fixture.elements()[0] as HTMLButtonElement).type)
                fixture.assertMatchesFresh(initial)
                val observer = observe(root)
                try {
                    fixture.render(initial.copy(visuals = listOf(initial.visuals[0].withLabel("After!"), initial.visuals[1])))
                    assertEquals(1, observer.takeRecords().size)
                } finally {
                    observer.disconnect()
                }
            }
        }
    }

    @Test
    fun validationPreservesCommittedDomAndOwnersRemainIsolated() {
        val initial = Snapshot(visuals = listOf(text(1)))
        Fixture(initial).use { first ->
            Fixture(initial.copy(visuals = listOf(text(1).withLabel("Other!")))).use { second ->
                val before = first.root.innerHTML
                assertFailsWith<IllegalStateException> { first.render(initial.copy(visuals = listOf(text(1), text(1)))) }
                assertEquals(before, first.root.innerHTML)
                assertEquals(1, first.renderer.retainedElementCount)
                first.renderer.close()
                assertEquals(0, first.renderer.retainedElementCount)
                assertEquals(1, second.renderer.retainedElementCount)
                assertEquals("Other!", second.root.firstElementChild?.textContent)
                second.render(initial)
                second.assertMatchesFresh(initial)
            }
        }
    }

    @Test
    fun failedInsertionReleasesCommittedAndPartiallyInsertedElements() {
        val initial = Snapshot(visuals = listOf(text(1)))
        Fixture(initial, WebTheme.Minecraft).use { fixture ->
            val original = fixture.root.asDynamic().insertBefore
            val expected = IllegalStateException("Insertion failure")
            var insertions = 0
            fixture.root.asDynamic().insertBefore = { node: dynamic, cursor: dynamic ->
                insertions += 1
                if (insertions == 2) throw expected
                original.call(fixture.root, node, cursor)
            }
            try {
                assertSame(expected, assertFailsWith<IllegalStateException> { fixture.render(Snapshot(visuals = listOf(text(1), text(2), text(3)))) })
            } finally {
                fixture.root.asDynamic().insertBefore = original
            }
            fixture.renderer.close()
            assertEquals(0, fixture.renderer.retainedElementCount)
            assertFalse(fixture.root.hasChildNodes())
            assertFalse(fixture.root.hasAttribute("data-strata-theme-root"))
        }
    }

    @Test
    fun cleanupFailureDropsSnapshotsAndStillReleasesOtherNodesAndTheme() {
        val styles = document.querySelectorAll("style[data-strata-styles]").length
        Fixture(Snapshot(visuals = listOf(text(1), text(2))), WebTheme.Minecraft).use { fixture ->
            val nodes = fixture.elements()
            val original = fixture.root.asDynamic().removeChild
            val expected = IllegalStateException("Removal failure")
            fixture.root.asDynamic().removeChild = { node: dynamic ->
                if (node === nodes.first()) throw expected
                original.call(fixture.root, node)
            }
            try {
                assertSame(expected, assertFailsWith<IllegalStateException> { fixture.renderer.close() })
            } finally {
                fixture.root.asDynamic().removeChild = original
            }
            assertEquals(0, fixture.renderer.retainedElementCount)
            assertEquals(null, nodes.last().parentNode)
            assertEquals(styles, document.querySelectorAll("style[data-strata-styles]").length)
            assertFalse(fixture.root.hasAttribute("data-strata-theme-root"))
            fixture.renderer.close()
        }
    }

    private fun observe(root: HTMLElement): MutationObserver =
        // Omit attributeFilter rather than supplying an invalid null native sequence.
        MutationObserver { _, _ -> }.also { it.observe(root, json("attributes" to true, "childList" to true, "subtree" to true).unsafeCast<MutationObserverInit>()) }

    private fun text(identity: Int): Visual = native(identity, WebPresentation.Kind.Text)

    private fun native(
        identity: Int,
        kind: WebPresentation.Kind,
    ): Visual =
        Visual(
            bounds = IntRect(0, identity * 14, 64, identity * 14 + 12),
            presentation = WebPresentation(identity, kind, "Before", true, TextStyle.Normal, if (kind == WebPresentation.Kind.Progress) 0.5 else null),
        )

    /**
     * Detached command inputs owned only by the test and its current frame.
     */
    private data class Visual(
        val bounds: IntRect,
        val clip: IntRect? = null,
        val presentation: WebPresentation? = null,
        val background: ArgbColor? = null,
    ) {
        fun withLabel(label: String): Visual = copy(presentation = checkNotNull(presentation).copy(label = label))
    }

    /**
     * Immutable current workload; neither the renderer nor its cached entries retain this collection.
     */
    private data class Snapshot(
        val size: IntSize = IntSize(320, 200),
        val visuals: List<Visual>,
    )

    /**
     * Independent retained source and renderer used to compare incremental output with a fresh full render.
     */
    private class Fixture(
        initial: Snapshot,
        private val theme: WebTheme = WebTheme.Native,
        val root: HTMLElement = document.createElement("div") as HTMLElement,
    ) : AutoCloseable {
        private val state = mutableStateOf(initial)
        private val session = createRuntimeUiSession { PaintElement(state.value) }
        val renderer = WebDomRenderer(root, theme)

        init {
            checkNotNull(document.body).appendChild(root)
            session.attach()
            render(initial)
        }

        fun render(snapshot: Snapshot) {
            state.value = snapshot
            renderer.render(session.frame(Constraints.fixed(snapshot.size.width, snapshot.size.height)))
        }

        fun elements(): List<HTMLElement> = (0 until root.children.length).map { root.children.item(it) as HTMLElement }

        fun assertMatchesFresh(snapshot: Snapshot) {
            Fixture(snapshot, theme).use { fresh ->
                assertEquals(fresh.root.style.cssText, root.style.cssText)
                assertEquals(fresh.root.getAttribute("data-strata-theme-root"), root.getAttribute("data-strata-theme-root"))
                assertEquals(fresh.elements().map(::properties), elements().map(::properties))
            }
        }

        private fun properties(element: HTMLElement): List<Any?> {
            val style = element.style
            val styles = (0 until style.length).map(style::item).associateWith(style::getPropertyValue)
            return listOf(element.tagName, element.getAttribute("data-strata-node"), element.getAttribute("data-strata-theme"), styles, element.textContent, (element as? HTMLButtonElement)?.type, (element as? HTMLButtonElement)?.disabled, (element as? HTMLProgressElement)?.value, (element as? HTMLProgressElement)?.max)
        }

        override fun close() {
            try {
                session.close()
            } finally {
                try {
                    renderer.close()
                } finally {
                    root.parentNode?.removeChild(root)
                }
            }
        }
    }

    /**
     * One test-only paint primitive supplying known native identities, clips and decorative fills.
     */
    private class PaintElement(
        val snapshot: Snapshot,
    ) : Element(ElementIdentity.Positional, TYPE) {
        private class Node(
            var snapshot: Snapshot,
        ) : RetainedNode(),
            MeasureNode,
            PaintNode {
            override fun measure(
                scope: MeasureScope,
                constraints: Constraints,
            ): IntSize = constraints.constrain(snapshot.size)

            override fun paint(scope: PaintScope) {
                for (visual in snapshot.visuals) {
                    val paint: () -> Unit =
                        {
                            visual.presentation?.let { scope.drawPlatform(it, visual.bounds) }
                            visual.background?.let { scope.fillRectangle(visual.bounds, it) }
                        }
                    val clip = visual.clip
                    if (clip == null) paint() else scope.withClip(clip, paint)
                }
            }
        }

        companion object {
            private val TYPE =
                ElementType(
                    elementClass = PaintElement::class,
                    nodeClass = Node::class,
                    validateLocal = { _ -> },
                    createNode = { Node(it.snapshot) },
                    updateNode = { previous, current, node ->
                        node.snapshot = current.snapshot
                        if (previous.snapshot == current.snapshot) DirtyMask.None else DirtyMask.of(DirtyPhase.Measure, DirtyPhase.Paint)
                    },
                )
        }
    }
}
