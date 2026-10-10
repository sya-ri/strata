package dev.s7a.strata.integration.minecraft.fabric

/**
 * Independent sampled-image and native lifetime workloads with bounded immutable input and real owner cutoffs.
 * Names are external selection identifiers; each case runs at every standard GUI density.
 */
internal enum class MinecraftSampledPerformanceCase(
    val mode: Mode,
    val resolution: Int,
    val lifetimeKind: LifetimeKind? = null,
    val lifetimeCount: Int = 0,
    val lifetimeChanged: Boolean = true,
) {
    SampledStationarySmall(Mode.Stationary, 16),
    SampledStationaryMedium(Mode.Stationary, 64),
    SampledStationaryLarge(Mode.Stationary, 256),
    SampledTranslationSmall(Mode.Translation, 16),
    SampledTranslationMedium(Mode.Translation, 64),
    SampledTranslationLarge(Mode.Translation, 256),
    SampledResizeSmall(Mode.Resize, 16),
    SampledResizeMedium(Mode.Resize, 64),
    SampledResizeLarge(Mode.Resize, 256),
    SampledClipSmall(Mode.Clip, 16),
    SampledClipMedium(Mode.Clip, 64),
    SampledClipLarge(Mode.Clip, 256),
    SampledReplacementSmall(Mode.Replacement, 16),
    SampledReplacementMedium(Mode.Replacement, 64),
    SampledReplacementLarge(Mode.Replacement, 256),
    SampledOrderedRowsSmall(Mode.OrderedRows, 16),
    SampledOrderedRowsMedium(Mode.OrderedRows, 64),
    SampledOrderedRowsLarge(Mode.OrderedRows, 256),
    SampledScrolledRowsSmall(Mode.ScrolledRows, 16),
    SampledScrolledRowsMedium(Mode.ScrolledRows, 64),
    SampledScrolledRowsLarge(Mode.ScrolledRows, 256),
    SampledTiledTranslationSmall(Mode.TiledTranslation, 16),
    SampledTiledTranslationMedium(Mode.TiledTranslation, 64),
    SampledTiledTranslationLarge(Mode.TiledTranslation, 256),
    LifetimeTargets0(Mode.Lifetime, 1, LifetimeKind.Targets, 0, true),
    LifetimeTargets1(Mode.Lifetime, 1, LifetimeKind.Targets, 1, true),
    LifetimeTargets64(Mode.Lifetime, 1, LifetimeKind.Targets, 64, true),
    LifetimeTargets64Clean(Mode.Lifetime, 1, LifetimeKind.Targets, 64, false),
    LifetimePortable0(Mode.Lifetime, 1, LifetimeKind.Portable, 0, true),
    LifetimePortable1(Mode.Lifetime, 1, LifetimeKind.Portable, 1, true),
    LifetimePortable64(Mode.Lifetime, 1, LifetimeKind.Portable, 64, true),
    LifetimeSources0(Mode.Lifetime, 1, LifetimeKind.Sources, 0, true),
    LifetimeSources1(Mode.Lifetime, 1, LifetimeKind.Sources, 1, true),
    LifetimeSources256(Mode.Lifetime, 1, LifetimeKind.Sources, 256, true),
    LifetimeSources512Overflow(Mode.Lifetime, 1, LifetimeKind.Sources, 512, true),
    LifetimeSources256Clean(Mode.Lifetime, 1, LifetimeKind.Sources, 256, false),
    ;

    /**
     * Selects a changed input dimension, ordered overlapping rows, or the dedicated bounded lifetime corpus.
     */
    enum class Mode {
        Stationary,
        Translation,
        Resize,
        Clip,
        Replacement,
        OrderedRows,
        ScrolledRows,
        TiledTranslation,
        Lifetime,
    }

    /**
     * Chooses native attachments, barrier-separated portable layers or distinct immutable sampled identities.
     * The 512-source scene deliberately exceeds one screen's 256-entry budget and retains its actual fallback.
     */
    enum class LifetimeKind {
        Targets,
        Portable,
        Sources,
    }
}
