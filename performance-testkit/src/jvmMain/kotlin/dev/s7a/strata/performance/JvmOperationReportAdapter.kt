package dev.s7a.strata.performance

import com.google.gson.JsonObject
import java.nio.file.Path

/**
 * Optional fixture-owned supplemental report, discovered through a static `operationReport()` hook.
 * Use [JvmPerformanceRunner.warmup] and [JvmPerformanceRunner.sequence] on the fixture owner thread.
 * Preparation, assertions, serialization and surviving-resource cleanup belong outside each sample;
 * required lifecycle work belongs inside the declared complete operation.
 */
public interface JvmOperationReportAdapter {
    /**
     * Stable logical rows binding every selected JMH method and parameter combination.
     */
    public val rows: List<JvmOperationReportRow>

    /**
     * Resolves the actual external files consumed by this adapter outside sampling.
     * Defaults to all declared fixture inputs; additional consumed files require explicit labels.
     * Collection re-reads this mapping and every resolved input's bytes after the operation.
     */
    public val inputs: Map<String, Path> get() = JmhFixtureSelection.inputs()

    /**
     * Returns complete detached meter reports keyed by row ID, releasing fixtures on every exit.
     * Every row must contain exactly [PerformancePlan.samples] successful samples.
     */
    public fun collect(plan: PerformancePlan): Map<String, JsonObject>
}
