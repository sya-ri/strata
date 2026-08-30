package dev.s7a.strata.runtime.minecraft.canvas

import dev.s7a.strata.component.Canvas
import dev.s7a.strata.component.Row
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.runtime.FrameTime
import dev.s7a.strata.runtime.minecraft.MinecraftProfileFixture
import dev.s7a.strata.runtime.minecraft.createMinecraftUiHost
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.runtime.spi.createRuntimeUiSession
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.UiText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies native canvas lifetime accounting through real retained requests and independent deterministic fences.
 * These tests exercise the shared protocol without claiming loaded GPU or adapter pixel evidence.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class NativeCanvasDeviceTest {
    @Test
    fun cachedFramesAndDuplicateRequestsCaptureEachAttachmentOnlyOnceAfterFinalLayout() {
        NativeCanvasFixture().use { fixture ->
            val source = fixture.source(depth = true)
            val size = IntSize(2, 3)
            val first = fixture.tree(source, size)
            val second = fixture.tree(source, size)
            val firstFrame = fixture.frame(first, size)
            val secondFrame = fixture.frame(second, size)
            assertEquals(firstFrame, fixture.frame(first, size))
            assertEquals(2, fixture.producers.size)
            assertEquals(listOf(0, 0), fixture.producers.map { it.captureCalls })

            val commands = firstFrame + secondFrame + firstFrame
            val presentation = fixture.device.prepare(commands, FrameTime(321L), scale = 3)
            assertEquals(listOf(1, 1), fixture.producers.map { it.captureCalls })
            assertEquals(listOf(IntSize(6, 9), IntSize(6, 9)), fixture.driver.targets.map { it.size })
            assertTrue(fixture.driver.targets.all { it.depth })
            fixture.producers.forEach { producer ->
                assertEquals(listOf(size), producer.captures.single().logicalSizes)
                assertEquals(listOf(FrameTime(321L)), producer.captures.single().frameTimes)
            }
            assertEquals(3, presentation.drawCommands.size)
            assertSame(token(presentation, 0), token(presentation, 2))
            assertEquals(4, fixture.driver.fences.size)
            fixture.device.queue(presentation)
            assertEquals(4, fixture.driver.fences.size)
            fixture.device.consumed()
            assertEquals(5, fixture.driver.fences.size)
        }
    }

    @Test
    fun threeSetLimitSkipsUpdatesWithoutRelabellingTheLastTokenOrSnapshot() {
        NativeCanvasFixture().use { fixture ->
            val tree = fixture.tree()
            val producer = fixture.producers.single()
            val generations = ArrayList<NativeCanvasPresentation>()
            repeat(3) { index ->
                producer.color = 0xFF102030.toInt() + index
                val presentation = fixture.prepare(tree)
                generations += presentation
                fixture.submit(presentation)
            }
            val committed = generations.last()
            val committedImage = image(committed)
            producer.color = 0xFFABCDEF.toInt()
            val skipped = fixture.prepare(tree)
            assertSame(token(committed), token(skipped))
            assertSame(committedImage, image(skipped))
            fixture.submit(skipped)
            repeat(100) { fixture.device.poll() }
            assertEquals(3, producer.captureCalls)
            assertEquals(3, fixture.device.retainedTargetCount())
            assertEquals(3, fixture.driver.targets.size)
            assertTrue(fixture.driver.targets.all { it.closeCalls == 0 })
            assertTrue(producer.captures.all { it.closeCalls == 0 })
            assertEquals(0, fixture.driver.finishCalls)

            fixture.driver.signalAll()
            fixture.device.poll()
            val refreshed = fixture.prepare(tree)
            assertNotSame(token(committed), token(refreshed))
            assertEquals(0xFFABCDEF.toInt(), image(refreshed).argbAt(0, 0))
            assertSame(committedImage, image(committed))
            assertEquals(0xFF102032.toInt(), committedImage.argbAt(0, 0))
            assertEquals(3, fixture.driver.targets.size)
            assertEquals(4, producer.captureCalls)
            fixture.submit(refreshed)
        }
    }

    @Test
    fun deviceLimitIncludesRetiredTargetsAndReleasesPermitsOnlyAfterDestruction() {
        val fixture = NativeCanvasFixture()
        val source = fixture.source()
        val trees = List(70) { fixture.tree(source) }
        val commands = trees.flatMap(fixture::frame)
        val full = fixture.device.prepare(commands, FrameTime(1L), scale = 1)
        assertEquals(64, full.drawCommands.size)
        assertThrows(IllegalStateException::class.java) { full.capture() }
        assertEquals(64, fixture.device.retainedTargetCount())
        assertEquals(64, fixture.driver.targets.size)
        assertEquals(64, fixture.producers.sumOf { it.captureCalls })
        fixture.submit(full)
        trees.take(32).forEach { it.close() }
        repeat(100) { fixture.device.poll() }
        val skipped = fixture.prepare(trees[64])
        assertTrue(skipped.drawCommands.isEmpty())
        assertThrows(IllegalStateException::class.java) { skipped.capture() }
        fixture.device.cancel(skipped)
        assertEquals(64, fixture.device.retainedTargetCount())
        assertEquals(0, fixture.producers[64].captureCalls)
        assertEquals(0, fixture.driver.finishCalls)

        fixture.driver.signalAll()
        fixture.device.poll()
        assertEquals(32, fixture.device.retainedTargetCount())
        assertEquals(32, fixture.driver.targets.count { it.closeCalls == 1 })
        val available = fixture.prepare(trees[64])
        assertEquals(1, available.drawCommands.size)
        assertEquals(0xFF336699.toInt(), image(available).argbAt(0, 0))
        assertEquals(33, fixture.device.retainedTargetCount())
        assertEquals(65, fixture.driver.targets.size)
        fixture.submit(available)
        fixture.close()
        assertEquals(0, fixture.device.retainedTargetCount())
        assertTrue(fixture.driver.targets.all { it.closeCalls == 1 })
        assertTrue(fixture.producers.all { it.closeCalls == 1 })
    }

    @Test
    fun asynchronousDestructionKeepsThreeCanvasPermitsAcrossSourceReplacements() {
        NativeCanvasFixture().use { fixture ->
            fixture.driver.destroyOnClose = false
            val tree = fixture.tree()
            repeat(3) {
                fixture.submit(fixture.prepare(tree))
                fixture.driver.signalAll()
                fixture.device.poll()
                tree.update(evaluateComponentTree { Canvas(fixture.source(), IntSize(2, 2)) })
            }
            repeat(100) { fixture.device.poll() }
            val skipped = fixture.prepare(tree)
            assertTrue(skipped.drawCommands.isEmpty())
            fixture.device.cancel(skipped)
            assertEquals(1, fixture.identities.distinct().size)
            assertEquals(3, fixture.device.retainedTargetCount())
            assertEquals(3, fixture.driver.allocationAttempts)
            assertTrue(fixture.driver.targets.all { it.closeCalls == 1 && it.destroyed.not() })
            assertEquals(listOf(1, 1, 1, 0), fixture.producers.map { it.captureCalls })
            assertEquals(0, fixture.driver.finishCalls)
            assertEquals(0, fixture.driver.drainCalls)

            fixture.driver.targets
                .first()
                .destroyed = true
            fixture.device.poll()
            assertEquals(2, fixture.device.retainedTargetCount())
            assertEquals(1, fixture.producers.first().closeCalls)
            val resumed = fixture.prepare(tree)
            assertEquals(1, resumed.drawCommands.size)
            assertEquals(3, fixture.device.retainedTargetCount())
            assertEquals(4, fixture.driver.allocationAttempts)
            fixture.submit(resumed)
        }
    }

    @Test
    fun asynchronousRetirementKeepsDevicePermitsAcrossRapidScreenChurn() {
        NativeCanvasFixture().use { fixture ->
            fixture.driver.destroyOnClose = false
            val source = fixture.source()
            repeat(64) {
                val tree = fixture.tree(source)
                fixture.submit(fixture.prepare(tree))
                fixture.driver.signalAll()
                fixture.device.poll()
                tree.close()
            }
            repeat(50) {
                val tree = fixture.tree(source)
                val skipped = fixture.prepare(tree)
                assertTrue(skipped.drawCommands.isEmpty())
                fixture.device.cancel(skipped)
                tree.close()
            }
            repeat(100) { fixture.device.poll() }
            assertEquals(64, fixture.device.retainedTargetCount())
            assertEquals(64, fixture.driver.allocationAttempts)
            assertTrue(fixture.driver.targets.all { it.closeCalls == 1 && it.destroyed.not() })
            assertEquals(0, fixture.driver.finishCalls)
            assertEquals(0, fixture.driver.drainCalls)

            fixture.driver.targets
                .take(32)
                .forEach { it.destroyed = true }
            fixture.device.poll()
            assertEquals(32, fixture.device.retainedTargetCount())
            val newest = fixture.tree(source)
            fixture.submit(fixture.prepare(newest))
            assertEquals(33, fixture.device.retainedTargetCount())
            assertEquals(65, fixture.driver.allocationAttempts)
        }
    }

    @Test
    fun terminalCleanupDrainsAcceptedTargetReleasesBeforeAcknowledgingPermits() {
        NativeCanvasFixture().use { fixture ->
            fixture.driver.destroyOnClose = false
            val first = fixture.tree()
            val second = fixture.tree()
            fixture.submit(fixture.device.prepare(fixture.frame(first) + fixture.frame(second), FrameTime(1L), 1))
            fixture.driver.signalAll()
            first.close()
            assertEquals(listOf(1, 0), fixture.driver.targets.map { it.closeCalls })
            fixture.driver.onDrain = {
                assertEquals(1, fixture.driver.finishCalls)
                assertEquals(2, fixture.device.retainedTargetCount())
                assertTrue(fixture.driver.targets.all { it.releaseAccepted && it.closeCalls == 1 })
                assertTrue(fixture.producers.all { it.closeCalls == 1 })
            }
            fixture.device.closeAfterGuiDiscarded()
            fixture.device.closeAfterGuiDiscarded()
            assertEquals(0, fixture.device.retainedTargetCount())
            assertTrue(fixture.driver.targets.all { it.destroyed && it.closeCalls == 1 })
            assertEquals(1, fixture.driver.finishCalls)
            assertEquals(1, fixture.driver.drainCalls)
        }
    }

    @Test
    fun absentCaptureKeepsInitializationWorkAliveAfterDetachWithoutBlocking() {
        NativeCanvasFixture().use { fixture ->
            val tree = fixture.tree()
            val producer = fixture.producers.single()
            producer.available = false
            producer.onCapture = {
                assertEquals(1, fixture.driver.fences.size)
                assertEquals(1, fixture.device.retainedTargetCount())
            }
            val empty = fixture.prepare(tree)
            fixture.device.cancel(empty)
            tree.close()
            repeat(100) { fixture.device.poll() }
            assertTrue(empty.drawCommands.isEmpty())
            assertThrows(IllegalStateException::class.java) { empty.capture() }
            assertEquals(1, fixture.device.retainedTargetCount())
            assertEquals(
                0,
                fixture.driver.targets
                    .single()
                    .closeCalls,
            )
            assertEquals(0, producer.closeCalls)
            assertEquals(0, fixture.driver.finishCalls)
            assertEquals(0, fixture.driver.drainCalls)
            fixture.driver.signalAll()
            fixture.device.poll()
            assertEquals(0, fixture.device.retainedTargetCount())
            assertEquals(
                1,
                fixture.driver.targets
                    .single()
                    .closeCalls,
            )
            assertEquals(1, producer.closeCalls)
        }
    }

    @Test
    fun sourceChangesRetainTheSameCanvasLimitAcrossAttachmentGenerations() {
        NativeCanvasFixture().use { fixture ->
            val tree = fixture.tree()
            repeat(5) { index ->
                if (0 < index) tree.update(evaluateComponentTree { Canvas(fixture.source(), IntSize(2, 2)) })
                val presentation = fixture.prepare(tree)
                assertEquals(if (index < 3) 1 else 0, presentation.drawCommands.size)
                fixture.submit(presentation)
            }
            assertEquals(1, fixture.identities.distinct().size)
            assertEquals(5, fixture.identities.size)
            assertEquals(3, fixture.device.retainedTargetCount())
            assertEquals(listOf(1, 1, 1, 0, 0), fixture.producers.map { it.captureCalls })
            assertTrue(fixture.producers.take(3).all { it.closeCalls == 0 })
            assertEquals(1, fixture.producers[3].closeCalls)

            fixture.driver.signalAll()
            fixture.device.poll()
            assertEquals(0, fixture.device.retainedTargetCount())
            val latest = fixture.prepare(tree)
            assertEquals(1, latest.drawCommands.size)
            assertEquals(1, fixture.producers.last().captureCalls)
            fixture.submit(latest)
        }
    }

    @Test
    fun capacityStarvedResizeKeepsOldPhysicalGenerationAndSnapshotAtTheNewDestination() {
        NativeCanvasFixture().use { fixture ->
            val source = fixture.source()
            val tree = fixture.tree(source)
            val generations = ArrayList<NativeCanvasPresentation>()
            for (width in 2..4) {
                val size = IntSize(width, 2)
                tree.update(evaluateComponentTree { Canvas(source, size) })
                val presentation = fixture.prepare(tree, size)
                generations += presentation
                fixture.submit(presentation)
            }
            val previous = generations.last()
            val newSize = IntSize(5, 2)
            tree.update(evaluateComponentTree { Canvas(source, newSize) })
            val skipped = fixture.prepare(tree, newSize)
            assertSame(token(previous), token(skipped))
            assertEquals(IntSize(4, 2), token(skipped).physicalSize)
            assertSame(image(previous), image(skipped))
            assertEquals(IntSize(5, 2), (skipped.drawCommands.single() as DrawCommand.Platform).bounds.size)
            assertEquals(listOf(IntSize(2, 2), IntSize(3, 2), IntSize(4, 2)), fixture.driver.targets.map { it.size })
            assertEquals(3, fixture.producers.single().captureCalls)
            fixture.submit(skipped)
        }
    }

    @Test
    fun sessionDetachRetiresItsRendererButQueuedTargetsSurviveUntilGuiCompletion() {
        NativeCanvasFixture().use { fixture ->
            val source = fixture.source()
            val session = createRuntimeUiSession { evaluateComponentTree { Canvas(source, IntSize(2, 2)) } }
            session.attach()
            val originalFrame = session.frame(Constraints.fixed(2, 2))
            val first = fixture.device.prepare(originalFrame.drawCommands, FrameTime(1L), 1)
            fixture.device.queue(first)
            val target = fixture.device.target(first, token(first))
            session.detach()
            assertSame(target, fixture.device.target(first, token(first)))
            assertEquals(0, fixture.producers.first().closeCalls)
            fixture.device.consumed()
            fixture.driver.fences[1].signalled = true
            fixture.device.poll()
            assertEquals(
                1,
                fixture.producers
                    .first()
                    .captures
                    .single()
                    .closeCalls,
            )
            assertEquals(0, fixture.producers.first().closeCalls)
            assertEquals(
                0,
                fixture.driver.targets
                    .first()
                    .closeCalls,
            )

            session.attach()
            val secondFrame = session.frame(Constraints.fixed(2, 2))
            val second = fixture.device.prepare(secondFrame.drawCommands, FrameTime(2L), 1)
            assertEquals(1, fixture.identities.distinct().size)
            assertEquals(2, fixture.producers.size)
            fixture.device.cancel(second)
            session.close()
            assertEquals(2, fixture.device.retainedTargetCount())
            fixture.driver.signalAll()
            fixture.device.poll()
            assertEquals(0, fixture.device.retainedTargetCount())
            assertTrue(fixture.producers.all { it.closeCalls == 1 })
            assertEquals(1, originalFrame.drawCommands.size)
            assertEquals(1, first.drawCommands.size)
        }
    }

    @Test
    fun closingTheHostInsideCaptureStopsRemainingProducersAndRetiresPartialGpuWork() {
        NativeCanvasFixture().use { fixture ->
            val source = fixture.source()
            val host =
                createMinecraftUiHost(
                    ScreenDefinition(UiText.Literal("canvas capture"), pausesGame = false) {
                        Row {
                            Canvas(source, IntSize(2, 2))
                            Canvas(source, IntSize(2, 2))
                        }
                    },
                    MinecraftProfileFixture.create(),
                )
            host.attach()
            val frame = host.frame(IntSize(4, 2))
            val first = fixture.producers.first()
            val second = fixture.producers.last()
            first.onRender = host::close

            assertThrows(IllegalStateException::class.java) {
                fixture.device.prepare(frame.drawCommands, FrameTime(1L), 1)
            }
            assertEquals(1, first.captureCalls)
            assertEquals(0, second.captureCalls)
            assertEquals(0, first.closeCalls)
            assertEquals(1, second.closeCalls)
            assertEquals(1, fixture.device.retainedTargetCount())
            assertEquals(
                0,
                fixture.driver.targets
                    .single()
                    .closeCalls,
            )
            assertEquals(2, fixture.driver.fences.size)
            assertThrows(IllegalStateException::class.java) {
                fixture.device.prepare(frame.drawCommands, FrameTime(2L), 1)
            }

            fixture.driver.signalAll()
            fixture.device.poll()
            assertEquals(0, fixture.device.retainedTargetCount())
            assertEquals(1, first.captures.single().closeCalls)
            assertEquals(
                1,
                fixture.driver.targets
                    .single()
                    .closeCalls,
            )
            assertEquals(1, first.closeCalls)
            assertEquals(0, fixture.driver.finishCalls)
            host.close()
        }
    }

    @Test
    fun closeBeforeQueueRequiresCancellationAndNeverWaitsForUnconsumedGuiWork() {
        NativeCanvasFixture().use { fixture ->
            val tree = fixture.tree()
            val commands = fixture.frame(tree)
            val presentation = fixture.device.prepare(commands, FrameTime(1L), 1)
            tree.close()
            fixture.driver.signalAll()
            repeat(100) { fixture.device.poll() }
            assertEquals(
                1,
                fixture.producers
                    .single()
                    .captures
                    .single()
                    .closeCalls,
            )
            assertEquals(
                0,
                fixture.driver.targets
                    .single()
                    .closeCalls,
            )
            assertEquals(0, fixture.producers.single().closeCalls)
            assertThrows(IllegalStateException::class.java) { fixture.device.queue(presentation) }
            fixture.device.cancel(presentation)
            assertEquals(
                1,
                fixture.driver.targets
                    .single()
                    .closeCalls,
            )
            assertEquals(1, fixture.producers.single().closeCalls)
            assertEquals(0, fixture.device.retainedTargetCount())
            assertThrows(IllegalStateException::class.java) { fixture.device.prepare(commands, FrameTime(2L), 1) }
            assertEquals(0, fixture.driver.finishCalls)
        }
    }

    @Test
    fun reloadKeepsOldQueuedGenerationAliveAndLazilyOpensANewRenderer() {
        NativeCanvasFixture().use { fixture ->
            val tree = fixture.tree()
            val first = fixture.prepare(tree)
            fixture.device.queue(first)
            val oldTarget = fixture.device.target(first, token(first))
            fixture.device.reload()
            assertEquals(1, fixture.producers.size)
            assertSame(oldTarget, fixture.device.target(first, token(first)))
            fixture.device.consumed()
            val second = fixture.prepare(tree, scale = 2)
            assertEquals(2, fixture.producers.size)
            assertEquals(IntSize(4, 4), token(second).physicalSize)
            assertNotSame(token(first), token(second))
            assertEquals(0, fixture.producers.first().closeCalls)
            fixture.submit(second)
            fixture.driver.signalAll()
            fixture.device.poll()
            assertEquals(1, fixture.producers.first().closeCalls)
            assertEquals(
                1,
                fixture.driver.targets
                    .first()
                    .closeCalls,
            )
            assertEquals(1, fixture.device.retainedTargetCount())
        }
    }

    @Test
    fun aCapacityStarvedReloadDoesNotConstructRenderersUntilATargetPermitExists() {
        NativeCanvasFixture().use { fixture ->
            val trees = List(64) { fixture.tree() }
            val commands = trees.flatMap(fixture::frame)
            fixture.submit(fixture.device.prepare(commands, FrameTime(1L), 1))
            fixture.device.reload()
            val skipped = fixture.device.prepare(commands, FrameTime(2L), 1)
            assertTrue(skipped.drawCommands.isEmpty())
            assertEquals(64, fixture.producers.size)
            fixture.device.cancel(skipped)
            fixture.driver.signalAll()
            fixture.device.poll()
            fixture.submit(fixture.prepare(trees.first()))
            assertEquals(65, fixture.producers.size)
            assertEquals(1, fixture.device.retainedTargetCount())
        }
    }

    @Test
    fun absentCaptureKeepsTheCommittedTokenAndSnapshotWithoutIssuingCaptureGpuWork() {
        NativeCanvasFixture().use { fixture ->
            val tree = fixture.tree()
            val producer = fixture.producers.single()
            producer.available = false
            val empty = fixture.prepare(tree)
            assertTrue(empty.drawCommands.isEmpty())
            assertEquals(1, fixture.driver.fences.size)
            fixture.device.cancel(empty)
            producer.available = true
            val first = fixture.prepare(tree)
            fixture.submit(first)
            producer.available = false
            val unchanged = fixture.prepare(tree)
            assertSame(token(first), token(unchanged))
            assertSame(image(first), image(unchanged))
            assertEquals(1, producer.captures.size)
            assertEquals(5, fixture.driver.fences.size)
            fixture.submit(unchanged)
        }
    }

    @Test
    fun invalidRequestsAreRejectedBeforeAnyAllocationOrProducerAndOldTokensCannotBeRequeued() {
        NativeCanvasFixture().use { fixture ->
            NativeCanvasFixture().use { foreign ->
                val tree = fixture.tree()
                val valid = fixture.frame(tree)
                val invalid = foreign.frame(foreign.tree())
                assertThrows(IllegalStateException::class.java) { fixture.device.prepare(valid + invalid, FrameTime(1L), 1) }
                assertThrows(IllegalStateException::class.java) { fixture.device.prepare(valid + DrawCommand.PopClip, FrameTime(1L), 1) }
                assertThrows(IllegalStateException::class.java) {
                    fixture.device.prepare(listOf(DrawCommand.PushClip(IntRect(0, 0, 2, 2))) + valid, FrameTime(1L), 1)
                }
                assertThrows(IllegalArgumentException::class.java) { fixture.device.prepare(valid, FrameTime(1L), 0) }
                assertThrows(ArithmeticException::class.java) { fixture.device.prepare(valid, FrameTime(1L), Int.MAX_VALUE) }
                assertTrue(fixture.driver.targets.isEmpty())
                assertEquals(0, fixture.producers.single().captureCalls)
                val presentation = fixture.prepare(tree)
                fixture.device.cancel(presentation)
                assertThrows(IllegalStateException::class.java) { fixture.device.prepare(presentation.drawCommands, FrameTime(2L), 1) }
                assertThrows(IllegalStateException::class.java) { fixture.device.queue(presentation) }
                assertThrows(IllegalStateException::class.java) { fixture.device.queue(foreign.prepare(foreign.tree())) }
            }
        }
    }

    @Test
    fun allocationReservesItsPermitFirstAndFailureReturnsItWithoutCapturing() {
        NativeCanvasFixture().use { fixture ->
            val tree = fixture.tree()
            val failure = IllegalArgumentException("allocation")
            val reservations = ArrayList<Int>()
            fixture.driver.onAllocate = { reservations += fixture.device.retainedTargetCount() }
            fixture.driver.nextAllocationFailure = failure
            assertSame(failure, assertThrows(IllegalArgumentException::class.java) { fixture.prepare(tree) })
            assertEquals(listOf(1), reservations)
            assertEquals(0, fixture.device.retainedTargetCount())
            assertEquals(0, fixture.producers.single().captureCalls)
            val retry = fixture.prepare(tree)
            assertEquals(listOf(1, 1), reservations)
            assertEquals(1, fixture.device.retainedTargetCount())
            fixture.device.cancel(retry)
        }
    }

    @Test
    fun queuedBatchesCannotBeCancelledOrSupersededAndExpiredTokensCannotResolve() {
        NativeCanvasFixture().use { fixture ->
            val tree = fixture.tree()
            val first = fixture.prepare(tree)
            assertThrows(IllegalStateException::class.java) { fixture.device.target(first, token(first)) }
            fixture.device.queue(first)
            assertThrows(IllegalStateException::class.java) { fixture.device.cancel(first) }
            assertThrows(IllegalStateException::class.java) { fixture.prepare(tree) }
            assertThrows(IllegalStateException::class.java) { fixture.device.queue(first) }
            fixture.device.consumed()
            assertThrows(IllegalStateException::class.java) { fixture.device.target(first, token(first)) }
            val second = fixture.prepare(tree)
            fixture.device.queue(second)
            assertThrows(IllegalStateException::class.java) { fixture.device.target(second, token(first)) }
            assertFalse(token(first) === token(second))
            fixture.device.consumed()
        }
    }

    private fun token(
        presentation: NativeCanvasPresentation,
        index: Int = 0,
    ): NativeCanvasToken = presentation.drawCommands.filterIsInstance<DrawCommand.Platform>()[index].command as NativeCanvasToken

    private fun image(presentation: NativeCanvasPresentation) =
        presentation
            .capture()
            .filterIsInstance<DrawCommand.BlitImagePixels>()
            .single()
            .image
}
