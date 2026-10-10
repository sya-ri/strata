package dev.s7a.strata.quality.benchmark

import com.google.gson.JsonObject
import dev.s7a.strata.input.TextInputEvent

/**
 * Deterministic source-expression work for the pinned eager baseline and lazy candidate.
 * Counts are declared construction/scan work, not measured surviving allocation, CPU or latency.
 * Both models are emitted by the same immutable fixture; actual JVM work remains in JMH/GC receipts.
 */
internal object PreeditSourceWork {
    /**
     * Tracks validation separately from output append/copy, buffer, materialization and accepted wrapper work.
     * All arithmetic uses bounded current input; raw rejection and block rejection precede either buffer model.
     */
    internal fun describe(
        event: TextInputEvent.Preedit,
        remaining: Int,
    ): JsonObject {
        require(0 <= remaining)
        val work = Work()
        val budget = 2L * remaining
        if (budget < event.fullText.length || budget + 1 < event.blocks.size) return work.report()
        if (inspectBlocks(event, budget, work).not()) return work.report()
        work.baselineBuffers = 1
        var offset = 0
        var length = 0
        while (offset < event.fullText.length) {
            val scalar = event.fullText.codePointAt(offset)
            work.fullScalarChecks++
            if (accepted(scalar).not()) return work.report()
            val hardBreak = isBreak(scalar)
            val units = if (hardBreak) 1 else Character.charCount(scalar)
            if (remaining - length < units) return work.report()
            work.baselineOutputUnits += units
            if (hardBreak && scalar != 10 && work.candidateBuffers == 0) {
                work.candidateBuffers = 1
                work.candidateOutputUnits = offset.toLong()
                work.candidatePrefixUnits = offset.toLong()
            }
            if (work.candidateBuffers == 1) work.candidateOutputUnits += units
            length += units
            offset += if (hardBreak) 1 else units
            if (scalar == 13 && offset < event.fullText.length && event.fullText[offset] == '\n') offset++
        }
        if (PreeditReference.normalize(event, remaining) != null) {
            work.baselineMaterializations = 1
            work.candidateMaterializations = work.candidateBuffers
            work.wrappers = 1
        }
        return work.report()
    }

    private fun inspectBlocks(
        event: TextInputEvent.Preedit,
        budget: Long,
        work: Work,
    ): Boolean {
        var offset = 0L
        for (block in event.blocks) {
            offset += block.length
            if (budget < offset || Int.MAX_VALUE < offset) return false
            var index = 0
            while (index < block.length) {
                val scalar = block.codePointAt(index)
                work.blockScalarChecks++
                if (accepted(scalar).not()) return false
                index += Character.charCount(scalar)
            }
            work.blockAgreementRequests++
        }
        return true
    }

    private fun isBreak(scalar: Int): Boolean = scalar in setOf(10, 13, 11, 12, 0x85, 0x2028, 0x2029)
    private fun accepted(scalar: Int): Boolean =
        (scalar in 0xD800..0xDFFF).not() && (isBreak(scalar) || (32 <= scalar && scalar != 127 && scalar != 0xA7))

    private class Work {
        var blockScalarChecks = 0
        var fullScalarChecks = 0
        var blockAgreementRequests = 0
        var baselineBuffers = 0
        var candidateBuffers = 0
        var baselineOutputUnits = 0L
        var candidateOutputUnits = 0L
        var candidatePrefixUnits = 0L
        var baselineMaterializations = 0
        var candidateMaterializations = 0
        var wrappers = 0

        fun report(): JsonObject = JsonObject().apply {
            addProperty("measured", false)
            addProperty("contract", "pinned-source-expression-work-v1")
            addProperty("block_scalar_checks", blockScalarChecks)
            addProperty("full_scalar_checks", fullScalarChecks)
            addProperty("block_agreement_requests", blockAgreementRequests)
            addProperty("accepted_wrapper_constructions", wrappers)
            addProperty("baseline_output_units", baselineOutputUnits)
            addProperty("candidate_output_units", candidateOutputUnits)
            addProperty("candidate_validated_prefix_copy_units", candidatePrefixUnits)
            addProperty("baseline_buffer_constructions", baselineBuffers)
            addProperty("candidate_buffer_constructions", candidateBuffers)
            addProperty("baseline_result_materialization_calls", baselineMaterializations)
            addProperty("candidate_result_materialization_calls", candidateMaterializations)
        }
    }
}
