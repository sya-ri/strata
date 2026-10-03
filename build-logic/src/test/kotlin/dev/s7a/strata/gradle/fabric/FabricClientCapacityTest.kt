package dev.s7a.strata.gradle.fabric

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Verify changing resource admission, cancellation and memory boundaries without Minecraft or host-dependent thresholds.
 */
internal class FabricClientCapacityTest {
    private val gibibyte = 1024L * 1024 * 1024

    @Test
    fun `capacity grows beyond two and follows available memory and CPU`() {
        assertEquals(8, capacity(20, 0.0))
        assertEquals(4, capacity(10, 0.0))
        assertEquals(1, capacity(10, 0.95))
        assertEquals(1, capacity(3, 0.0))
        assertEquals(1, capacity(1, 1.0))
        assertEquals(1, fabricClientCapacity(-1, 2 * gibibyte, 2 * gibibyte, 32, -1.0, 16))
        assertEquals(1, fabricClientCapacity(0, 2 * gibibyte, 2 * gibibyte, 32, -1.0, 16))
    }

    @Test
    fun `lower capacity waits for admitted tasks rather than cancelling them`() {
        val limit = AtomicInteger(2)
        val waiting = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val gate = FabricClientCapacityGate({ limit.get().also { if (it == 1) waiting.countDown() } })
        try {
            gate.acquire("first")
            gate.acquire("second")
            limit.set(1)
            val third = executor.submit<Int> { gate.acquire("third") }
            assertTrue(waiting.await(2, TimeUnit.SECONDS))
            gate.release("first")
            assertThrows(TimeoutException::class.java) { third.get(50, TimeUnit.MILLISECONDS) }
            gate.release("second")
            assertEquals(1, third.get(2, TimeUnit.SECONDS))
        } finally {
            gate.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun `new resources admit waiting clients during the same invocation`() {
        val limit = AtomicInteger(0)
        val waiting = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val gate = FabricClientCapacityGate({ limit.get().also { waiting.countDown() } })
        try {
            val first = executor.submit<Int> { gate.acquire("first") }
            assertTrue(waiting.await(2, TimeUnit.SECONDS))
            limit.set(3)
            gate.release("unrelated completed task")
            assertEquals(3, first.get(2, TimeUnit.SECONDS))
            assertEquals(3, gate.acquire("second"))
            assertEquals(3, gate.acquire("third"))
        } finally {
            gate.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun `close wakes waiters and insufficient resources have a bounded deadline`() {
        val waiting = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val gate =
            FabricClientCapacityGate({
                waiting.countDown()
                0
            })
        try {
            val first = executor.submit<Int> { gate.acquire("first") }
            assertTrue(waiting.await(2, TimeUnit.SECONDS))
            gate.close()
            assertThrows(ExecutionException::class.java) { first.get(2, TimeUnit.SECONDS) }
            assertThrows(IllegalStateException::class.java) {
                FabricClientCapacityGate({ 0 }, timeoutMillis = 0).use { it.acquire("timeout") }
            }
        } finally {
            gate.close()
            executor.shutdownNow()
        }
    }

    private fun capacity(
        freeGibibytes: Long,
        cpuLoad: Double,
    ): Int = fabricClientCapacity(freeGibibytes * gibibyte, 2 * gibibyte, 2 * gibibyte, 32, cpuLoad, 8)
}
