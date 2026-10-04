package dev.s7a.strata.performance

/**
 * Shared diagnostic presets without another measurement engine.
 * Quick results have separate workload identities and cannot satisfy a standard evidence contract.
 * Consumers register their actual ordered viewport matrix and retain explicit workload selections.
 */
public enum class PerformanceProfile {
    /**
     * Preserves the caller's fixed sample, warm-up, repetition and viewport contract.
     */
    Standard,

    /**
     * One independent execution with at most three warm-up operations and ten samples per interval.
     */
    Quick,
    ;

    /**
     * Caps exploratory counts while preserving smaller intervals, including single initial frames.
     * Preparation deadlines and readiness checks remain unchanged.
     */
    public fun plan(standard: PerformancePlan = PerformancePlan()): PerformancePlan =
        when (this) {
            Standard -> standard
            Quick -> standard.copy(warmup = minOf(3, standard.warmup), samples = minOf(10, standard.samples), repetitions = 1)
        }

    /**
     * Selects one middle registered viewport for a quick run; standard collection preserves all entries.
     * GUI scales one through four therefore select scale three, without changing framebuffer size.
     */
    public fun <T> viewports(standard: List<T>): List<T> {
        require(standard.isNotEmpty() && standard.distinct().size == standard.size) { "Register unique nonempty viewports" }
        return when (this) {
            Standard -> standard.toList()
            Quick -> listOf(standard[standard.size / 2])
        }
    }

    /**
     * Gives exploratory evidence a distinct identity rather than weakening the standard contract.
     */
    public fun workloadId(standard: String): String {
        require(standard.isNotBlank())
        return when (this) {
            Standard -> standard
            Quick -> "$standard-quick"
        }
    }

    /**
     * Strict decoding at the diagnostic runner's configuration boundary.
     */
    public companion object {
        /**
         * Decodes the shared `strata.performance.quick` flag; absence preserves standard behavior.
         * Malformed values fail before collection rather than silently choosing another workload.
         */
        public fun fromQuickFlag(value: String?): PerformanceProfile = if (value?.toBooleanStrict() == true) Quick else Standard
    }
}
