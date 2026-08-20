package dev.s7a.strata.runtime.headless

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Verifies the headless work and cleanup failure contract independently of the retained tree.
 */
internal class HeadlessFailureCompositionTest {
    @Test
    fun successfulWorkWithCleanupFailureUsesCleanupIdentity() {
        val closeFailure = IllegalStateException("close")

        val thrown =
            assertThrows<IllegalStateException> {
                val result =
                    completeWithClose(
                        work = { Unit },
                        close = { throw closeFailure },
                    )
                assertEquals(Unit, result)
            }

        assertSame(closeFailure, thrown)
        assertEquals(0, thrown.suppressed.size)
    }

    @Test
    fun workFailureRemainsPrimaryAndCleanupGraphIsAddedOnce() {
        val workFailure = IllegalStateException("work")
        val detachFailure = IllegalArgumentException("detach")
        val disposeFailure = UnsupportedOperationException("dispose")
        detachFailure.addSuppressed(disposeFailure)

        val thrown =
            assertThrows<IllegalStateException> {
                completeWithClose(
                    work = { throw workFailure },
                    close = { throw detachFailure },
                )
            }

        assertSame(workFailure, thrown)
        assertEquals(1, thrown.suppressed.size)
        assertSame(detachFailure, thrown.suppressed.single())
        assertEquals(1, detachFailure.suppressed.size)
        assertSame(disposeFailure, detachFailure.suppressed.single())
    }

    @Test
    fun selfAndDirectDuplicateCleanupFailuresAreNotSuppressedAgain() {
        val workFailure = IllegalStateException("work")
        val cleanupFailure = IllegalArgumentException("cleanup")
        workFailure.addSuppressed(cleanupFailure)

        val thrown =
            assertThrows<IllegalStateException> {
                completeWithClose(
                    work = { throw workFailure },
                    close = { throw cleanupFailure },
                )
            }

        assertSame(workFailure, thrown)
        assertEquals(1, thrown.suppressed.size)
        assertSame(cleanupFailure, thrown.suppressed.single())
    }

    @Test
    fun sameCleanupInstanceAsWorkIsNotSuppressed() {
        val selfFailure = IllegalStateException("self")
        val selfThrown =
            assertThrows<IllegalStateException> {
                completeWithClose(
                    work = { throw selfFailure },
                    close = { throw selfFailure },
                )
            }

        assertSame(selfFailure, selfThrown)
        assertEquals(0, selfThrown.suppressed.size)
    }

    @Test
    fun cleanupFailureAppendsAfterAnExistingUnrelatedSuppression() {
        val workFailure = IllegalStateException("work")
        val unrelatedFailure = IllegalArgumentException("unrelated")
        val cleanupFailure = UnsupportedOperationException("cleanup")
        workFailure.addSuppressed(unrelatedFailure)

        val thrown =
            assertThrows<IllegalStateException> {
                completeWithClose(
                    work = { throw workFailure },
                    close = { throw cleanupFailure },
                )
            }

        assertSame(workFailure, thrown)
        assertEquals(2, thrown.suppressed.size)
        assertSame(unrelatedFailure, thrown.suppressed[0])
        assertSame(cleanupFailure, thrown.suppressed[1])
    }
}
