@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime

import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.DoubleOffset
import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.node.ChildTransform
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.PaintScope
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.runtime.spi.createRuntimeUiSession
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.spi.RuntimeExecutionOwner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * Preserves cached painting, root geometry, clips, and independent session ownership on both targets.
 */
internal class RootOverlayScopePaintingTest {
    @Test
    fun ordinaryAndPostChildScopesRemainLocalWhileCleanFramesReuseAllPaintCallbacks() {
        val probe = RootOverlayProbe()
        val otherOwner = RuntimeExecutionOwner()
        val localScopes = ArrayList<PaintScope>()
        probe.paint = { scope ->
            localScopes.add(scope)
            assertEquals(IntSize(4, 4), scope.size)
            otherOwner.run {
                assertFailsWith<IllegalStateException> { scope.size }
                assertFailsWith<IllegalStateException> { scope.fillRectangle(IntRect(0, 0, 1, 1), ArgbColor(-1)) }
            }
            scope.fillRectangle(IntRect(0, 0, 4, 4), ArgbColor(1))
        }
        probe.overlay = { scope ->
            localScopes.add(scope)
            assertEquals(IntSize(4, 4), scope.size)
            otherOwner.run {
                assertFailsWith<IllegalStateException> { scope.size }
                assertFailsWith<IllegalStateException> { scope.withClip(IntRect(0, 0, 1, 1)) { error("Foreign clip callback") } }
            }
            scope.fillRectangle(IntRect(0, 0, 2, 2), ArgbColor(2))
        }
        probe.rootOverlay = { scope ->
            probe.scopes.dropLast(1).forEach { old -> assertFailsWith<IllegalStateException> { old.anchorBounds } }
            scope.fillRectangle(scope.anchorBounds, ArgbColor(3))
        }
        val session = createRuntimeUiSession { probe.element() }
        try {
            session.attach()
            val first = session.frame(Constraints.fixed(4, 4))
            repeat(3) { assertSame(first, session.frame(Constraints.fixed(4, 4))) }
            assertEquals(listOf(1, 1, 1), listOf(probe.paintCalls, probe.overlayCalls, probe.rootOverlayCalls))
            localScopes.forEach { scope -> assertFailsWith<IllegalStateException> { scope.size } }
            probe.invalidatePaint()
            val next = session.frame(Constraints.fixed(4, 4))
            assertNotSame(first, next)
            assertEquals(first.drawCommands, next.drawCommands)
            assertEquals(listOf(2, 2, 2), listOf(probe.paintCalls, probe.overlayCalls, probe.rootOverlayCalls))
            assertNotSame(probe.scopes[0], probe.scopes[1])
            localScopes.forEach { scope -> assertFailsWith<IllegalStateException> { scope.withClip(IntRect(0, 0, 1, 1)) { error("Expired clip callback") } } }
        } finally {
            session.close()
        }
    }

    @Test
    fun twoOwnersKeepSessionsAndEveryCallbackGenerationIndependent() {
        val firstOwner = RuntimeExecutionOwner()
        val secondOwner = RuntimeExecutionOwner()
        val first = RootOverlayProbe()
        val second = RootOverlayProbe()
        val firstSession = firstOwner.run { createRuntimeUiSession { first.element() } }
        val secondSession = secondOwner.run { createRuntimeUiSession { second.element() } }
        firstOwner.run { firstSession.attach() }
        secondOwner.run { secondSession.attach() }
        try {
            repeat(3) { frame ->
                for ((owner, session, probe) in listOf(Triple(firstOwner, firstSession, first), Triple(secondOwner, secondSession, second))) {
                    owner.run {
                        if (0 < frame) probe.invalidatePaint()
                        probe.rootOverlay = { scope ->
                            (first.scopes + second.scopes).filter { it !== scope }.forEach { old ->
                                assertFailsWith<IllegalStateException> { old.anchorBounds }
                            }
                            assertEquals(IntRect(0, 0, 4, 4), scope.anchorBounds)
                        }
                        session.frame(Constraints.fixed(4, 4))
                    }
                }
            }
            assertEquals(3, first.rootOverlayCalls)
            assertEquals(3, second.rootOverlayCalls)
            assertNotSame(first.scopes[0], second.scopes[0])
        } finally {
            firstOwner.run { firstSession.close() }
            secondOwner.run { secondSession.close() }
        }
    }

