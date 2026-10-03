package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.performance.PerformanceHost

/**
 * Decodes the physical registration boundary and its compiler publication formats outside collection.
 */
internal enum class RegisteredPerformanceHost(
    internal val boundary: PerformanceHost,
    internal val jvm: Boolean,
    internal val klib: Boolean,
) {
    Jvm(PerformanceHost.Jvm, true, false),
    Minecraft(PerformanceHost.Minecraft, true, false),
    Web(PerformanceHost.Web, false, true),
    Paper(PerformanceHost.Paper, true, false),
    Velocity(PerformanceHost.Velocity, true, false),

    /**
     * Collector self-contract verification covers both JVM and browser API publications.
     */
    Collector(PerformanceHost.Jvm, true, true),
}
