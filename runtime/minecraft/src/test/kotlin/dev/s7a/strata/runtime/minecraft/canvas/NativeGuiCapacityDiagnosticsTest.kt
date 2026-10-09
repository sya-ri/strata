package dev.s7a.strata.runtime.minecraft.canvas

import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Separates portable admission blockers and verifies failure snapshots without a loaded GPU.
 * Explicit consumer events, initialization, GUI completion and physical destruction remain independent.
 * The deterministic driver does not establish the cause of a loaded Minecraft failure.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class NativeGuiCapacityDiagnosticsTest {
    @Test
    fun changedPresentationsExhaustThreePermitsWithoutAnyConsumerEvenAfterInitializationCompletes() {
        NativeGuiResourceFixture().use { fixture ->
            val sets = List(3) {
                val set = fixture.initialized()
                queue(fixture, set)
                fixture.driver.signalAll()
                fixture.device.poll()
                set
            }
            sets.dropLast(1).forEach(fixture.gui::release)
            val polls = fixture.driver.fences.sumOf { it.polls }
            assertCapacityBeforeAllocation(fixture, "pendingGui=true", "initializationOutstanding=false", "guiCompletionOutstanding=false", "quarantined=false")
            assertEquals(polls, fixture.driver.fences.sumOf { it.polls })
            assertTrue(fixture.allocations.all { it.closeCalls == 0 })
            assertEquals(3, fixture.driver.fences.size)
            fixture.device.consumed()
            assertEquals(4, fixture.driver.fences.size)
            assertCapacityBeforeAllocation(fixture, "pendingGui=false", "guiCompletionOutstanding=true")
            fixture.driver.signalAll()
            fixture.device.poll()
            assertEquals(1, fixture.gui.retainedSetCount())
            assertEquals(2, fixture.allocations.count { it.destroyed })
            fixture.initialized()
            assertEquals(2, fixture.gui.retainedSetCount())
            assertEquals(0, fixture.driver.finishCalls)
        }
    }

    @Test
    fun actualConsumerWithUnsignalledGuiFencesCanExhaustTheSamePermits() {
        NativeGuiResourceFixture().use { fixture ->
            val initialization = ArrayList<NativeCanvasFixture.Fence>()
            val sets = List(3) {
                val set = fixture.initialized()
                initialization.add(fixture.driver.fences.last())
                queue(fixture, set)
                fixture.device.consumed()
                set
            }
            sets.dropLast(1).forEach(fixture.gui::release)
            initialization.forEach { it.signalled = true }
            repeat(4) { fixture.device.poll() }
            assertCapacityBeforeAllocation(fixture, "pendingGui=false", "initializationOutstanding=false", "guiCompletionOutstanding=true", "quarantined=false")
            assertEquals(6, fixture.driver.fences.size)
            assertTrue(fixture.allocations.all { it.closeCalls == 0 })
            fixture.driver.signalAll()
            fixture.device.poll()
            assertEquals(1, fixture.gui.retainedSetCount())
            fixture.initialized()
            assertEquals(2, fixture.gui.retainedSetCount())
            assertEquals(0, fixture.driver.finishCalls)
        }
    }

    @Test
    fun completedGuiFencesDoNotOverrideUnsignalledInitializationFences() {
        NativeGuiResourceFixture().use { fixture ->
            val sets = List(3) {
                val set = fixture.initialized()
                queue(fixture, set)
                fixture.device.consumed()
                fixture.driver.fences.last().signalled = true
                set
            }
            sets.dropLast(1).forEach(fixture.gui::release)
            repeat(4) { fixture.device.poll() }
            assertCapacityBeforeAllocation(fixture, "pendingGui=false", "initializationOutstanding=true", "guiCompletionOutstanding=false", "quarantined=false")
            assertTrue(fixture.allocations.all { it.closeCalls == 0 })
            fixture.driver.signalAll()
            fixture.device.poll()
            assertEquals(1, fixture.gui.retainedSetCount())
            fixture.initialized()
            assertEquals(0, fixture.driver.finishCalls)
        }
    }

    @Test
    fun signalledAndReleasedGenerationsRetainPermitsUntilPhysicalDestructionIsAcknowledged() {
        NativeGuiResourceFixture().use { fixture ->
            val sets = List(3) { fixture.initialized() }
            fixture.allocations.forEach { it.destroyOnClose = false }
            sets.forEach(fixture.gui::release)
            fixture.driver.signalAll()
            repeat(4) { fixture.device.poll() }
            val destructionPolls = fixture.allocations.sumOf { it.destructionPolls }
            assertCapacityBeforeAllocation(fixture, "pendingGui=false", "initializationOutstanding=false", "guiCompletionOutstanding=false", "referencesReleased=true", "resourceSample0Release=Requested")
            assertEquals(destructionPolls + 3, fixture.allocations.sumOf { it.destructionPolls })
            assertTrue(fixture.allocations.all { it.closeCalls == 1 })
            assertTrue(fixture.allocations.all { it.destroyed.not() })
            fixture.allocations.first().destroyed = true
            fixture.device.poll()
            fixture.initialized()
            assertEquals(3, fixture.gui.retainedSetCount())
            assertEquals(0, fixture.driver.finishCalls)
        }
    }

    @Test
    fun failedConsumerKeepsQuarantinedPermitsDespiteLaterFenceCompletion() {
        NativeGuiResourceFixture().use { fixture ->
            val sets = List(3) {
                val set = fixture.initialized()
                queue(fixture, set)
                set
            }
            sets.forEach(fixture.gui::release)
            fixture.device.failedGui()
            fixture.driver.signalAll()
            repeat(4) { fixture.device.poll() }
            assertCapacityBeforeAllocation(fixture, "pendingGui=false", "initializationOutstanding=false", "guiCompletionOutstanding=false", "quarantined=true", "referencesReleased=false")
            assertTrue(fixture.allocations.all { it.closeCalls == 0 })
            assertEquals(0, fixture.driver.finishCalls)
        }
    }

    @Test
    fun failedReservationSnapshotAddsNoFencePollOrSubmissionBeyondOrdinaryAdmission() {
        NativeGuiResourceFixture().use { fixture ->
            repeat(3) {
                val set = fixture.initialized()
                queue(fixture, set)
                fixture.device.consumed()
            }
            val polls = fixture.driver.fences.map { it.polls }
            val fences = fixture.driver.fences.size
            assertCapacityBeforeAllocation(fixture, "initializationOutstanding=true", "guiCompletionOutstanding=true")
            assertEquals(polls.map { it + 1 }, fixture.driver.fences.map { it.polls })
            assertEquals(fences, fixture.driver.fences.size)
            assertEquals(0, fixture.driver.finishCalls)
            assertEquals(0, fixture.driver.drainCalls)
        }
    }

    @Test
    fun failureSnapshotDistinguishesDeviceCapacityFromAnEmptyPresenter() {
        NativeGuiResourceFixture().use { fixture ->
            repeat(64) { fixture.initialized(ownerId = fixture.gui.createOwnerId()) }
            assertCapacityBeforeAllocation(fixture, expectedRetained = 64, expectedOwnerCount = 0)
            assertEquals(64, fixture.allocations.size)
            assertEquals(64, fixture.driver.fences.size)
            assertEquals(0, fixture.driver.finishCalls)
        }
    }

    @Test
    fun failureSnapshotBoundsLayerDetailsWithoutQueryingNativeResources() {
        NativeGuiResourceFixture().use { fixture ->
            repeat(3) { fixture.initialized(count = 9) }
            val diagnostic = assertCapacityBeforeAllocation(fixture, "layers=9", "resourceReferences=9", "resourceSample7Release=Live", "unsampledResourceReferences=1")
            assertTrue(diagnostic.contains("resourceSample8").not())
            assertTrue(fixture.allocations.all { it.closeCalls == 0 && it.destructionPolls == 0 })
            assertEquals(27, fixture.allocations.size)
            assertEquals(0, fixture.driver.finishCalls)
        }
    }

    private fun queue(
        fixture: NativeGuiResourceFixture,
        set: NativeGuiResourceSet,
    ) {
        fixture.gui.beginUse(set)
        fixture.gui.queued(set)
        fixture.gui.endUse(set)
    }

    private fun assertCapacityBeforeAllocation(
        fixture: NativeGuiResourceFixture,
        vararg state: String,
        expectedRetained: Int = 3,
        expectedOwnerCount: Int = 3,
    ): String {
        val allocations = fixture.allocations.size
        val failure = assertThrows(IllegalStateException::class.java) { fixture.initialized() }
        assertEquals("Portable GUI resource-set capacity is exhausted before presentation.", failure.message)
        val diagnostic = requireNotNull(failure.suppressed.single().message)
        assertTrue(diagnostic.contains("ownerSets=$expectedOwnerCount/3"))
        assertTrue(diagnostic.contains("deviceSets=$expectedRetained/64"))
        val rows = diagnostic.lineSequence().drop(1).toList()
        assertEquals(expectedRetained, rows.size)
        rows.forEach { row -> state.forEach { expected -> assertTrue(row.contains(expected), row) } }
        assertEquals(allocations, fixture.allocations.size)
        assertEquals(expectedRetained, fixture.gui.retainedSetCount())
        return diagnostic
    }
}
