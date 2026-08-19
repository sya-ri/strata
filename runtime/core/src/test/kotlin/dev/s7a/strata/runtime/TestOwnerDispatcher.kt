package dev.s7a.strata.runtime

import kotlinx.coroutines.CoroutineDispatcher
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

/**
 * Queues owner continuations for deterministic tests without executing them inline.
 */
internal class TestOwnerDispatcher : CoroutineDispatcher() {
    private val queue = LinkedBlockingQueue<Runnable>()

    override fun isDispatchNeeded(context: CoroutineContext): Boolean = true

    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) {
        queue.add(block)
    }

    /**
     * Runs one queued continuation when one arrives before [timeoutMillis].
     *
     * @return true when a continuation was run.
     */
    fun runNext(timeoutMillis: Long = 1000L): Boolean {
        val next = queue.poll(timeoutMillis, TimeUnit.MILLISECONDS) ?: return false
        next.run()
        return true
    }

    /**
     * Runs at most [maxTasks] immediately available continuations.
     *
     * @return the number of continuations run.
     */
    fun drain(maxTasks: Int = 1000): Int {
        var count = 0
        while (count < maxTasks) {
            val next = queue.poll() ?: return count
            next.run()
            count += 1
        }
        return count
    }
}
