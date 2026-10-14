package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.performance.JmhFixtureSelection
import dev.s7a.strata.performance.JvmOperationReportAdapter
import dev.s7a.strata.performance.JvmPerformanceInputs
import java.nio.file.Path

/**
 * Resolves the original component corpus's library, font and API control inputs for every cleanup adapter.
 * Additional selected inputs retain their original labels and cannot overwrite a required control.
 */
internal fun JvmOperationReportAdapter.cleanupOperationInputs(): Map<String, Path> {
    val required =
        JvmPerformanceInputs.read(Path.of(checkNotNull(System.getProperty("strata.performance.inputs")))) +
            mapOf(
                "cc0-geometric-font" to Path.of(checkNotNull(System.getProperty("strata.performance.fontFixture"))),
                "component-api" to Path.of(checkNotNull(javaClass.getResource("/component-api.tsv")).toURI()),
                "runtime-api" to Path.of(checkNotNull(javaClass.getResource("/runtime-api.tsv")).toURI()),
            )
    val additional = JmhFixtureSelection.inputs()
    require(required.keys.intersect(additional.keys).isEmpty()) { "Fixture input labels overlap required control inputs" }
    return required + additional
}
