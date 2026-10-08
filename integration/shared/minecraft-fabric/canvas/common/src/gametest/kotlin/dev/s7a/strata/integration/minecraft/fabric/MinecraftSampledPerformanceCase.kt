package dev.s7a.strata.integration.minecraft.fabric

/**
 * Independent general sampled-image workloads with fixed small, medium and large immutable source extents.
 * Names are external selection identifiers; each case runs at every standard GUI density.
 */
internal enum class MinecraftSampledPerformanceCase(
    val mode: Mode,
    val resolution: Int,
    val clipDepth: Int = 0,
    val clipPattern: ClipPattern? = null,
    val clipPrimitives: Int = 512,
    val smallClipViewport: Boolean = false,
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
    FrameClipInteger0(Mode.FrameClips, 4, 0, ClipPattern.Integer),
    FrameClipInteger1(Mode.FrameClips, 4, 1, ClipPattern.Integer),
    FrameClipInteger4(Mode.FrameClips, 4, 4, ClipPattern.Integer),
    FrameClipInteger32(Mode.FrameClips, 4, 32, ClipPattern.Integer),
    FrameClipInteger128(Mode.FrameClips, 4, 128, ClipPattern.Integer),
    FrameClipFractional4(Mode.FrameClips, 4, 4, ClipPattern.Fractional),
    FrameClipFractional32(Mode.FrameClips, 4, 32, ClipPattern.Fractional),
    FrameClipFractional128(Mode.FrameClips, 4, 128, ClipPattern.Fractional),
    FrameClipMixed4(Mode.FrameClips, 4, 4, ClipPattern.Mixed),
    FrameClipMixed32(Mode.FrameClips, 4, 32, ClipPattern.Mixed),
    FrameClipMixed128(Mode.FrameClips, 4, 128, ClipPattern.Mixed),
    FrameClipFewMixed32(Mode.FrameClips, 4, 32, ClipPattern.Mixed, clipPrimitives = 8),
    FrameClipSmallInteger32(Mode.FrameClips, 4, 32, ClipPattern.Integer, smallClipViewport = true),
    FrameClipCleanMixed32(Mode.FrameClipsClean, 4, 32, ClipPattern.Mixed),
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
        FrameClips,
        FrameClipsClean,
    }

    /**
     * Raw clip representation produced by alternating fixed child translations before leaf coordinates are restored.
     */
    enum class ClipPattern {
        Integer,
        Fractional,
        Mixed,
    }
}
