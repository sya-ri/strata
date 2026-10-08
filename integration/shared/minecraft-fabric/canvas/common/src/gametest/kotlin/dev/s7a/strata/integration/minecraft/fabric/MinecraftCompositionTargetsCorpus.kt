package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.geometry.IntSize

/**
 * Complete native target/pass matrix with ordinary Canvas revisions and original immutable source pixels.
 * Collector selection uses the same compiled case inventory and control conditions on both actual runtimes.
 */
internal class MinecraftCompositionTargetsCorpus : MinecraftNativePerformanceCorpus {
    override val family: String = "native-composition-targets"
    override val caseIds: Set<String> = Case.entries.map { it.name }.toSet()

    override fun scene(
        id: String,
        viewport: IntSize,
        operations: Int,
    ): MinecraftNativePerformanceScene = MinecraftCompositionTargetsScene(Case.valueOf(id), viewport, operations)

    /**
     * Dense parity, sparse coverage, redundant passes, shape/source churn and ordinary clean/single/CPU controls.
     */
    internal enum class Case(
        val small: Boolean = false,
        val stationary: Boolean = false,
        val sourceExtent: Int = 128,
    ) {
        CleanLarge(stationary = true),
        DenseOddLarge,
        DenseEvenLarge,
        SparseLarge,
        ScrollLarge,
        NoOpHeavyLarge,
        CutoffHeavyLarge,
        ActivePassSwapLarge,
        OneDirtyLarge,
        ReplacementLarge,
        SourceChurnLarge,
        MixedPassesLarge,
        MixedSizesLarge,
        SingleTileControl(small = true),
        SmallSourceFallback(small = true, sourceExtent = 16),
    }
}
