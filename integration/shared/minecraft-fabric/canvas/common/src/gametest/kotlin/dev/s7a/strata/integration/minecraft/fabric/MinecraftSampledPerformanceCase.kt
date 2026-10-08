package dev.s7a.strata.integration.minecraft.fabric

/**
 * Independent general sampled-image workloads with fixed small, medium and large immutable source extents.
 * Names are external selection identifiers; each case runs at every standard GUI density.
 */
internal enum class MinecraftSampledPerformanceCase(
    val mode: Mode,
    val resolution: Int,
    val resource: Resource? = null,
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
    ResourcePinnedSmall(Mode.ResourceDecode, 16, Resource.Pinned),
    ResourcePinnedMedium(Mode.ResourceDecode, 256, Resource.Pinned),
    ResourcePinnedLarge(Mode.ResourceDecode, 1024, Resource.Pinned),
    ResourceBridgeHotSmall(Mode.ResourceDecode, 16, Resource.Hot),
    ResourceBridgeHotMedium(Mode.ResourceDecode, 256, Resource.Hot),
    ResourceBridgeHotLarge(Mode.ResourceDecode, 1024, Resource.Hot),
    ResourceBridgeHot100(Mode.ResourceDecode, 16, Resource.Hot100),
    ResourceBridgeColdSmall(Mode.ResourceDecode, 16, Resource.Cold),
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
        ResourceDecode,
    }
    /**
     * Keeps admitted host hits separate from the actual public Fabric resolution used on uncached overflow paths.
     * Cold selects eight distinct current packaged PNGs; Hot100 resolves the same input one hundred times per declaration.
     */
    enum class Resource {
        Pinned,
        Hot,
        Hot100,
        Cold,
    }

}
