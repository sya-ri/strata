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
    private val nanoTime: () -> Long = System::nanoTime,
) : AutoCloseable {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val admitted = mutableSetOf<String>()
    private var closed = false
    private var completions = 0L

    /**
     * Wait interruptibly for current capacity; fail after a stalled interval, not while admitted clients complete.
     */
    fun acquire(taskPath: String): Int =
        lock.withLock {
            check((taskPath in admitted).not()) { "Client task already admitted: $taskPath" }
            val timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            var deadline = nanoTime() + timeoutNanos
            var observedCompletions = completions
            check(closed.not()) { "Client admission is closed" }
            var limit = capacity()
            while (limit <= admitted.size) {
                check(closed.not()) { "Client admission is closed" }
                if (observedCompletions != completions) {
                    observedCompletions = completions
                    deadline = nanoTime() + timeoutNanos
                }
                val remaining = deadline - nanoTime()
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
            if (admitted.remove(taskPath)) completions = Math.incrementExact(completions)
            changed.signalAll()
        }

    override fun close(): Unit =
        lock.withLock {
            closed = true
            admitted.clear()
            changed.signalAll()
        }
}
