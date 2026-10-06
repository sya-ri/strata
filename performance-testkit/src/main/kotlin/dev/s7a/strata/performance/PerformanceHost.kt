package dev.s7a.strata.performance

/**
 * Execution boundaries whose measurements are never interchangeable.
 */
public enum class PerformanceHost {
    /**
     * JVM session evaluation and portable rasterization.
     */
    Jvm,

    /**
     * Actual Minecraft client presentation.
     */
    Minecraft,

    /**
     * Real browser rendering, with the browser engine recorded separately.
     */
    Web,

    /**
     * Paper declarations and delivery to an actual client.
     */
    Paper,

    /**
     * Velocity declarations and delivery to an actual client.
     */
    @Suppress("unused") // Public remote-host evidence identity for downstream proxy fixtures.
    Velocity,
}
