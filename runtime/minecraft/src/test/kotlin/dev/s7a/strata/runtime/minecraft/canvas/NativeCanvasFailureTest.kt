package dev.s7a.strata.runtime.minecraft.canvas

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.runtime.FrameTime
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies partial native work and cleanup failures retain their permits and preserve the original failure.
 * All device behavior is deterministic protocol simulation, without claiming actual GPU execution.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class NativeCanvasFailureTest {
    @Test
    fun partialProducerFailureRetainsAllIssuedCaptureWorkUntilEachFenceSignals() {
        NativeCanvasFixture().use { fixture ->
            val first = fixture.tree()
            val second = fixture.tree()
            val failure = IllegalArgumentException("second capture")
            fixture.producers[1].renderFailure = failure
            val commands = fixture.frame(first) + fixture.frame(second)
            assertSame(failure, assertThrows(IllegalArgumentException::class.java) { fixture.device.prepare(commands, FrameTime(1L), 1) })
            first.close()
            second.close()
            repeat(100) { fixture.device.poll() }
            assertEquals(2, fixture.device.retainedTargetCount())
            assertTrue(fixture.producers.all { it.closeCalls == 0 })
            assertTrue(fixture.producers.flatMap { it.captures }.all { it.closeCalls == 0 })
            fixture.driver.fences[0].signalled = true
            fixture.driver.fences[1].signalled = true
            fixture.device.poll()
            assertEquals(1, fixture.device.retainedTargetCount())
            assertEquals(1, fixture.producers[0].closeCalls)
            assertEquals(0, fixture.producers[1].closeCalls)
            fixture.driver.fences[2].signalled = true
            fixture.driver.fences[3].signalled = true
            fixture.device.poll()
            assertEquals(0, fixture.device.retainedTargetCount())
            assertTrue(fixture.producers.all { it.closeCalls == 1 })
        }
    }

    @Test
    fun failedCaptureFenceQuarantinesPartialWorkUntilDeviceShutdown() {
        val fixture = NativeCanvasFixture()
        val tree = fixture.tree()
        val primary = IllegalArgumentException("render")
        val fenceFailure = IllegalStateException("capture fence")
        fixture.producers.single().renderFailure = primary
        fixture.producers.single().onRender = { fixture.driver.nextFenceFailure = fenceFailure }
        val failure = assertThrows(IllegalArgumentException::class.java) { fixture.prepare(tree) }
        assertSame(primary, failure)
        assertEquals(listOf(fenceFailure), failure.suppressed.toList())
        tree.close()
        repeat(100) { fixture.device.poll() }
        assertEquals(1, fixture.device.retainedTargetCount())
        assertEquals(
            0,
            fixture.producers
                .single()
                .captures
                .single()
                .closeCalls,
        )
        assertEquals(0, fixture.driver.finishCalls)
        fixture.close()
        assertEquals(1, fixture.driver.finishCalls)
        assertEquals(
            1,
            fixture.producers
                .single()
                .captures
                .single()
                .closeCalls,
        )
        assertEquals(
            1,
            fixture.driver.targets
                .single()
                .closeCalls,
        )
        assertEquals(1, fixture.producers.single().closeCalls)
    }

    @Test
    fun failedInitializationFenceQuarantinesAllocationBeforeSourceAcquisition() {
        NativeCanvasFixture().use { fixture ->
            val tree = fixture.tree()
            val failure = IllegalArgumentException("initialization fence")
            fixture.driver.nextFenceFailure = failure
            assertSame(failure, assertThrows(IllegalArgumentException::class.java) { fixture.prepare(tree) })
            tree.close()
            repeat(100) { fixture.device.poll() }
            assertEquals(0, fixture.producers.single().captureCalls)
            assertEquals(1, fixture.device.retainedTargetCount())
            assertEquals(
                0,
                fixture.driver.targets
                    .single()
                    .closeCalls,
            )
            assertEquals(0, fixture.driver.finishCalls)
            assertEquals(0, fixture.driver.drainCalls)

            fixture.device.closeAfterGuiDiscarded()
            assertEquals(0, fixture.device.retainedTargetCount())
            assertEquals(
                1,
                fixture.driver.targets
                    .single()
                    .closeCalls,
            )
            assertEquals(1, fixture.producers.single().closeCalls)
            assertEquals(1, fixture.driver.finishCalls)
            assertEquals(1, fixture.driver.drainCalls)
        }
    }

    @Test
    fun asynchronousDestructionQueryFailureRetainsPermitWithoutRepeatingAcceptedClose() {
        NativeCanvasFixture().use { fixture ->
            fixture.driver.destroyOnClose = false
            val tree = fixture.tree()
            fixture.submit(fixture.prepare(tree))
            tree.close()
            fixture.driver.signalAll()
            val target = fixture.driver.targets.single()
            val failure = IllegalArgumentException("destruction query")
            target.destructionFailure = failure
            repeat(3) {
                assertSame(failure, assertThrows(IllegalArgumentException::class.java) { fixture.device.poll() })
            }
            assertEquals(1, target.closeCalls)
            assertEquals(1, fixture.device.retainedTargetCount())
            target.destructionFailure = null
            repeat(100) { fixture.device.poll() }
            assertEquals(1, fixture.device.retainedTargetCount())
            assertEquals(0, fixture.producers.single().closeCalls)
            target.destroyed = true
            fixture.device.poll()
            assertEquals(0, fixture.device.retainedTargetCount())
            assertEquals(1, target.closeCalls)
            assertEquals(1, fixture.producers.single().closeCalls)
        }
    }

    @Test
    fun failedGuiFenceKeepsTargetsAndRenderersAfterCaptureLeasesHaveCompleted() {
        val fixture = NativeCanvasFixture()
        val tree = fixture.tree()
        val presentation = fixture.prepare(tree)
        fixture.device.queue(presentation)
        val failure = IllegalStateException("GUI fence")
        fixture.driver.nextFenceFailure = failure
        assertSame(failure, assertThrows(IllegalStateException::class.java) { fixture.device.consumed() })
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
        assertEquals(1, fixture.device.retainedTargetCount())
        fixture.close()
        assertEquals(
            1,
            fixture.driver.targets
                .single()
                .closeCalls,
        )
        assertEquals(1, fixture.producers.single().closeCalls)
    }

    @Test
    fun failedGuiConsumptionQuarantinesEverySelectedTargetWithoutAnEarlyCompletionFence() {
        NativeCanvasFixture().use { fixture ->
            val firstTree = fixture.tree()
            val secondTree = fixture.tree()
            val presentation =
                fixture.device.prepare(
                    fixture.frame(firstTree) + fixture.frame(secondTree),
                    FrameTime(1L),
                    1,
                )
            fixture.device.queue(presentation)
            fixture.device.failedGui()
            assertEquals(4, fixture.driver.fences.size)
            firstTree.close()
            secondTree.close()
            fixture.driver.signalAll()
            repeat(100) { fixture.device.poll() }
            assertTrue(fixture.producers.all { it.captures.single().closeCalls == 1 })
            assertTrue(fixture.driver.targets.all { it.closeCalls == 0 })
            assertTrue(fixture.producers.all { it.closeCalls == 0 })
            assertEquals(2, fixture.device.retainedTargetCount())
            assertEquals(0, fixture.driver.finishCalls)
            assertThrows(IllegalStateException::class.java) {
                fixture.device.target(presentation, token(presentation))
            }
        }
    }

    @Test
    fun sourceAcquisitionFailureRetainsInitializationUntilItsFenceSignals() {
        NativeCanvasFixture().use { fixture ->
            val tree = fixture.tree()
            val failure = IllegalArgumentException("source unavailable")
            fixture.producers.single().captureFailure = failure
            assertSame(failure, assertThrows(IllegalArgumentException::class.java) { fixture.prepare(tree) })
            assertEquals(1, fixture.driver.fences.size)
            assertTrue(
                fixture.producers
                    .single()
                    .captures
                    .isEmpty(),
            )
            tree.close()
            repeat(100) { fixture.device.poll() }
            assertEquals(1, fixture.device.retainedTargetCount())
            assertEquals(
                0,
                fixture.driver.targets
                    .single()
                    .closeCalls,
            )
            assertEquals(0, fixture.producers.single().closeCalls)
            fixture.driver.signalAll()
            fixture.device.poll()
            assertEquals(0, fixture.device.retainedTargetCount())
            assertEquals(
                1,
                fixture.driver.targets
                    .single()
                    .closeCalls,
            )
            assertEquals(1, fixture.producers.single().closeCalls)
            assertEquals(0, fixture.driver.finishCalls)
        }
    }

    @Test
    fun aFailedCompletionQueryRetainsResourcesUntilALaterSuccessfulNonblockingPoll() {
        NativeCanvasFixture().use { fixture ->
            val tree = fixture.tree()
            fixture.submit(fixture.prepare(tree))
            tree.close()
            val failure = IllegalArgumentException("completion query")
            fixture.driver.fences
                .first()
                .pollFailure = failure
            assertSame(failure, assertThrows(IllegalArgumentException::class.java) { fixture.device.poll() })
            assertEquals(1, fixture.device.retainedTargetCount())
            assertEquals(
                0,
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
            fixture.driver.fences
                .first()
                .pollFailure = null
            fixture.driver.signalAll()
            fixture.device.poll()
            assertEquals(0, fixture.device.retainedTargetCount())
            assertEquals(
                1,
                fixture.producers
                    .single()
                    .captures
                    .single()
                    .closeCalls,
            )
            assertEquals(
                1,
                fixture.driver.targets
                    .single()
                    .closeCalls,
            )
            assertEquals(0, fixture.driver.finishCalls)
        }
    }

    @Test
    fun shutdownAttemptsEveryResourceAndPreservesItsFailureOnRepeatedClose() {
        val fixture = NativeCanvasFixture()
        val tree = fixture.tree()
        val captureClose = IllegalStateException("capture close")
        val initializationFenceClose = IllegalArgumentException("initialization fence close")
        val captureFenceClose = IllegalArgumentException("capture fence close")
        val guiFenceClose = IllegalStateException("GUI fence close")
        val targetClose = IllegalStateException("target close")
        val producerClose = IllegalStateException("producer close")
        fixture.producers.single().captureCloseFailure = captureClose
        fixture.producers.single().closeFailure = producerClose
        fixture.submit(fixture.prepare(tree))
        fixture.driver.fences[0].closeFailure = initializationFenceClose
        fixture.driver.fences[1].closeFailure = captureFenceClose
        fixture.driver.fences[2].closeFailure = guiFenceClose
        fixture.driver.targets
            .single()
            .closeFailure = targetClose
        tree.close()

        val failure = assertThrows(IllegalArgumentException::class.java) { fixture.device.closeAfterGuiDiscarded() }
        assertSame(initializationFenceClose, failure)
        assertEquals(listOf(captureFenceClose, guiFenceClose, targetClose, producerClose), failure.suppressed.toList())
        assertEquals(listOf(captureClose), captureFenceClose.suppressed.toList())
        assertEquals(1, fixture.device.retainedTargetCount())
        assertTrue(fixture.driver.fences.all { it.closeCalls == 1 })
        assertEquals(
            1,
            fixture.driver.targets
                .single()
                .closeCalls,
        )
        assertEquals(1, fixture.producers.single().closeCalls)
        assertSame(failure, assertThrows(IllegalArgumentException::class.java) { fixture.device.closeAfterGuiDiscarded() })
        assertEquals(1, fixture.driver.finishCalls)
        assertEquals(
            1,
            fixture.driver.targets
                .single()
                .closeCalls,
        )
    }

    @Test
    fun terminalRetirementDrainFailureStillAcknowledgesIndependentDestroyedTargets() {
        val fixture = NativeCanvasFixture()
        fixture.driver.destroyOnClose = false
        val first = fixture.tree()
        val second = fixture.tree()
        fixture.submit(fixture.device.prepare(fixture.frame(first) + fixture.frame(second), FrameTime(1L), 1))
        first.close()
        second.close()
        fixture.driver.targets
            .first()
            .destroyOnClose = true
        val failure = IllegalArgumentException("retirement drain")
        fixture.driver.drainFailure = failure
        assertSame(failure, assertThrows(IllegalArgumentException::class.java) { fixture.device.closeAfterGuiDiscarded() })
        assertTrue(failure.suppressed.single() is IllegalStateException)
        assertEquals(1, fixture.device.retainedTargetCount())
        assertTrue(fixture.driver.targets.all { it.closeCalls == 1 && it.releaseAccepted })
        assertTrue(fixture.driver.fences.all { it.closeCalls == 1 })
        assertTrue(fixture.producers.all { it.closeCalls == 1 })
        assertSame(failure, assertThrows(IllegalArgumentException::class.java) { fixture.device.closeAfterGuiDiscarded() })
        assertEquals(1, fixture.driver.finishCalls)
        assertEquals(1, fixture.driver.drainCalls)
    }

    @Test
    fun terminalMissingDestructionAcknowledgementFailsAfterDrainWithoutReissuingClose() {
        val fixture = NativeCanvasFixture()
        fixture.driver.destroyOnClose = false
        fixture.driver.completeDestructionOnDrain = false
        val tree = fixture.tree()
        fixture.device.cancel(fixture.prepare(tree))
        tree.close()
        val failure = assertThrows(IllegalStateException::class.java) { fixture.device.closeAfterGuiDiscarded() }
        assertEquals(1, fixture.device.retainedTargetCount())
        assertEquals(
            1,
            fixture.driver.targets
                .single()
                .closeCalls,
        )
        assertEquals(1, fixture.producers.single().closeCalls)
        fixture.driver.targets
            .single()
            .destroyed = true
        assertSame(failure, assertThrows(IllegalStateException::class.java) { fixture.device.closeAfterGuiDiscarded() })
        assertEquals(
            1,
            fixture.driver.targets
                .single()
                .closeCalls,
        )
        assertEquals(1, fixture.driver.finishCalls)
        assertEquals(1, fixture.driver.drainCalls)
    }

    @Test
    fun partialAllocationAndInitializationFenceFailurePreserveOriginalAndQuarantine() {
        NativeCanvasFixture().use { fixture ->
            val tree = fixture.tree()
            val allocationFailure = IllegalArgumentException("partial allocation")
            val fenceFailure = IllegalStateException("partial initialization fence")
            fixture.driver.onAllocate = {
                val target = NativeCanvasFixture.Target(IntSize(2, 2), depth = false)
                fixture.driver.targets += target
                throw NativeCanvasAllocationFailure(target, allocationFailure)
            }
            fixture.driver.nextFenceFailure = fenceFailure
            assertSame(allocationFailure, assertThrows(IllegalArgumentException::class.java) { fixture.prepare(tree) })
            assertEquals(listOf(fenceFailure), allocationFailure.suppressed.toList())
            tree.close()
            repeat(100) { fixture.device.poll() }
            assertEquals(1, fixture.device.retainedTargetCount())
            assertEquals(
                0,
                fixture.driver.targets
                    .single()
                    .closeCalls,
            )
            assertEquals(0, fixture.producers.single().captureCalls)
            fixture.device.closeAfterGuiDiscarded()
            assertEquals(0, fixture.device.retainedTargetCount())
            assertEquals(
                1,
                fixture.driver.targets
                    .single()
                    .closeCalls,
            )
            assertEquals(1, fixture.driver.finishCalls)
            assertEquals(1, fixture.driver.drainCalls)
        }
    }

    @Test
    fun acceptedPartialAllocationReleaseIsNeverRequestedAgainDuringShutdown() {
        NativeCanvasFixture().use { fixture ->
            val tree = fixture.tree()
            val failure = IllegalArgumentException("asynchronous rollback")
            fixture.driver.onAllocate = {
                val target = NativeCanvasFixture.Target(IntSize(2, 2), depth = false)
                target.destroyOnClose = false
                fixture.driver.targets += target
                target.close()
                throw NativeCanvasAllocationFailure(target, failure, releaseRequested = true)
            }
            assertSame(failure, assertThrows(IllegalArgumentException::class.java) { fixture.prepare(tree) })
            tree.close()
            fixture.driver.signalAll()
            repeat(100) { fixture.device.poll() }
            val target = fixture.driver.targets.single()
            assertEquals(1, target.closeCalls)
            assertEquals(0, target.destructionPolls)
            assertEquals(1, fixture.device.retainedTargetCount())
            fixture.device.closeAfterGuiDiscarded()
            assertEquals(1, target.closeCalls)
            assertEquals(1, target.destructionPolls)
            assertEquals(0, fixture.device.retainedTargetCount())
        }
    }

    @Test
    fun incompleteAllocationRollbackReservesEveryPartialTargetUntilTerminalDestruction() {
        NativeCanvasFixture().use { fixture ->
            val tree = fixture.tree()
            val failures = ArrayList<Throwable>()
            fixture.driver.onAllocate = {
                assertEquals(fixture.driver.targets.size + 1, fixture.device.retainedTargetCount())
                val target = NativeCanvasFixture.Target(IntSize(2, 2), depth = false)
                fixture.driver.targets += target
                val rollbackFailure = IllegalStateException("partial target rollback")
                target.closeFailure = rollbackFailure
                val allocationFailure = IllegalArgumentException("partial target allocation")
                allocationFailure.addSuppressed(assertThrows(IllegalStateException::class.java) { target.close() })
                failures += allocationFailure
                throw NativeCanvasAllocationFailure(target, allocationFailure)
            }
            repeat(3) { index ->
                val failure = assertThrows(IllegalArgumentException::class.java) { fixture.prepare(tree) }
                assertSame(failures[index], failure)
                assertEquals(1, failure.suppressed.size)
                assertEquals(index + 1, fixture.device.retainedTargetCount())
            }
            val blocked = fixture.prepare(tree)
            assertTrue(blocked.drawCommands.isEmpty())
            fixture.device.cancel(blocked)
            tree.close()
            repeat(100) { fixture.device.poll() }
            assertEquals(3, fixture.driver.targets.size)
            assertEquals(3, fixture.device.retainedTargetCount())
            assertTrue(fixture.driver.targets.all { it.closeCalls == 1 })
            assertEquals(0, fixture.producers.single().captureCalls)
            assertEquals(0, fixture.producers.single().closeCalls)
            assertEquals(0, fixture.driver.finishCalls)

            fixture.driver.targets.forEach { it.closeFailure = null }
            fixture.device.closeAfterGuiDiscarded()
            assertEquals(0, fixture.device.retainedTargetCount())
            assertTrue(fixture.driver.targets.all { it.closeCalls == 2 })
            assertEquals(1, fixture.producers.single().closeCalls)
        }
    }

    @Test
    fun earlierPhysicalDestructionFailureRemainsVisibleAtTerminalDeviceClose() {
        val fixture = NativeCanvasFixture()
        val tree = fixture.tree()
        fixture.submit(fixture.prepare(tree))
        tree.close()
        val target = fixture.driver.targets.single()
        val failure = IllegalArgumentException("physical destruction")
        target.closeFailure = failure
        fixture.driver.signalAll()
        assertSame(failure, assertThrows(IllegalArgumentException::class.java) { fixture.device.poll() })
        assertEquals(1, target.closeCalls)
        assertEquals(1, fixture.device.retainedTargetCount())

        assertSame(failure, assertThrows(IllegalArgumentException::class.java) { fixture.device.closeAfterGuiDiscarded() })
        assertSame(failure, assertThrows(IllegalArgumentException::class.java) { fixture.device.closeAfterGuiDiscarded() })
        assertEquals(1, fixture.device.retainedTargetCount())
        assertEquals(1, fixture.driver.finishCalls)
        assertEquals(2, target.closeCalls)
    }

    @Test
    fun terminalDestructionRetryReleasesThePermitOnlyAfterPhysicalSuccess() {
        NativeCanvasFixture().use { fixture ->
            val tree = fixture.tree()
            fixture.submit(fixture.prepare(tree))
            tree.close()
            val target = fixture.driver.targets.single()
            val failure = IllegalArgumentException("physical destruction")
            target.closeFailure = failure
            fixture.driver.signalAll()
            assertSame(failure, assertThrows(IllegalArgumentException::class.java) { fixture.device.poll() })
            assertEquals(1, target.closeCalls)
            assertEquals(1, fixture.device.retainedTargetCount())
            assertEquals(0, fixture.producers.single().closeCalls)

            target.closeFailure = null
            fixture.device.closeAfterGuiDiscarded()
            fixture.device.closeAfterGuiDiscarded()
            assertEquals(0, fixture.device.retainedTargetCount())
            assertEquals(1, fixture.driver.finishCalls)
            assertEquals(2, target.closeCalls)
            assertEquals(1, fixture.producers.single().closeCalls)
        }
    }

    @Test
    fun failedDeviceFinishQuarantinesResourcesAndRepeatedCloseNeverClaimsSuccess() {
        val fixture = NativeCanvasFixture()
        val tree = fixture.tree()
        fixture.submit(fixture.prepare(tree))
        val failure = IllegalArgumentException("finish")
        fixture.driver.finishFailure = failure
        tree.close()
        assertSame(failure, assertThrows(IllegalArgumentException::class.java) { fixture.device.closeAfterGuiDiscarded() })
        val fencePolls = fixture.driver.fences.map { it.polls }
        fixture.driver.signalAll()
        repeat(100) {
            fixture.device.poll()
            fixture.device.consumed()
        }
        assertEquals(fencePolls, fixture.driver.fences.map { it.polls })
        assertTrue(fixture.driver.fences.all { it.closeCalls == 0 })
        assertEquals(1, fixture.device.retainedTargetCount())
        assertEquals(
            0,
            fixture.driver.targets
                .single()
                .closeCalls,
        )
        assertEquals(0, fixture.producers.single().closeCalls)
        assertTrue(
            fixture.producers
                .single()
                .captures
                .all { it.closeCalls == 0 },
        )
        assertSame(failure, assertThrows(IllegalArgumentException::class.java) { fixture.device.closeAfterGuiDiscarded() })
        assertEquals(1, fixture.driver.finishCalls)
        assertEquals(0, fixture.driver.drainCalls)
    }

    private fun token(
        presentation: NativeCanvasPresentation,
        index: Int = 0,
    ): NativeCanvasToken = presentation.drawCommands.filterIsInstance<DrawCommand.Platform>()[index].command as NativeCanvasToken
}
