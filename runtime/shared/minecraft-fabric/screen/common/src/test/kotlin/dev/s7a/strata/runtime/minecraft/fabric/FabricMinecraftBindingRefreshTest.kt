package dev.s7a.strata.runtime.minecraft.fabric

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Exercises reentrant binding disposal and callback failure without loading a Minecraft client.
 */
internal class FabricMinecraftBindingRefreshTest {
    @Test
    fun removingTheCurrentAndNextBindingsPreservesRemainingNotificationOrder() {
        val first = Any()
        val second = Any()
        val third = Any()
        val bindings = linkedSetOf(first, second, third)
        val visited = mutableListOf<Any>()
        refreshMinecraftBindings(bindings) { binding ->
            visited += binding
            if (binding === first) {
                bindings.remove(first)
                bindings.remove(second)
            }
        }
        assertEquals(listOf(first, third), visited)
        assertEquals(setOf(third), bindings)
    }

    @Test
    fun terminalReleaseStopsEveryRemainingCallback() {
        val first = Any()
        val bindings = linkedSetOf(first, Any())
        val visited = mutableListOf<Any>()
        refreshMinecraftBindings(bindings) { binding ->
            visited += binding
            bindings.clear()
        }
        assertEquals(listOf(first), visited)
        assertEquals(emptySet<Any>(), bindings)
    }

    @Test
    fun callbacksCannotAdmitNewWorkIntoTheCurrentPoll() {
        val first = Any()
        val second = Any()
        val added = Any()
        val bindings = linkedSetOf(first, second)
        val visited = mutableListOf<Any>()
        refreshMinecraftBindings(bindings) { binding ->
            visited += binding
            bindings.add(added)
        }
        assertEquals(listOf(first, second), visited)
        visited.clear()
        refreshMinecraftBindings(bindings) { binding -> visited += binding }
        assertEquals(listOf(first, second, added), visited)
    }

    @Test
    fun callbackFailuresRemainPrimaryAndStopThePoll() {
        val first = Any()
        val failure = IllegalStateException("binding callback")
        val visited = mutableListOf<Any>()
        val actual =
            assertThrows(IllegalStateException::class.java) {
                refreshMinecraftBindings(linkedSetOf(first, Any())) { binding ->
                    visited += binding
                    throw failure
                }
            }
        assertSame(failure, actual)
        assertEquals(listOf(first), visited)
    }
}
