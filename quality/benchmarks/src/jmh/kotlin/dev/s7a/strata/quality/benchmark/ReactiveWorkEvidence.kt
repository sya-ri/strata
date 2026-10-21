package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.performance.RuntimeWorkMonitor
import dev.s7a.strata.runtime.spi.RuntimeUiSession
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Verifies complete frame reuse and changed operations without requiring production diagnostic hooks.
 */
@OptIn(InternalStrataRuntimeApi::class)
public object ReactiveWorkEvidence {
    /**
     * Exercises every scene and reports optional legacy diagnostics as explicitly unavailable on current runtimes.
     */
    @JvmStatic
    public fun main(args: Array<String>) {
        require(args.isEmpty())
        println("scenario,frameReused,commands,diagnostics")
        ReactiveWorkload.entries.forEach { workload ->
            val state = ReactiveRenderingBenchmark.ReactiveSession()
            state.scenario = workload
            state.monitoring = true
            state.use { _ ->
                state.setup()
                val before = state.cleanFrame()
                val after = state.nextFrame()
                val reused = workload == ReactiveWorkload.Static || workload == ReactiveWorkload.MapEqual
                check((before === after) == reused) { "Unexpected presentation reuse for ${workload.name}." }
                // Virtual-list anchor preservation can publish a scroll revision for the following frame.
                val settled = state.cleanFrame()
                check(state.cleanFrame() === settled) { "The presentation must settle after ${workload.name}." }
                println("${workload.name},$reused,${after.drawCommands.size},${state.workSnapshot()}")
            }
        }
        verifyTeardownFailures()
    }

    private fun verifyTeardownFailures() {
        for (workFailure in listOf(null, IllegalArgumentException("benchmark work failure"))) {
            val state = ReactiveRenderingBenchmark.ReactiveSession()
            state.scenario = ReactiveWorkload.Single
            state.monitoring = true
            state.setup()
            val session =
                state.javaClass
                    .getDeclaredField("session")
                    .apply { isAccessible = true }
                    .get(state) as RuntimeUiSession
            session.use { _ ->
                val source =
                    state.javaClass
                        .getDeclaredField("source")
                        .apply { isAccessible = true }
                        .get(state) as BenchmarkStateSource<*>
                check(source.subscribed)
                val monitorField = state.javaClass.getDeclaredField("monitor").apply { isAccessible = true }
                (monitorField.get(state) as RuntimeWorkMonitor).close()
                val snapshotFailure = IllegalStateException("snapshot failure")
                val monitorFailure = IllegalStateException("monitor close failure")
                val diagnostics = FailingDiagnostics(snapshotFailure, monitorFailure)
                RuntimeWorkMonitor(diagnostics).use { monitor ->
                    monitorField.set(state, monitor)
                    val failure = runCatching { state.use { _ -> if (workFailure != null) throw workFailure } }.exceptionOrNull()
                    check(failure === (workFailure ?: snapshotFailure)) { "Benchmark work and snapshot failures must remain primary." }
                    check(diagnostics.closes == 1) { "Benchmark teardown skipped the diagnostic monitor close." }
                    check(snapshotFailure.suppressed.toList() == listOf(monitorFailure))
                    if (workFailure != null) check(workFailure.suppressed.toList() == listOf(snapshotFailure))
                    check(source.subscribed.not())
                    check(monitor.available.not() && monitorField.get(state) == null)
                    state.close()
                    check(diagnostics.closes == 1)
                }
            }
        }
    }

    /**
     * Supplies independent legacy snapshot and close failures to the existing compatibility reader.
     * Named methods are invoked reflectively through [RuntimeWorkMonitor].
     */
    private class FailingDiagnostics(
        private val snapshotFailure: Throwable,
        private val closeFailure: Throwable,
    ) : AutoCloseable {
        /**
         * Counts actual terminal calls made by the compatibility reader.
         */
        var closes: Int = 0
            private set

        /**
         * Returns the worker-owned diagnostic handle without acquiring another resource.
         */
        fun startRenderMonitoring(): FailingDiagnostics = this

        /**
         * Fails before returning evidence so teardown must still release both owners.
         */
        fun snapshot(): Any = throw snapshotFailure

        override fun close() {
            closes += 1
            throw closeFailure
        }
    }
}
