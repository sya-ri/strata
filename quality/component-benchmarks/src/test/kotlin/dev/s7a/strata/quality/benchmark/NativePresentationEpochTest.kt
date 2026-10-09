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
}
