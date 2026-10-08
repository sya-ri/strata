package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.performance.PerformanceProfile
import dev.s7a.strata.performance.PerformanceSelection

/**
 * Native fixture identities keep historical frames separate from live pacing and restoration evidence.
 */
internal enum class NativePresentationEpoch(private val suffix: String) {
    Legacy("presented-v1"),
    Paced("paced-presented-v2"),
    ;

    /**
     * Returns the external identity for the selected corpus without changing its sampling profile.
     */
    internal fun workloadId(
        selection: PerformanceSelection,
        profile: PerformanceProfile,
        sampledImages: Boolean,
    ): String {
        val family = if (sampledImages) "native-sampled-images" else "native-components"
        val selected = if (selection.narrowed) "-selected" else ""
        return profile.workloadId("$family$selected-$suffix")
    }

    internal companion object {
        /**
         * Requires one epoch across every actual raw invocation before selecting a summary contract.
         */
        internal fun fromWorkloadIds(
            ids: List<String>,
            selection: PerformanceSelection,
            profile: PerformanceProfile,
            sampledImages: Boolean,
        ): NativePresentationEpoch {
            val epochs = ids.map { fromWorkloadId(it, selection, profile, sampledImages) }.distinct()
            require(epochs.size == 1) { "Mixed or missing native fixture epochs cannot certify one raw repetition group" }
            return epochs.single()
        }

        /**
         * Decodes an actual receipt identity; an unknown epoch or different selection cannot satisfy admission.
         */
        internal fun fromWorkloadId(
            id: String,
            selection: PerformanceSelection,
            profile: PerformanceProfile,
            sampledImages: Boolean,
        ): NativePresentationEpoch =
            requireNotNull(entries.singleOrNull { it.workloadId(selection, profile, sampledImages).contentEquals(id) }) {
                "Targeted or unknown evidence cannot satisfy the requested native acceptance"
            }
    }
}
