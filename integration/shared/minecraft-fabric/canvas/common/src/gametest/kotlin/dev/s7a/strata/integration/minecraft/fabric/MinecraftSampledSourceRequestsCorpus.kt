package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.geometry.IntSize

/**
 * Independent compiled source-request matrix preserving original placements on baseline and candidate runtimes.
 * The dense unique-source control includes native owner-capacity exhaustion and the complete ordered portable fallback.
 */
internal class MinecraftSampledSourceRequestsCorpus : MinecraftNativePerformanceCorpus {
    override val family: String = "native-source-requests"
    override val caseIds: Set<String> = Case.entries.map { it.name }.toSet()

    override fun scene(
        id: String,
        viewport: IntSize,
        operations: Int,
    ): MinecraftNativePerformanceScene = MinecraftSampledSourceRequestsScene(Case.valueOf(id), viewport, operations)

    /**
     * Original placement and referential source dimensions, with preparation-triggering replacement as a separate workload.
     * Composed cases retain all ordered tint passes and share their source identities across output tiles.
     */
    internal enum class Case(
        val occurrences: Int,
        val identities: Int,
        val composed: Boolean = false,
        val replacement: Boolean = false,
        val expectedNativeSources: Int = identities,
    ) {
        RequestsOne(1, 1),
        Requests64Shared(64, 1),
        Requests64Sixteen(64, 16),
        Requests64Unique(64, 64),
        Requests4096Shared(4096, 1),
        Requests4096Sixteen(4096, 16),
        Requests4096Unique(4096, 4096, expectedNativeSources = 256),
        RequestsComposedShared(64, 1, composed = true),
        RequestsComposedSixteen(64, 16, composed = true),
        RequestsReplacedSixteen(64, 16, replacement = true),
    }
}
