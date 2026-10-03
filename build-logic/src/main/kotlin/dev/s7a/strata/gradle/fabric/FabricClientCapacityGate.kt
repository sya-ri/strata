package dev.s7a.strata.gradle.fabric

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Own task admission until completion; resource pressure delays new clients without interrupting admitted work.
 */
internal class FabricClientCapacityGate(
    private val capacity: () -> Int,
    private val timeoutMillis: Long = TimeUnit.MINUTES.toMillis(5),
) : AutoCloseable {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val admitted = mutableSetOf<String>()
    private var closed = false

    /**
     * Wait interruptibly for current capacity, failing rather than hanging indefinitely under resource pressure.
     */
    fun acquire(taskPath: String): Int =
        lock.withLock {
            check((taskPath in admitted).not()) { "Client task already admitted: $taskPath" }
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            check(closed.not()) { "Client admission is closed" }
            var limit = capacity()
            while (limit <= admitted.size) {
                check(closed.not()) { "Client admission is closed" }
                val remaining = deadline - System.nanoTime()
                check(0 < remaining) { "Timed out waiting for client resources: $taskPath" }
                changed.awaitNanos(minOf(remaining, TimeUnit.SECONDS.toNanos(1)))
                check(closed.not()) { "Client admission is closed" }
                limit = capacity()
            }
            admitted.add(taskPath)
            limit
        }

    /**
     * Release on every terminal task result, including failure and cancellation; unrelated events are harmless.
     */
    fun release(taskPath: String): Unit =
        lock.withLock {
            admitted.remove(taskPath)
            changed.signalAll()
        }

    override fun close(): Unit =
        lock.withLock {
            closed = true
            admitted.clear()
            changed.signalAll()
        }
}
