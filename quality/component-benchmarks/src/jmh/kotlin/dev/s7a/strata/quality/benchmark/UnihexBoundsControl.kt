package dev.s7a.strata.quality.benchmark

/**
 * Twelve single-row consumer controls, retained regardless of whether packed natural bounds can benefit them.
 * Full source loading and terminal operations remain inside their explicitly named measurement boundaries.
 */
public enum class UnihexBoundsControl {
    Evicted8Integer,
    Evicted32Fractional,
    WarmRasterHit,
    AbsentSparseGlyph,
    MatchingOverride,
    NoMatchingOverride,
    TrueType,
    Bitmap,
    SnapshotLoad,
    EngineLifecycle,
    CleanTextFrame,
    SnapshotReplacement,
}
