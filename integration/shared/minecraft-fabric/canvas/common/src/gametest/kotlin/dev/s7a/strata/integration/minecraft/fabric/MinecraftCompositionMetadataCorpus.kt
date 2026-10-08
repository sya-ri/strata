package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.geometry.IntSize

/**
 * Independent compiled native metadata cases, preserving ordinary Canvas updates and the actual presentation boundary.
 * Both supplied runtimes receive the same source bytes, original coordinates, case selection and GUI densities.
 */
internal class MinecraftCompositionMetadataCorpus : MinecraftNativePerformanceCorpus {
    override val family: String = "native-composition-metadata"
    override val caseIds: Set<String> = Case.entries.map { it.name }.toSet()

    override fun scene(
        id: String,
        viewport: IntSize,
        operations: Int,
    ): MinecraftNativePerformanceScene = MinecraftCompositionMetadataScene(Case.valueOf(id), viewport, operations)

    /**
     * Complete stationary/rebuilt/local/source/control/geometry matrix; controls keep existing identity and small-source paths.
     */
    internal enum class Case(
        val small: Boolean = false,
        val stationary: Boolean = false,
        val sourceExtent: Int = 128,
    ) {
        SameListSmall(small = true, stationary = true),
        SameListLarge(stationary = true),
        RebuiltSmall(small = true),
        RebuiltLarge,
        OneDirtyLarge,
        InsertLarge,
        RemoveLarge,
        TintChangedLarge,
        CutoffChangedLarge,
        ReplacementLarge,
        ScrollLarge,
        RepeatedTintsLarge,
        ReversedTintsLarge,
        UniqueTintsLarge,
        NestedClipsLarge,
        SmallSourceFallback(small = true, sourceExtent = 16),
    }
}