    @Test
    fun detachedRectangleRemainsReadableAfterCallbackAndCloseUnderAnotherOwner() {
        val owner = RuntimeExecutionOwner()
        val other = RuntimeExecutionOwner()
        var copied: IntRect? = null
        val probe = RootOverlayProbe()
        probe.rootOverlay = { copied = it.anchorBounds }
        owner.run {
            val session = createRuntimeUiSession { probe.element() }
            session.attach()
            session.frame(Constraints.fixed(4, 4))
            assertEquals(IntSize(4, 4), requireNotNull(copied).size)
            session.close()
        }
        other.run {
            val rectangle = requireNotNull(copied)
            assertEquals(listOf(0, 0, 4, 4), listOf(rectangle.left, rectangle.top, rectangle.right, rectangle.bottom))
            assertEquals(IntSize(4, 4), rectangle.size)
            assertEquals(IntRect(2, -3, 6, 1), rectangle + IntOffset(2, -3))
            assertFailsWith<IllegalStateException> { probe.scopes.single().anchorBounds }
        }
    }

    @Test
    fun fractionalAnchorAndNestedRootClipsKeepExactRootCommandOrder() {
        val child = RootOverlayProbe(IntSize(4, 6))
        val root = RootOverlayProbe(IntSize(6, 6), IntOffset(2, 3), ChildTransform(0.5, DoubleOffset(0.5, 0.5)))
        child.paint = { scope -> scope.fillRectangle(IntRect(0, 0, 4, 6), ArgbColor(1)) }
        child.overlay = { scope -> scope.fillRectangle(IntRect(0, 0, 2, 2), ArgbColor(2)) }
        child.rootOverlay = { scope ->
            repeat(3) {
                assertEquals(IntRect(2, 3, 5, 7), scope.anchorBounds)
                assertEquals(IntSize(6, 6), scope.size)
            }
            scope.fillRectangle(IntRect(0, 0, 6, 6), ArgbColor(3))
            scope.withClip(IntRect(0, 1, 5, 5)) {
                scope.fillRectangle(IntRect(0, 0, 6, 6), ArgbColor(4))
                scope.withClip(IntRect(1, 0, 3, 6)) {
                    scope.fillRectangle(IntRect(0, 0, 6, 6), ArgbColor(5))
                }
                scope.fillRectangle(IntRect(4, 0, 6, 6), ArgbColor(6))
            }
        }
        val tree = UiTree()
        try {
            tree.update(root.element(listOf(child.element())))
            tree.measure(Constraints.fixed(6, 6))
            tree.layout()
            val expected =
                listOf(
                    DrawCommand.PushClip(IntRect(0, 0, 6, 6)),
                    DrawCommand.SampledImage(SOLID, SOURCE, FloatRect(2.5f, 3.5f, 4.5f, 6.5f), ArgbColor(1), 0f),
                    DrawCommand.PushFractionalClip(FloatRect(2.5f, 3.5f, 4.5f, 6.5f)),
                    DrawCommand.PopClip,
                    DrawCommand.SampledImage(SOLID, SOURCE, FloatRect(2.5f, 3.5f, 3.5f, 4.5f), ArgbColor(2), 0f),
                    DrawCommand.PopClip,
                    DrawCommand.FillRectangle(IntRect(0, 0, 6, 6), ArgbColor(3)),
                    DrawCommand.PushClip(IntRect(0, 1, 5, 5)),
                    DrawCommand.FillRectangle(IntRect(0, 0, 6, 6), ArgbColor(4)),
                    DrawCommand.PushClip(IntRect(1, 0, 3, 6)),
                    DrawCommand.FillRectangle(IntRect(0, 0, 6, 6), ArgbColor(5)),
                    DrawCommand.PopClip,
                    DrawCommand.FillRectangle(IntRect(4, 0, 6, 6), ArgbColor(6)),
                    DrawCommand.PopClip,
                )
            val commands = tree.paint()
            assertEquals(expected.size, commands.size)
            expected.zip(commands).forEach { (reference, actual) ->
                if (reference is DrawCommand.SampledImage && actual is DrawCommand.SampledImage) {
                    assertEquals(reference.copy(image = actual.image), actual)
                    assertEquals(listOf(-1), actual.image.copyArgb().toList())
                } else {
                    assertEquals(reference, actual)
                }
            }
            assertEquals(commands, tree.paint())
            assertEquals(1, child.rootOverlayCalls)
        } finally {
            tree.close()
        }
    }

    private companion object {
        val SOLID = createDrawImage(IntSize(1, 1), intArrayOf(-1))
        val SOURCE = FloatRect(0f, 0f, 1f, 1f)
    }
}
