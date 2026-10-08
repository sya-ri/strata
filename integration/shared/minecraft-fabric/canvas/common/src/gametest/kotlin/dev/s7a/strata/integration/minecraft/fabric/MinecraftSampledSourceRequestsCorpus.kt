package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.geometry.IntSize

/**
 * Independent compiled source-request matrix preserving original placements on baseline and candidate runtimes.
 * Direct unique-source controls stay within native owner capacity; the JVM corpus separately measures the 4096-identity capacity control.
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
    ) {
        RequestsOne(1, 1),
        Requests64Shared(64, 1),
        Requests64Sixteen(64, 16),
        Requests64Unique(64, 64),
        Requests4096Shared(4096, 1),
        Requests4096Sixteen(4096, 16),
        RequestsComposedShared(64, 1, composed = true),
        RequestsComposedSixteen(64, 16, composed = true),
        RequestsReplacedSixteen(64, 16, replacement = true),
    }
}
