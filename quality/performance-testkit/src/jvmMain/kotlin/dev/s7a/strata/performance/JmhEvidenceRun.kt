package dev.s7a.strata.performance

import com.google.gson.JsonObject

/**
 * Detached validated invocation, with preserved artifact identities separate from controlled fixture inputs.
 */
internal data class JmhEvidenceRun(
    val receipt: JsonObject,
    val rows: Map<JmhEvidenceCase, JsonObject>,
    val sources: JsonObject,
    val targets: JsonObject,
    val inputs: JsonObject,
)
