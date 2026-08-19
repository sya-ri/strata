package dev.s7a.strata

import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.Node
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Verifies atomic terminal node binding ownership.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class NodeBindingTest {
    @Test
    fun onlyOneThreadClaimsAndReleaseRetiresTheNode() {
        val node = BindingNode()
        val start = CountDownLatch(1)
        val successes = AtomicInteger(0)
        val callbacks = AtomicInteger(0)
        val release = AtomicReference<(() -> Unit)?>(null)
        val failures = arrayOfNulls<Throwable>(2)
        val threads =
            (0 until 2).map { index ->
                Thread {
                    start.await()
                    runCatching { node.bindRuntime { callbacks.incrementAndGet() } }
                        .onSuccess { handle ->
                            successes.incrementAndGet()
                            release.compareAndSet(null, handle)
                        }.onFailure { failure -> failures[index] = failure }
                }
            }
        threads.forEach(Thread::start)
        start.countDown()
        threads.forEach(Thread::join)

        assertEquals(1, successes.get())
        assertTrue(failures.any { failure -> failure is IllegalStateException })
        node.invalidatePaint()
        assertEquals(1, callbacks.get())
        val handle = requireNotNull(release.get())
        handle()
        handle()
        assertThrows(IllegalStateException::class.java) { node.invalidatePaint() }
        assertThrows(IllegalStateException::class.java) { node.bindRuntime { } }
    }

    /**
     * Exposes protected invalidation for the binding test.
     */
    private class BindingNode : Node() {
        /**
         * Invalidates paint through the protected node contract.
         */
        fun invalidatePaint() {
            invalidate(DirtyMask.of(DirtyPhase.Paint))
        }
    }
}
