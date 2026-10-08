package dev.s7a.strata

import dev.s7a.strata.component.Column
import dev.s7a.strata.component.FlowRow
import dev.s7a.strata.component.Grid
import dev.s7a.strata.component.PanZoomState
import dev.s7a.strata.component.Row
import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.TiledImage
import dev.s7a.strata.component.TiledImageLevel
import dev.s7a.strata.component.TiledImageSource
import dev.s7a.strata.component.TiledImageTile
import dev.s7a.strata.component.TiledImageTileId
import dev.s7a.strata.component.UiScope
import dev.s7a.strata.component.buildComponentTree
import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementType
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.geometry.LongRect
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.Node
import dev.s7a.strata.state.StateSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Verifies detached container membership and callback cleanup on JVM and JavaScript.
 */
internal class ContainerChildrenOwnershipTest {
    @Test
    fun everyContainerDetachesEmptySmallAndWideMembershipAndPreservesEarlierDeclarations() {
        for (kind in Kind.entries) {
            for (count in listOf(0, 1, 8, 1_024)) {
                val children = List(count) { ExternalElement() }
                var escaped: UiScope? = null
                val parent =
                    buildComponentTree {
                        container(kind) {
                            escaped = this
                            children.forEach(::element)
                        }
                    }
                val offset = if (kind == Kind.TiledImage) 1 else 0
                assertEquals(count + offset, parent.children.size)
                children.forEachIndexed { index, child -> assertSame(child, parent.children[index + offset]) }
                repeat(3) {
                    val replacement = buildComponentTree { container(kind) { element(ExternalElement()) } }
                    assertEquals(1 + offset, replacement.children.size)
                    assertEquals(count + offset, parent.children.size)
                    children.forEachIndexed { index, child -> assertSame(child, parent.children[index + offset]) }
                }
                assertFailsWith<IllegalStateException> { checkNotNull(escaped).element(ExternalElement()) }
            }
        }
    }

    @Test
    fun callbackFailureClosesEveryChildScopeWithoutEmittingAPartialParent() {
        val failure = IllegalArgumentException("content failure")
        for (kind in Kind.entries) {
            var escaped: UiScope? = null
            val before = ExternalElement()
            val after = ExternalElement()
            val root =
                buildComponentTree {
                    Stack {
                        element(before)
                        val caught =
                            assertFailsWith<IllegalArgumentException> {
                                container(kind) {
                                    escaped = this
                                    element(ExternalElement())
                                    throw failure
                                }
                            }
                        assertSame(failure, caught)
                        assertFailsWith<IllegalStateException> { checkNotNull(escaped).element(after) }
                        element(after)
                    }
                }
            assertEquals(listOf(before, after), root.children)
        }
    }

    @Test
    fun nestedBuildersKeepIntermediateDeclarationsAndOuterEmissionOrder() {
        val first = ExternalElement()
        val last = ExternalElement()
        var intermediate: Element? = null
        val root =
            buildComponentTree {
                Stack {
                    element(first)
                    Row {
                        val nested = buildComponentTree { Column { element(first) } }
                        intermediate = nested
                        element(nested)
                        Grid(columns = 2) { FlowRow { Stack { element(last) } } }
                    }
                    element(last)
                }
            }
        assertSame(first, root.children[0])
        assertSame(last, root.children[2])
        assertSame(intermediate, root.children[1].children[0])
        assertEquals(listOf(first), checkNotNull(intermediate).children)
        assertSame(last, root.children[1].children[1].children.single().children.single().children.single())
    }

    @Test
    fun externalSubclassStillSnapshotsCallerListsAndVarargMembership() {
        val first = ExternalElement()
        val last = ExternalElement()
        val source = arrayListOf<Element>(first, last)
        val parent = ExternalElement(source)
        source.reverse()
        source.clear()
        assertEquals(listOf(first, last), parent.children)
        val arguments = arrayOf<Element>(first, last)
        val varargParent = fromVararg(*arguments)
        arguments[0] = last
        assertEquals(listOf(first, last), varargParent.children)
    }

