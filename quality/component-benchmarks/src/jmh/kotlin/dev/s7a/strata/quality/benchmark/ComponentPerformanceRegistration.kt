package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.performance.PerformanceCoverage
import dev.s7a.strata.performance.PerformanceInventory

/**
 * Detached executable registrations and source ownership supplied to the shared selection gate.
 */
internal class ComponentPerformanceRegistration(
    internal val coverage: PerformanceCoverage,
    internal val inventory: PerformanceInventory,
)
