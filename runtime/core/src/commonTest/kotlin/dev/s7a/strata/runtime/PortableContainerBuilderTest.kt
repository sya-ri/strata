package dev.s7a.strata.runtime

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
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.element.Element
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.DoubleOffset
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.geometry.LongRect
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.runtime.spi.createRuntimeUiSession
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.StateRevision
import dev.s7a.strata.state.StateSnapshot
import dev.s7a.strata.state.StateSource
import dev.s7a.strata.state.StateSubscription
import dev.s7a.strata.state.mutableStateOf
import dev.s7a.strata.text.UiText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * Verifies DSL membership changes through the retained JVM and JavaScript pipelines.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class PortableContainerBuilderTest {
    @Test
    fun rowPreservesRetainedContracts() = verifyContainer(Kind.Row)

    @Test
    fun flowRowPreservesRetainedContracts() = verifyContainer(Kind.FlowRow)

    @Test
    fun columnPreservesRetainedContracts() = verifyContainer(Kind.Column)

    @Test
    fun stackPreservesRetainedContracts() = verifyContainer(Kind.Stack)

    @Test
    fun gridPreservesRetainedContracts() = verifyContainer(Kind.Grid)

    @Test
    fun tiledImagePreservesRetainedContracts() = verifyContainer(Kind.TiledImage)

    /**
     * Exercises keyed rebuilds, all frame outputs, previous snapshots and terminal ownership for one builder.
     */
    private fun verifyContainer(kind: Kind) {
        val probe = TestProbe()
        val first = TestProbe.ProbeId("first")
        val second = TestProbe.ProbeId("second")
        val order = mutableStateOf(listOf(first, second))
        val state = PanZoomState()
        val source = source()
        var declaration: Element? = null
        val session =
            createRuntimeUiSession {
                evaluateComponentTree {
                    container(kind, state, source) { modifier ->
                        order.value.forEach { id -> element(probe.element(id, key = id, modifier = modifier)) }
                    }
                }.also { declaration = it }
            }
        try {
            session.attach()
            val constraints = Constraints(maxWidth = 8, maxHeight = 8)
            // Tiled viewport geometry invalidates its tile observer during the first measurement.
            session.frame(constraints)
            val original = session.frame(constraints)
            val originalDeclaration = checkNotNull(declaration)
            val originalChildren = originalDeclaration.children.toList()
            val originalCommands = original.drawCommands.toList()
            val originalSemantics = original.semantics.toList()
            val firstNode = probe.nodeForTag(first)
            val secondNode = probe.nodeForTag(second)
            assertEquals(bounds(kind), original.semantics.map { it.bounds }, "$kind initial semantic bounds")
            assertEquals(bounds(kind), original.drawCommands.filterIsInstance<DrawCommand.FillRectangle>().map { it.bounds }, "$kind initial paint bounds")
            assertEquals(listOf(UiText.Literal("first"), UiText.Literal("second")), original.semantics.map { it.semantics.label }, "$kind initial semantic order")
            assertSame(original, session.frame(constraints), "$kind settled frame reuse")

            order.value = listOf(second, first)
            val reordered = session.frame(constraints)
            assertEquals(bounds(kind), reordered.semantics.map { it.bounds }, "$kind reordered semantic bounds")
            assertEquals(listOf(UiText.Literal("second"), UiText.Literal("first")), reordered.semantics.map { it.semantics.label }, "$kind reordered semantic order")
            assertSame(firstNode, probe.nodeForTag(first))
            assertSame(secondNode, probe.nodeForTag(second))
            assertEquals(2, probe.created.size)
            assertEquals(originalChildren, originalDeclaration.children)
            assertEquals(originalCommands, original.drawCommands)
            assertEquals(originalSemantics, original.semantics)
            assertEquals(InputResult.Consumed, session.dispatchPointer(PointerEvent.Press(IntOffset.Zero, PointerButton.Primary)))
            val overlaps = kind == Kind.Stack || kind == Kind.TiledImage
            assertEquals(if (overlaps) first else second, probe.inputEvents.last(), "$kind reordered pointer winner")

            session.detach()
            session.attach()
            assertEquals(reordered.semantics, session.frame(constraints).semantics)
            assertEquals(2, probe.created.size)
            order.value = listOf(second)
            assertEquals(listOf(UiText.Literal("second")), session.frame(constraints).semantics.map { it.semantics.label })
            assertSame(secondNode, probe.nodeForTag(second))
            assertEquals(1, probe.events.filterIsInstance<TestProbe.Event.Dispose>().size)
        } finally {
            session.close()
        }
        assertEquals(2, probe.events.filterIsInstance<TestProbe.Event.Dispose>().size)
        session.close()
        assertEquals(2, probe.events.filterIsInstance<TestProbe.Event.Dispose>().size)
    }

    private fun UiScope.container(
        kind: Kind,
        state: PanZoomState,
        source: TiledImageSource,
        content: UiScope.(Modifier) -> Unit,
    ) {
        when (kind) {
            Kind.Row -> {
                Row(spacing = 1) { content(Modifier.Empty) }
            }

            Kind.FlowRow -> {
                FlowRow(horizontalSpacing = 1) { content(Modifier.Empty) }
            }

            Kind.Column -> {
                Column(spacing = 1) { content(Modifier.Empty) }
            }

            Kind.Stack -> {
                Stack { content(Modifier.Empty) }
            }

            Kind.Grid -> {
                Grid(columns = 2) { content(Modifier.Empty) }
            }

            Kind.TiledImage -> {
                TiledImage(source, state, IntSize(8, 8)) {
                    content(Modifier.Empty.atContentPosition(DoubleOffset.Zero, Alignment.TopStart))
                }
            }
        }
    }

    private fun bounds(kind: Kind): List<IntRect> =
        listOf(
            IntRect(0, 0, 2, 1),
            when (kind) {
                Kind.Row, Kind.FlowRow -> IntRect(3, 0, 5, 1)
                Kind.Column -> IntRect(0, 2, 2, 3)
                Kind.Stack, Kind.TiledImage -> IntRect(0, 0, 2, 1)
                Kind.Grid -> IntRect(2, 0, 4, 1)
            },
        )

    private fun source(): TiledImageSource =
        object : TiledImageSource {
            override val bounds: LongRect = LongRect(0L, 0L, 8L, 8L)
            override val levels: List<TiledImageLevel> = listOf(TiledImageLevel(IntSize(8, 8), 1L))

            override fun tile(id: TiledImageTileId): StateSource<TiledImageTile> = StateSource { StateSubscription(StateSnapshot(StateRevision(0L), TiledImageTile.Empty)) {} }
        }

    private enum class Kind {
        Row,
        FlowRow,
        Column,
        Stack,
        Grid,
        TiledImage,
    }
}
