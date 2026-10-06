package dev.s7a.strata.performance

/**
 * Typed corruption cases keep test fault selection separate from external evidence string decoding.
 */
internal enum class JmhEvidenceMutation {
    MissingMarker,
    MissingConfirmation,
    InvalidConfirmationScore,
    IncompleteConfirmation,
    BooleanConfirmation,
    MissingWorkload,
    MissingMetric,
    MissingHistogram,
    DifferentEnvironment,
    BooleanIndex,
    DuplicateRun,
    AlteredRaw,
    AlteredCollector,
    AlteredTarget,
    InputMissing,
    InputChanged,
    InputUnsafe,
    InputDuplicate,
    ChangedInput,
    ChangedFixture,
    ChangedHarness,
    ChangedEnvironment,
    ChangedOptions,
    ChangedUnit,
    ChangedControl,
}
