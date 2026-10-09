package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.performance.JmhWorkloadInventory

/**
 * Exact precollection inventory gate delegated to the generic JMH-generated metadata reader.
 */
internal object TextAreaCorpus {
    /**
     * Rejects any drift from the complete frozen 574-case public admission/control inventory before timing.
     */
    fun verify() {
        val expected = checkNotNull(javaClass.getResourceAsStream("/textarea-normalization-v1.jsonl")).bufferedReader(Charsets.UTF_8).use { it.readLines().toSet() }
        check(expected.size == 574)
        val actual =
            JmhWorkloadInventory.capture(
                listOf(TextAreaStateBenchmark::class.java, TextAreaRejectionBenchmark::class.java, TextAreaEditorBenchmark::class.java, TextFieldAdmissionBenchmark::class.java),
                setOf("avgt"),
            )
        check(actual == expected) { "The complete frozen TextArea inventory differs from generated JMH metadata" }
    }
}
