package dev.s7a.strata.runtime.minecraft

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * Verifies owner-thread identity transactions and terminal reference release for button hover.
 */
internal class MinecraftButtonHoverCoordinatorTest {
    @Test
    fun identityDiffEntersExitsForgetsAndAbandonsWithoutDeadNodeInvalidation() {
        val coordinator = MinecraftButtonHoverCoordinator.create()
        val first = Target()
        val second = Target()

        coordinator.beginMove()
        coordinator.offer(first)
        coordinator.offer(first)
        coordinator.offer(second)
        coordinator.finishMove()
        assertTrue(first.hovered)
        assertTrue(second.hovered)
        assertEquals(1, first.transitions)
        assertEquals(1, second.transitions)
        assertEquals(1, first.calls)
        assertEquals(1, second.calls)

        coordinator.beginMove()
        coordinator.offer(second)
        coordinator.finishMove()
        assertFalse(first.hovered)
        assertTrue(second.hovered)
        assertEquals(2, first.transitions)
        assertEquals(1, second.transitions)
        assertEquals(2, first.calls)
        assertEquals(1, second.calls)

        coordinator.forget(second)
        coordinator.beginMove()
        coordinator.finishMove()
        assertTrue(second.hovered)
        assertEquals(1, second.transitions)
        assertEquals(1, second.calls)

        coordinator.abandon()
        assertTrue(second.hovered)
        assertThrows(IllegalStateException::class.java) { coordinator.beginMove() }
    }

    @Test
    fun coordinatorRejectsWrongThreadOperations() {
        val coordinator = MinecraftButtonHoverCoordinator.create()
        val task =
            FutureTask<Throwable?> {
                runCatching { coordinator.beginMove() }.exceptionOrNull()
            }
        val runner = Thread(task)
        try {
            runner.start()
            assertTrue(task.get(5, TimeUnit.SECONDS) is IllegalStateException)
        } finally {
            runner.join(5_000)
        }
        coordinator.abandon()
    }

    private class Target : MinecraftButtonHoverCoordinator.Target {
        var hovered = false
        var transitions = 0
        var calls = 0

        override fun isEnabledForHover(): Boolean = true

        override fun setHoveredFromCoordinator(value: Boolean) {
            calls += 1
            if (hovered != value) transitions += 1
            hovered = value
        }
    }
}
