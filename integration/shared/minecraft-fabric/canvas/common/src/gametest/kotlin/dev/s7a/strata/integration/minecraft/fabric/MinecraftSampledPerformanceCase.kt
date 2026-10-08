package dev.s7a.strata.integration.minecraft.fabric

/**
 * Independent general sampled-image workloads with fixed small, medium and large immutable source extents.
 * Names are external selection identifiers; each case runs at every standard GUI density.
 */
internal enum class MinecraftSampledPerformanceCase(
    val mode: Mode,
    val resolution: Int,
    val canvasTargets: Int = 0,
    val canvasOccurrences: Int = 0,
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
    CanvasLookupNone(Mode.NativeLookup, 16, 0, 0),
    CanvasLookupOne(Mode.NativeLookup, 16, 1, 1),
    CanvasLookupOneRepeated64(Mode.NativeLookup, 16, 1, 64),
    CanvasLookupOneRepeated4096(Mode.NativeLookup, 16, 1, 4096),
    CanvasLookupSixteen(Mode.NativeLookup, 16, 16, 16),
    CanvasLookupSixteenRepeated64(Mode.NativeLookup, 16, 16, 64),
    CanvasLookupSixteenRepeated4096(Mode.NativeLookup, 16, 16, 4096),
    CanvasLookupSixtyFour(Mode.NativeLookup, 16, 64, 64),
    CanvasLookupSixtyFourRepeated4096(Mode.NativeLookup, 16, 64, 4096),
    ;

    /**
     * One changed input dimension, or ordered overlapping rows with changing layout and non-identity tint.
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
        NativeLookup,
    }
}
