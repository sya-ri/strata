package dev.s7a.strata.gradle.performance

/**
 * Physical execution hosts and the test-only collector's own verification obligation.
 * Registration does not certify completed measurements on any host.
 */
internal enum class PerformanceModuleHost {
    Jvm,
    Minecraft,
    Web,
    Paper,
    Velocity,
    Collector,
}
