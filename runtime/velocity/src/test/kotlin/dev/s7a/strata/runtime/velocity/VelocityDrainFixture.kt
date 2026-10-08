package dev.s7a.strata.runtime.velocity

import dev.s7a.strata.runtime.velocity.VelocityScreensTest.Harness
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import java.lang.reflect.Field
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Native-free measurement adapter for the actual worker and captured scheduled action.
 * Requires --add-opens=java.base/java.util.concurrent=ALL-UNNAMED on the frozen JDK classpath.
 * Captures the JDK RunnableAdapter task before cancellation clears FutureTask.callable, then invokes that same action only on its original executor; no command drain or host continuation is copied into the fixture.
 * CPU collection uses the original queue; an optional untimed subclass counts actual poll invocations.
 */
@Suppress("TooManyFunctions") // Keeps the captured worker action, bounded controls and terminal owner cleanup together.
internal class VelocityDrainFixture(
    private val workload: VelocityDrainWorkload,
    instrumentProbes: Boolean = false,
) : AutoCloseable {
    private val fixture = Harness()
    private val service = field(VelocityScreens::class.java, "service").get(null) as VelocityScreenService
    private val executor = field(service.javaClass, "executor").get(service) as ScheduledExecutorService
    private val worker = field(service.javaClass, "owner").get(service) as Thread
    private val producer = Executors.newSingleThreadExecutor()
    private val requests = mutableListOf<CompletableFuture<Unit>>()
    private val calls = mutableListOf<Int>()
    private val probes = if (instrumentProbes) CommandProbes() else null
    private var processed = 0L
    private var closed = false
    private val ticker =
        runCatching {
            onOwner {
                val scheduled = field(service.javaClass, "ticker").get(service) as ScheduledFuture<*>
                val callable = checkNotNull(field(FutureTask::class.java, "callable").get(scheduled))
                val action = field(callable.javaClass, "task").get(callable) as Runnable
                check(scheduled.cancel(false))
                probes?.let { field(service.javaClass, "commands").set(service, it) }
                action
            }
        }.getOrElse { failure ->
            fixture.close()
            check(executor.awaitTermination(10, TimeUnit.SECONDS))
            producer.shutdownNow()
            check(producer.awaitTermination(10, TimeUnit.SECONDS))
            throw failure
        }

    /**
     * Runs consumer work on the existing UI executor, never on the coordinator or producer thread.
     */
    fun <T> onOwner(operation: () -> T): T {
        check((Thread.currentThread() === worker).not()) { "The coordinator must remain outside the UI executor." }
        return executor.submit(Callable(operation)).get(5, TimeUnit.MINUTES)
    }

    /**
     * Completes one exact burst, including actual queue submission and worker ticks, and verifies every future.
     * A full-plus-one burst requires two ticks; callback preparation and verification are part of this CPU corpus.
     */
    fun cycle(): Long {
        check(Thread.currentThread() === worker)
        prepare()
        repeat(maxOf(1, (workload.commands + 63) / 64)) { ticker.run() }
        verify()
        return processed
    }

    /**
     * Checks failure identity and cancellation without poisoning later callbacks or the host continuation.
     */
    fun verifyFailures() {
        val failure = IllegalStateException("command control")
        prepare(failure)
        repeat(maxOf(1, (workload.commands + 63) / 64)) { ticker.run() }
        requests.forEachIndexed { index, request ->
            when (index) {
                1 -> assertSame(failure, assertThrows(ExecutionException::class.java) { request.get(10, TimeUnit.SECONDS) }.cause)
                2 -> assertTrue(request.isCancelled)
                else -> request.get(10, TimeUnit.SECONDS)
            }
        }
        assertEquals((0 until workload.commands).filter { it != 2 }, calls)
        check(queue().isEmpty())
    }

    /**
     * Records post-empty admission separately from timing; final delivery may span different ticks on each runtime.
     * The return value preserves probes and delivered counts for both ticks, making the latency tradeoff explicit.
     */
    fun lateArrival(): List<Long> {
        val counter = checkNotNull(probes)
        calls.clear()
        requests.clear()
        counter.probes = 0
        counter.afterEmpty = {
            val added = producer.submit<List<CompletableFuture<Unit>>> { submit(7) }.get(10, TimeUnit.SECONDS)
            requests.addAll(added)
        }
        ticker.run()
        val firstCalls = calls.size.toLong()
        val firstProbes = counter.probes
        ticker.run()
        assertEquals((0 until 7).toList(), calls)
        requests.forEach { it.get(10, TimeUnit.SECONDS) }
        check(queue().isEmpty())
        return listOf(firstCalls, firstProbes, calls.size.toLong(), counter.probes - firstProbes)
    }

    /**
     * Returns untimed actual command polls for the current fixed burst.
     */
    fun probeCycle(): Long {
        val counter = checkNotNull(probes)
        counter.probes = 0
        cycle()
        return counter.probes
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            fixture.close()
            check(executor.awaitTermination(10, TimeUnit.SECONDS))
            requests.clear()
            calls.clear()
            check(queue().isEmpty())
            check((field(service.javaClass, "players").get(service) as Map<*, *>).isEmpty())
            check((field(service.javaClass, "terminated").get(service) as CompletableFuture<*>).isDone)
        } finally {
            producer.shutdownNow()
            check(producer.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    /**
     * Keeps only the current burst's futures and callback order, with no retained earlier application results.
     */
    private fun prepare(failure: Throwable? = null) {
        calls.clear()
        requests.clear()
        if (workload == VelocityDrainWorkload.BusyProducer && failure == null) {
            requests.add(
                service.execute(fixture.owner) {
                    calls.add(0)
                    val added = producer.submit<List<CompletableFuture<Unit>>> { submit(workload.commands - 1, offset = 1) }.get(10, TimeUnit.SECONDS)
                    requests.addAll(added)
                    Unit
                },
            )
        } else {
            requests.addAll(submit(workload.commands, failure = failure))
        }
    }

    /**
     * Uses the actual asynchronous API; only producer admission runs on the producer thread.
     */
    private fun submit(
        count: Int,
        offset: Int = 0,
        failure: Throwable? = null,
    ): List<CompletableFuture<Unit>> =
        List(count) { index ->
            val command = index + offset
            service
                .execute(fixture.owner) {
                    calls.add(command)
                    if (command == 1 && failure != null) throw failure
                }.also { if (command == 2 && failure != null) check(it.cancel(false)) }
        }

    /**
     * Verifies order, successful completion and queue release before dropping the current scalar/future results.
     */
    private fun verify() {
        requests.forEach { it.get(10, TimeUnit.SECONDS) }
        check(calls == (0 until workload.commands).toList())
        check(queue().isEmpty())
        processed += calls.size
    }

    private fun queue(): ArrayBlockingQueue<*> = field(service.javaClass, "commands").get(service) as ArrayBlockingQueue<*>

    private fun field(
        type: Class<*>,
        name: String,
    ): Field = type.getDeclaredField(name).apply { isAccessible = true }

    /**
     * Untimed observation of the actual runtime's queue calls; the producer hook runs after super.poll returns null.
     */
    private class CommandProbes : ArrayBlockingQueue<Any>(1024) {
        var probes = 0L
        var afterEmpty: (() -> Unit)? = null

        override fun poll(): Any? {
            probes++
            val command = super.poll()
            if (command == null) {
                val hook = afterEmpty
                afterEmpty = null
                hook?.invoke()
            }
            return command
        }
    }
}
