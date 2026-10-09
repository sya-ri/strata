package dev.s7a.strata.performance

import com.google.gson.JsonObject
import java.nio.file.Path

/**
 * Outer collector phase accounting, independent of JMH's operation clocks and samples.
 */
internal class JmhCpuCosts {
    private val started = System.nanoTime()
    private var harnessStarted = started
    private var harnessFinished = started

    /**
     * Marks the boundary after parent provenance/preparation and before JMH takes control.
     */
    internal fun startHarness() {
        harnessStarted = System.nanoTime()
    }

    /**
     * Marks complete JMH execution before parent archive preservation and receipt processing.
     */
    internal fun finishHarness() {
        harnessFinished = System.nanoTime()
    }

    /**
     * Preserves observed phase durations for the actual invocation after success publication.
     */
    internal fun publish(
        directory: Path,
        runId: String,
        context: JsonObject,
    ) {
        val finished = System.nanoTime()
        PerformanceJson.writeNew(
            directory.resolve("cpu-costs.json"),
            JsonObject().apply {
                addProperty("contract", "strata-jmh-cpu-costs-v1")
                addProperty("run_id", runId)
                add("context", context)
                addProperty("parent_preparation_seconds", seconds(started, harnessStarted))
                addProperty("harness_seconds", seconds(harnessStarted, harnessFinished))
                addProperty("parent_processing_seconds", seconds(harnessFinished, finished))
                addProperty("source", "System.nanoTime outer collector phase boundaries")
            },
        )
    }

    private fun seconds(
        before: Long,
        after: Long,
    ): Double {
        val elapsed = after - before
        check(0 <= elapsed) { "CPU collector phase clock moved backwards" }
        return elapsed / 1_000_000_000.0
    }
}
