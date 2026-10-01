package dev.s7a.strata.performance

/**
 * Browser evidence encoding with explicit units and bounded safe integer conversion.
 */
public object BrowserPerformanceJson {
    /**
     * Encodes common distribution totals as native JSON numbers rather than Kotlin Long objects.
     */
    public fun distribution(distribution: PerformanceDistribution): String {
        val result = js("({})")
        result.samples = distribution.samples
        result.p50_ns = safeNumber(distribution.p50)
        result.p95_ns = safeNumber(distribution.p95)
        result.p99_ns = safeNumber(distribution.p99)
        result.total_ns = safeNumber(distribution.total)
        result.max_ns = safeNumber(distribution.maximum)
        return JSON.stringify(result)
    }

    /**
     * Encodes both observed intervals while explicitly retaining unavailable CPU and allocation metrics.
     */
    public fun sample(sample: BrowserPerformanceSample): String {
        val result = js("({})")
        result.operation_wall = JSON.parse<dynamic>(distribution(sample.operation))
        result.action_to_animation_frame = JSON.parse<dynamic>(distribution(sample.animationFrame))
        result.cpu_ns = null
        result.allocated_bytes = null
        return JSON.stringify(result)
    }

    private fun safeNumber(value: Long): Double {
        require(value in 0..9_007_199_254_740_991L) { "Browser performance evidence exceeds JSON safe integer capacity" }
        return value.toDouble()
    }
}