    @Test
    fun parentConstructionEndsCallbackAccessBeforeExternalSourceGetters() {
        var escaped: UiScope? = null
        val child = ExternalElement()
        val source =
            source {
                assertFailsWith<IllegalStateException> { checkNotNull(escaped).element(ExternalElement()) }
                LongRect(0L, 0L, 8L, 8L)
            }
        val parent =
            buildComponentTree {
                TiledImage(source, PanZoomState(), IntSize(8, 8)) {
                    escaped = this
                    element(child)
                }
            }
        assertEquals(2, parent.children.size)
        assertSame(child, parent.children[1])
    }

    @Test
    fun constructorFailureClosesScopeAndLeavesTheOuterBuilderUsable() {
        val failure = IllegalArgumentException("source getter failure")
        var escaped: UiScope? = null
        val source = source { throw failure }
        val survivor = ExternalElement()
        val root =
            buildComponentTree {
                Stack {
                    assertSame(
                        failure,
                        assertFailsWith<IllegalArgumentException> {
                            TiledImage(source, PanZoomState(), IntSize(8, 8)) {
                                escaped = this
                                element(ExternalElement())
                            }
                        },
                    )
                    assertFailsWith<IllegalStateException> { checkNotNull(escaped).element(survivor) }
                    element(survivor)
                }
            }
        assertEquals(listOf(survivor), root.children)
    }

    @Test
    fun borrowedMembershipIsReleasedAfterSuccessAndConstructorFailure() {
        for (fail in listOf(false, true)) {
            val scope = UiScope.createRoot()
            scope.element(ExternalElement())
            scope.element(ExternalElement())
            val borrowed = scope.borrowChildElements()
            assertEquals(2, borrowed.size)
            assertFailsWith<IllegalStateException> { scope.element(ExternalElement()) }
            var detached: Element? = null
            try {
                if (fail) {
                    assertFailsWith<IllegalArgumentException> { throw IllegalArgumentException("constructor failure") }
                } else {
                    detached = ExternalElement(borrowed)
                }
            } finally {
                scope.close()
            }
            assertTrue(borrowed.isEmpty())
            if (fail.not()) assertEquals(2, checkNotNull(detached).children.size)
        }
    }

    private fun UiScope.container(kind: Kind, content: UiScope.() -> Unit) {
        when (kind) {
            Kind.Row -> Row { content() }
            Kind.FlowRow -> FlowRow { content() }
            Kind.Column -> Column { content() }
            Kind.Stack -> Stack { content() }
            Kind.Grid -> Grid(columns = 4) { content() }
            Kind.TiledImage -> TiledImage(source { LongRect(0L, 0L, 8L, 8L) }, PanZoomState(), IntSize(8, 8)) { content() }
        }
    }

    private fun source(readBounds: () -> LongRect): TiledImageSource =
        object : TiledImageSource {
            override val bounds: LongRect get() = readBounds()
            override val levels: List<TiledImageLevel> = listOf(TiledImageLevel(IntSize(8, 8), 1L))

            override fun tile(id: TiledImageTileId): StateSource<TiledImageTile> =
                error("Declaration construction must not request tiles.")
        }

    private fun fromVararg(vararg children: Element): Element = ExternalElement(children.asList())

    private enum class Kind {
        Row,
        FlowRow,
        Column,
        Stack,
        Grid,
        TiledImage,
    }

    /**
     * Downstream-style declaration using only the public defensive constructor.
     */
    private class ExternalElement(children: List<Element> = emptyList()) : Element(ElementIdentity.Positional, TYPE, children) {
        companion object {
            val TYPE: ElementType<ExternalElement, Node> =
                ElementType(
                    elementClass = ExternalElement::class,
                    nodeClass = Node::class,
                    validateLocal = {},
                    createNode = { object : Node() {} },
                    updateNode = { _, _, _ -> DirtyMask.None },
                )
        }
    }
}
