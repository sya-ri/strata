package dev.s7a.strata.runtime.minecraft.canvas

import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.runtime.FrameTime
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Verifies actual current-batch resolution, queued lifetime cutoffs and zero/one/many owner isolation. */
@OptIn(InternalStrataRuntimeApi::class)
internal class NativeCanvasTargetIndexTest {
    @Test
    fun repeatedOccurrencesResolveTheOriginalOrderedTargetsThroughQueuedDetachmentAndReload() {
        for (size in listOf(1, 16, 64)) {
            NativeCanvasFixture().use { fixture ->
                val trees = List(size) { fixture.tree() }
                val requests = trees.flatMap { fixture.frame(it) }.filterIsInstance<DrawCommand.Platform>()
                val commands = List(4096) { index -> requests[index % size].copy(bounds = IntRect(index % 8, 0, index % 8 + 2, 2)) }
                val presentation = fixture.device.prepare(commands, FrameTime(1L), 1)
                val tokens = presentation.drawCommands.filterIsInstance<DrawCommand.Platform>().map { it.command as NativeCanvasToken }
                assertEquals(size, fixture.driver.targets.size)
                assertThrows(IllegalStateException::class.java) { fixture.device.target(presentation, tokens.first()) }
                fixture.device.queue(presentation)
                tokens.forEachIndexed { index, token -> assertSame(fixture.driver.targets[index % size], fixture.device.target(presentation, token)) }
                val foreign = NativeCanvasToken(tokens.first().deviceId, tokens.first().attachmentId, tokens.first().generation, tokens.first().physicalSize)
                assertThrows(IllegalStateException::class.java) { fixture.device.target(presentation, foreign) }
                trees.forEach { it.close() }
                fixture.device.reload()
                fixture.driver.signalAll()
                fixture.device.poll()
                assertEquals(size, fixture.device.retainedTargetCount())
                tokens.forEachIndexed { index, token -> assertSame(fixture.driver.targets[index % size], fixture.device.target(presentation, token)) }
                val original = presentation.capture()
                fixture.device.consumed()
                assertThrows(IllegalStateException::class.java) { fixture.device.target(presentation, tokens.first()) }
                fixture.driver.signalAll()
                fixture.device.poll()
                assertEquals(0, fixture.device.retainedTargetCount())
                assertEquals(original, presentation.capture())
            }
        }
    }

    @Test
    fun ownerThreadForeignDeviceAndExpiredBatchChecksRemainBeforeTargetBorrow() {
        NativeCanvasFixture().use { fixture ->
            NativeCanvasFixture().use { other ->
                val tree = fixture.tree()
                val first = fixture.prepare(tree)
                val token = (first.drawCommands.single() as DrawCommand.Platform).command as NativeCanvasToken
                val otherPresentation = other.prepare(other.tree())
                fixture.device.queue(first)
                assertThrows(IllegalStateException::class.java) { fixture.device.target(otherPresentation, token) }
                val wrongThread = FutureTask { assertThrows(IllegalStateException::class.java) { fixture.device.target(first, token) } }
                Thread(wrongThread).start()
                wrongThread.get(30L, TimeUnit.SECONDS)
                fixture.device.consumed()
                fixture.driver.signalAll()
                fixture.device.poll()
                val next = fixture.prepare(tree)
                fixture.device.queue(next)
                assertThrows(IllegalStateException::class.java) { fixture.device.target(first, token) }
                assertThrows(IllegalStateException::class.java) { fixture.device.target(next, token) }
                fixture.device.consumed()
                other.device.cancel(otherPresentation)
            }
        }
    }

    @Test
    fun cancelledAndFailedBatchesReleaseLookupMembershipWithoutResurrectingOldTargets() {
        for (failed in listOf(false, true)) {
            NativeCanvasFixture().use { fixture ->
                val trees = List(16) { fixture.tree() }
                val commands = trees.flatMap { fixture.frame(it) }
                val presentation = fixture.device.prepare(commands, FrameTime(1L), 1)
                val token = (presentation.drawCommands.first() as DrawCommand.Platform).command as NativeCanvasToken
                if (failed) {
                    fixture.device.queue(presentation)
                    fixture.device.failedGui()
                } else {
                    fixture.device.cancel(presentation)
                }
                assertThrows(IllegalStateException::class.java) { fixture.device.target(presentation, token) }
                trees.forEach { it.close() }
                fixture.device.closeAfterGuiDiscarded()
                assertEquals(0, fixture.device.retainedTargetCount())
                assertEquals(16, presentation.capture().size)
            }
        }
    }
}
