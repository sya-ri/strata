package dev.s7a.strata.performance

/**
 * Validated workload selection shared by CPU and loaded-host adapters.
 * Omitted selection retains the full registered matrix; explicit IDs cannot silently select nothing.
 * Adapters must still verify their complete registration before constructing a selection.
 *
 * @param available complete registered workload IDs for this adapter.
 * @param requested optional comma-separated IDs; unknown, empty and duplicate IDs are rejected.
 */
public class PerformanceSelection(
    available: Set<String>,
    requested: String? = null,
) {
    /**
     * Detached selected IDs in registration order, independent of the caller's argument ordering.
     */
    public val ids: Set<String>

    /**
     * Whether evidence covers a proper subset and must be kept separate from full-suite acceptance.
     */
    public val narrowed: Boolean

    init {
        require(available.isNotEmpty() && available.all { it.isNotBlank() && ',' !in it })
        val selected = requested?.split(',')?.map(String::trim)
        if (selected != null) {
            require(selected.isNotEmpty() && selected.all(String::isNotBlank)) { "Empty performance workload selection" }
            require(selected.distinct().size == selected.size) { "Duplicate performance workload selection" }
            require(selected.all { it in available }) { "Unknown performance workloads: ${selected.toSet() - available}" }
        }
        ids = available.filter { selected == null || it in selected }.toSet()
        narrowed = ids != available
    }
}
