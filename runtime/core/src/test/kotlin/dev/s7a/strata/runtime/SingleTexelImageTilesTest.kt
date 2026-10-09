package dev.s7a.strata.runtime

import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementType
import dev.s7a.strata.geometry.DoubleOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.LifecycleNode
import dev.s7a.strata.node.Node
import dev.s7a.strata.node.PaintNode
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.PaintScope
import dev.s7a.strata.render.PlatformDrawCommand
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * Proves deferred scalar expansion, original validation and current-owner lifetime independently of Minecraft.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class SingleTexelImageTilesTest {
    @Test
    fun scalarGridAndCollapsedSpansPreserveSourceIdentityPhaseAndCroppedEdges() {
        for (sourceSize in listOf(IntSize(1, 1), IntSize(1, 8), IntSize(8, 1), IntSize(1, 257), IntSize(257, 1))) {
            val image = image(sourceSize)
            for (size in listOf(IntSize(1, 1), IntSize(8, 8), IntSize(17, 13), IntSize(320, 180), IntSize(641, 367))) {
                val composed = checkNotNull(createSingleTexelImageTiles(image, IntRect(0, 0, size.width, size.height)))
                val original = composed.original
                val scalar = scalar(image, size)
                assertEquals(scalar, original)
                val collapsed = composed.commands
                val columns = if (sourceSize.width == 1) 1 else (size.width + sourceSize.width - 1) / sourceSize.width
                val rows = if (sourceSize.height == 1) 1 else (size.height + sourceSize.height - 1) / sourceSize.height
                assertEquals(columns * rows, collapsed.size)
                for (blit in collapsed) {
                    assertSame(image, blit.image)
                    assertEquals(minOf(sourceSize.width, blit.destination.width), blit.source.width)
                    assertEquals(minOf(sourceSize.height, blit.destination.height), blit.source.height)
                    assertEquals(0, blit.source.left)
                    assertEquals(0, blit.source.top)
                }
                assertEquals(size.width, collapsed.last().destination.right)
                assertEquals(size.height, collapsed.last().destination.bottom)
                assertNotSame(original[0], original[0])
            }
        }
    }

    @Test
    fun countAndTerminalIncrementRejectionsKeepOriginalArithmeticPathWithoutAllocatingTheGrid() {
        val pixel = image(IntSize(1, 1))
        val composed = checkNotNull(createSingleTexelImageTiles(pixel, IntRect(0, 0, Int.MAX_VALUE, 1)))
        val original = composed.original
        assertEquals(Int.MAX_VALUE, original.size)
        assertEquals(IntRect(Int.MAX_VALUE - 1, 0, Int.MAX_VALUE, 1), original[Int.MAX_VALUE - 1].destination)
        assertEquals(1, composed.commands.size)
        assertNull(createSingleTexelImageTiles(pixel, IntRect(0, 0, Int.MAX_VALUE, 2)))
        assertNull(createSingleTexelImageTiles(image(IntSize(1, 8)), IntRect(0, 0, 1, Int.MAX_VALUE)))
        assertNull(createSingleTexelImageTiles(image(IntSize(8, 1)), IntRect(0, 0, Int.MAX_VALUE, 1)))
        assertNull(createSingleTexelImageTiles(image(IntSize(2, 2)), IntRect(0, 0, 8, 8)))
        assertNull(createSingleTexelImageTiles(pixel, IntRect(0, 0, 0, 8)))
        assertNull(createSingleTexelImageTiles(pixel, IntRect(1, 0, 8, 8)))
    }

    @Test
    fun ancestorTransformChangesUseOriginalCellsWithoutRepaintingOrMutatingPublishedFrames() {
        val image = image(IntSize(1, 8))
        val retained = entry(Probe(image, specialized = true))
        val pipeline = PaintPipeline(OwnerGuard())
        val integer = pipeline.paint(retained)
        assertEquals(23, integer.size)
        val original = (checkNotNull(retained.localCommands).single() as LocalDrawCommand.ComposedBlits).original
        assertEquals(7360, original.size)
        val old = integer.toList()
        val probe = retained.node as Probe
        for (transform in listOf(TreeTransform(0.5, DoubleOffset(0.25, -2.5)), TreeTransform(1.25, DoubleOffset(-3.25, 1.75)), TreeTransform.Identity)) {
            retained.localToTree = transform
            retained.paintSubtreeDirty = true
            val transformed = pipeline.paint(retained)
            if (transform == TreeTransform.Identity) {
                assertEquals(old, transformed)
            } else {
                val baseline = entry(Probe(image, specialized = false))
                baseline.localToTree = transform
                assertEquals(PaintPipeline(OwnerGuard()).paint(baseline), transformed)
                assertEquals(7360, transformed.size)
            }
            assertSame(original, (checkNotNull(retained.localCommands).single() as LocalDrawCommand.ComposedBlits).original)
            assertEquals(1, probe.calls)
            assertEquals(old, integer)
            assertSame(transformed, pipeline.paint(retained))
        }
    }

    @Test
    fun collapsedRectanglesDoNotEraseFloatDoubleFiniteOrIntegerTransformationFailures() {
        val transforms =
            listOf(
                TreeTransform(1.0, DoubleOffset(16_777_216.25, 0.0)),
                TreeTransform(0.25, DoubleOffset(-16_777_216.25, 0.0)),
                TreeTransform(1.0e-300, DoubleOffset(1.0, 1.0)),
                TreeTransform(1.0e40, DoubleOffset.Zero),
                TreeTransform(1.0, DoubleOffset(Int.MAX_VALUE.toDouble(), 0.0)),
            )
        for (transform in transforms) {
            val baseline = entry(Probe(image(IntSize(1, 1)), specialized = false))
            val candidate = entry(Probe(image(IntSize(1, 1)), specialized = true))
            baseline.localToTree = transform
            candidate.localToTree = transform
            val before = runCatching { PaintPipeline(OwnerGuard()).paint(baseline) }.exceptionOrNull()
            val after = runCatching { PaintPipeline(OwnerGuard()).paint(candidate) }.exceptionOrNull()
            assertTrue(before != null, "The original scalar grid must reject this transform.")
            assertEquals(before?.javaClass, after?.javaClass)
            assertEquals(before?.message, after?.message)
        }
    }

    @Test
    fun bridgeChecksScopeOwnerAndExpirationAndFailedCallbacksCloseTheirScope() {
        val primary = IllegalStateException("Injected paint failure")
        val probe = Probe(image(IntSize(1, 1)), specialized = true)
        val retained = entry(probe)
        PaintPipeline(OwnerGuard()).paint(retained)
        val expired = checkNotNull(probe.scope)
        assertThrows(IllegalStateException::class.java) { paintSingleTexelImageTiles(expired, probe.image) }
        val task = FutureTask { runCatching { paintSingleTexelImageTiles(expired, probe.image) }.exceptionOrNull() }
        val runner = Thread(task)
        runner.start()
        val failure = task.get(5, TimeUnit.SECONDS)
        runner.join(5_000)
        assertTrue(failure is IllegalStateException)
        assertEquals("This runtime object requires its owning execution context.", failure?.message)
        val failed = Probe(probe.image, specialized = true).apply { this.failure = primary }
        assertSame(primary, assertThrows(IllegalStateException::class.java) { PaintPipeline(OwnerGuard()).paint(entry(failed)) })
        assertThrows(IllegalStateException::class.java) { paintSingleTexelImageTiles(checkNotNull(failed.scope), failed.image) }
        assertEquals(1, (checkNotNull(retained.localCommands).single() as LocalDrawCommand.ComposedBlits).commands.size)
    }

    @Test
    fun terminalCleanupDropsCurrentDescriptorBeforeDisposeAndPreservesPublishedPixelsAfterFailure() {
        val owner = OwnerGuard()
        val probe = Probe(image(IntSize(1, 8)), specialized = true)
        val retained = entry(probe)
        val lifecycle = LifecycleManager(NodeOwnershipRegistry(), owner, DirtyTracker()) {}
        lifecycle.bind(retained)
        lifecycle.attachCurrent(retained)
        val published = PaintPipeline(owner).paint(retained)
        val saved = published.toList()
        val savedPixel = probe.image.argbAt(0, 0)
        val primary = IllegalStateException("Injected terminal failure")
        probe.onDispose = {
            assertNull(retained.localCommands)
            assertNull(retained.localOverlayCommands)
            assertNull(retained.rootOverlayCommands)
            assertNull(retained.transformedPaint)
            throw primary
        }
        assertSame(primary, lifecycle.cleanup(retained))
        assertNull(retained.localCommands)
        assertNull(retained.transformedPaint)
        assertEquals(saved, published)
        val first = published.first() as DrawCommand.BlitImage
        assertSame(probe.image, first.image)
        assertEquals(savedPixel, first.image.argbAt(0, 0))
    }

    @Test
    fun unknownScopesAreDeclinedWithoutReadingOrMutatingThem() {
        val foreign =
            object : PaintScope {
                override val size: IntSize get() = error("Foreign size must not be read")

                override fun fillRectangle(
                    localBounds: IntRect,
                    color: ArgbColor,
                ): Unit = error("Foreign fill")

                override fun blitImage(
                    image: DrawImage,
                    source: IntRect,
                    localDestination: IntRect,
                ): Unit = error("Foreign blit")

                override fun drawPlatform(
                    command: PlatformDrawCommand,
                    localBounds: IntRect,
                ): Unit = error("Foreign platform")
            }
        assertFalse(paintSingleTexelImageTiles(foreign, image(IntSize(1, 1))))
    }

    private fun image(size: IntSize): DrawImage = createDrawImage(size, IntArray(size.width * size.height) { 0x80123400.toInt() or it })

    private fun entry(probe: Probe): RetainedNode =
        RetainedNode(Description(probe), probe, null).apply {
            measuredSize = IntSize(320, 180)
            bounds = IntRect(0, 0, 320, 180)
            placed = true
        }

    private fun scalar(
        image: DrawImage,
        size: IntSize,
    ): List<LocalDrawCommand.BlitImage> =
        buildList {
            var top = 0
            while (top < size.height) {
                var left = 0
                while (left < size.width) {
                    val width = minOf(image.size.width, size.width - left)
                    val height = minOf(image.size.height, size.height - top)
                    add(LocalDrawCommand.BlitImage(image, IntRect(0, 0, width, height), IntRect(left, top, Math.addExact(left, width), Math.addExact(top, height))))
                    left = Math.addExact(left, image.size.width)
                }
                top = Math.addExact(top, image.size.height)
            }
        }

    /**
     * Independent scalar or bridge-backed node used with the real guarded paint pipeline.
     */
    private class Probe(
        val image: DrawImage,
        val specialized: Boolean,
    ) : Node(),
        PaintNode,
        LifecycleNode {
        var calls: Int = 0
        var scope: PaintScope? = null
        var failure: Throwable? = null
        var onDispose: () -> Unit = {}

        override fun attach() = Unit

        override fun detach() = Unit

        override fun dispose() = onDispose()

        override fun paint(scope: PaintScope) {
            this.scope = scope
            calls += 1
            if (specialized) {
                check(paintSingleTexelImageTiles(scope, image))
            } else {
                var top = 0
                while (top < scope.size.height) {
                    var left = 0
                    while (left < scope.size.width) {
                        val width = minOf(image.size.width, scope.size.width - left)
                        val height = minOf(image.size.height, scope.size.height - top)
                        scope.blitImage(image, IntRect(0, 0, width, height), IntRect(left, top, Math.addExact(left, width), Math.addExact(top, height)))
                        left = Math.addExact(left, image.size.width)
                    }
                    top = Math.addExact(top, image.size.height)
                }
            }
            failure?.let { throw it }
        }
    }

    /**
     * Detached description for the directly exercised internal retained entry.
     */
    private class Description(
        probe: Probe,
    ) : Element(ElementIdentity.Positional, TYPE) {
        val probe: Probe = probe

        /**
         * Stable description/node token for this test fixture.
         */
        companion object {
            val TYPE: ElementType<Description, Probe> =
                ElementType(Description::class, Probe::class, validateLocal = {}, createNode = { it.probe }, updateNode = { _, _, _ -> DirtyMask.None })
        }
    }
}
