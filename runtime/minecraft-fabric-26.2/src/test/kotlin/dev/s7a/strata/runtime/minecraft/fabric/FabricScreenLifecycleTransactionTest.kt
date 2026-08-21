package dev.s7a.strata.runtime.minecraft.fabric

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Verifies deferred native lifecycle ordering without loading a Minecraft client.
 */
internal class FabricScreenLifecycleTransactionTest {
    @Test
    fun defersLifecycleInOrderAndExposesPendingExit() {
        val trace = mutableListOf<Event>()
        val transaction = transaction(trace)

        val result =
            transaction.run {
                trace += Event.Operation
                assertFalse(transaction.hasPendingExit())
                transaction.requestAttach()
                transaction.requestAttach()
                assertFalse(transaction.hasPendingExit())
                transaction.requestDetach()
                assertTrue(transaction.hasPendingExit())
                "result"
            }

        assertEquals("result", result)
        assertEquals(listOf(Event.Operation, Event.Attach, Event.Detach), trace)
        assertFalse(transaction.isActive())
    }

    @Test
    fun lifecycleCallbackMayQueueAnotherDeferredTransition() {
        val trace = mutableListOf<Event>()
        lateinit var transaction: FabricScreenLifecycleTransaction
        transaction =
            FabricScreenLifecycleTransaction.create(
                {
                    trace += Event.Attach
                    transaction.requestDetach()
                },
                { trace += Event.Detach },
                { trace += Event.Close },
                { trace += Event.Navigate },
            )

        transaction.run { transaction.requestAttach() }

        assertEquals(listOf(Event.Attach, Event.Detach), trace)
    }

    @Test
    fun closeThenNavigateIsOrderedCoalescedAndTerminal() {
        val trace = mutableListOf<Event>()
        lateinit var transaction: FabricScreenLifecycleTransaction
        transaction =
            FabricScreenLifecycleTransaction.create(
                { trace += Event.Attach },
                { trace += Event.Detach },
                { trace += Event.Close },
                {
                    trace += Event.Navigate
                    transaction.requestDetach()
                },
            )

        transaction.run {
            transaction.requestDetach()
            transaction.requestClose()
            transaction.requestCloseThenNavigate()
            transaction.requestCloseThenNavigate()
        }
        transaction.run { transaction.requestCloseThenNavigate() }

        assertEquals(listOf(Event.Close, Event.Navigate), trace)
        assertThrows(IllegalStateException::class.java) {
            transaction.run { transaction.requestAttach() }
        }
    }

    @Test
    fun plainCloseCanBeFollowedByOneNavigationWithoutRepeatingCleanup() {
        val trace = mutableListOf<Event>()
        val transaction = transaction(trace)

        transaction.run { transaction.requestClose() }
        transaction.run { transaction.requestCloseThenNavigate() }
        transaction.run { transaction.requestCloseThenNavigate() }

        assertEquals(listOf(Event.Close, Event.Navigate), trace)
    }

    @Test
    fun cleanupFailureRemainsPrimaryAndNavigationFailureIsSuppressedOnce() {
        val cleanup = IllegalStateException("cleanup")
        val navigation = IllegalArgumentException("navigation")
        val transaction =
            FabricScreenLifecycleTransaction.create(
                {},
                {},
                { throw cleanup },
                { throw navigation },
            )

        val actual =
            assertThrows(IllegalStateException::class.java) {
                transaction.run { transaction.requestCloseThenNavigate() }
            }

        assertSame(cleanup, actual)
        assertEquals(1, actual.suppressed.size)
        assertSame(navigation, actual.suppressed.single())
        transaction.run { transaction.requestCloseThenNavigate() }
    }

    @Test
    fun operationFailureRemainsPrimaryWhenDeferredCleanupAlsoFails() {
        val operation = IllegalStateException("operation")
        val cleanup = IllegalArgumentException("cleanup")
        val transaction =
            FabricScreenLifecycleTransaction.create(
                {},
                {},
                { throw cleanup },
                {},
            )

        val actual =
            assertThrows(IllegalStateException::class.java) {
                transaction.run {
                    transaction.requestClose()
                    throw operation
                }
            }

        assertSame(operation, actual)
        assertEquals(1, actual.suppressed.size)
        assertSame(cleanup, actual.suppressed.single())
    }

    @Test
    fun rejectsReentrancyAndWrongThreadUse() {
        val transaction = transaction(mutableListOf())
        transaction.run {
            assertThrows(IllegalStateException::class.java) { transaction.run {} }
        }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val failure =
                executor
                    .submit<Throwable?> {
                        runCatching { transaction.isActive() }.exceptionOrNull()
                    }.get(5, TimeUnit.SECONDS)
            assertTrue(failure is IllegalStateException)
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun suppressionRejectsIdentityDuplicatesAndThrowableCycles() {
        val primary = IllegalStateException("primary")
        FabricMinecraftFailures.addSuppressed(primary, primary)
        assertEquals(0, primary.suppressed.size)

        val secondary = IllegalArgumentException("secondary")
        FabricMinecraftFailures.addSuppressed(primary, secondary)
        FabricMinecraftFailures.addSuppressed(primary, secondary)
        assertEquals(1, primary.suppressed.size)
        assertSame(secondary, primary.suppressed.single())

        val cyclicPrimary = IllegalStateException("cyclic-primary")
        val cause = IllegalArgumentException("cause", cyclicPrimary)
        FabricMinecraftFailures.addSuppressed(cyclicPrimary, cause)
        assertEquals(0, cyclicPrimary.suppressed.size)
    }

    private fun transaction(trace: MutableList<Event>): FabricScreenLifecycleTransaction =
        FabricScreenLifecycleTransaction.create(
            { trace += Event.Attach },
            { trace += Event.Detach },
            { trace += Event.Close },
            { trace += Event.Navigate },
        )

    private enum class Event {
        Operation,
        Attach,
        Detach,
        Close,
        Navigate,
    }
}
