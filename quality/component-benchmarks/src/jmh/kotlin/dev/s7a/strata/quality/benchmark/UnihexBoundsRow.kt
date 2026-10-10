package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.performance.JmhPerformanceRunner

/**
 * Typed decoding boundary for the frozen comparison table, shared by registry admission and untimed method tracing.
 * External operation/advance names are decoded once; runtime workload selection uses these enums.
 *
 * @property id immutable comparison-row identity.
 * @property fixture geometry enum, absent for a single-row control.
 * @property control control enum, absent for a geometry operation.
 * @property operation complete measured consumer boundary.
 * @property advance release capability, or explicitly not applicable to a scalar/control operation.
 */
internal data class UnihexBoundsRow(
    val id: String,
    val fixture: UnihexBoundsFixture?,
    val control: UnihexBoundsControl?,
    val operation: Operation,
    val advance: Advance,
) {
    /**
     * Exact generated JMH identity for the standard average-time mode.
     */
    internal fun identity(): String {
        val parameters = buildMap {
            control?.let { put("control", it.name) }
            fixture?.let { put("fixture", it.name) }
            if (control == null && advance != Advance.NotApplicable) put("fractional", (advance == Advance.Fractional).toString())
        }
        val method =
            when (operation) {
                Operation.NaturalBounds -> "UnihexBoundsBenchmark.naturalBounds"
                Operation.PublicUncachedGlyph -> "UnihexBoundsBenchmark.publicUncachedGlyph"
                Operation.CompleteDirtyTextFrame -> "UnihexBoundsBenchmark.completeDirtyTextFrame"
                Operation.Control -> "UnihexBoundsControlBenchmark.control"
            }
        return JmhPerformanceRunner.workloadIdentity("dev.s7a.strata.quality.benchmark.$method", "avgt", parameters)
    }

    /**
     * Consumer boundaries named by the external comparison table.
     */
    internal enum class Operation {
        NaturalBounds,
        PublicUncachedGlyph,
        CompleteDirtyTextFrame,
        Control,
    }

    /**
     * Actual native advance capability, independent of source geometry.
     *
     * @property wireName external table spelling decoded at this boundary.
     */
    internal enum class Advance(
        val wireName: String,
    ) {
        NotApplicable("N/A"),
        Integer("Integer"),
        Fractional("Fractional"),
    }

    /**
     * Reads detached immutable rows; it adds no runtime cache or request-history state.
     */
    internal companion object {
        /**
         * Decodes original external text into the fixed typed consumer registry.
         */
        internal fun read(): List<UnihexBoundsRow> =
            UnihexBoundsAssets
                .bytes("comparisons.tsv")
                .toString(Charsets.UTF_8)
                .lineSequence()
                .drop(1)
                .filter { it.isNotEmpty() }
                .map { line ->
                    val values = line.split('\t')
                    val operation = Operation.valueOf(values[2])
                    UnihexBoundsRow(
                        values[0],
                        if (operation == Operation.Control) null else UnihexBoundsFixture.valueOf(values[1]),
                        if (operation == Operation.Control) UnihexBoundsControl.valueOf(values[1]) else null,
                        operation,
                        Advance.entries.single { it.wireName == values[3] },
                    )
                }.toList()
    }
}
