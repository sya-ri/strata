package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.geometry.IntSize

/**
 * Independent complete-presentation controls for lazy proof classification and additional exact-mask admissions.
 * Both revisions consume identical prebuilt sources and commands; native passes, uploads and GPU scopes remain measured separately.
 */
internal class MinecraftSamplingCompositionCorpus : MinecraftNativePerformanceCorpus {
    override val family: String = "native-sampling-composition"
    override val caseIds: Set<String> = Case.entries.map { it.name }.toSet()

    override fun scene(
        id: String,
        viewport: IntSize,
        operations: Int,
    ): MinecraftNativePerformanceScene = MinecraftSamplingCompositionScene(Case.valueOf(id), viewport)

    /** Complete frozen native controls; changed cases alternate two immutable destinations through the normal source cutoff. */
    internal enum class Case(
        val masks: Int = 32,
        val fills: Int = 4096,
        val changed: Boolean = true,
    ) {
        OrdinaryLargeChanged,
        OneMaskFewChanged(masks = 1, fills = 1),
        OneMaskManyChanged(masks = 1),
        DisjointFewChanged(fills = 1),
        DisjointManyStatic(changed = false),
        DisjointManyChanged,
        TouchingChanged,
        OverlappingChanged,
        EarlyBlockedChanged,
        LateBlockedChanged,
    }
}
