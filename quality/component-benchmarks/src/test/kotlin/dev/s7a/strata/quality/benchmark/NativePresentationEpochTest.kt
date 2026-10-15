package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.performance.PerformanceProfile
import dev.s7a.strata.performance.PerformanceSelection
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

/**
 * Historical and paced raw invocations retain separate fixture identities across corpus and profile selection.
 */
internal class NativePresentationEpochTest {
    @Test
    internal fun repetitionsRequireOneActualEpochAndSelection() {
        val selection = PerformanceSelection(setOf("TextField", "TextArea"), "TextField")
        PerformanceProfile.entries.forEach { profile ->
            listOf(false, true).forEach { sampledImages ->
                NativePresentationEpoch.entries.forEach { epoch ->
                    val id = epoch.workloadId(selection, profile, sampledImages)
                    assertEquals(epoch, NativePresentationEpoch.fromWorkloadIds(List(3) { id }, selection, profile, sampledImages))
                    assertFails { NativePresentationEpoch.fromWorkloadId(id, PerformanceSelection(selection.ids), profile, sampledImages) }
                    assertFails { NativePresentationEpoch.fromWorkloadId(id, selection, profile, sampledImages.not()) }
                }
                val ids = NativePresentationEpoch.entries.map { it.workloadId(selection, profile, sampledImages) }
                assertFails { NativePresentationEpoch.fromWorkloadIds(ids, selection, profile, sampledImages) }
                assertFails { NativePresentationEpoch.fromWorkloadIds(emptyList(), selection, profile, sampledImages) }
                assertFails { NativePresentationEpoch.fromWorkloadId("unknown", selection, profile, sampledImages) }
            }
        }
    }

    @Test
    internal fun onlyExplicitPolicyComparisonAdmitsDifferentPacingOutcomes() {
        val selection = PerformanceSelection(setOf("TextField"))
        val ordinary = NativeComponentPerformanceEvidence.contract(selection, PerformanceProfile.Standard, false, NativePresentationEpoch.TimedPaced)
        assertEquals(true, "inactivity_mode" in ordinary.reportConditions && "pacing" in ordinary.phaseConditions)
        val policy = NativeComponentPerformanceEvidence.contract(selection, PerformanceProfile.Standard, false, NativePresentationEpoch.TimedPaced, NativeEvidenceCommand.PacingComparison)
        assertEquals(setOf("inactivity_mode"), policy.variantReportFields)
        assertEquals(ordinary.reportConditions - "inactivity_mode", policy.reportConditions)
        assertEquals(ordinary.phaseConditions - "pacing", policy.phaseConditions)
        assertEquals(ordinary.workloadId, policy.workloadId)
        assertEquals(3, policy.repetitions)
        assertEquals(NativeEvidenceCommand.PacingComparison, NativeEvidenceCommand.decode("pacing-comparison"))
        assertEquals(NativeEvidenceCommand.Comparison, NativeEvidenceCommand.decode("comparison"))
        assertFails { NativeEvidenceCommand.decode("unknown") }
    }
}
