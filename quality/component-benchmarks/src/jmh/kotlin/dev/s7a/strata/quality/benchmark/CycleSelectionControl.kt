package dev.s7a.strata.quality.benchmark

/**
 * The thirty independent complete transition sequences frozen by the selection-index comparison contract.
 * Each identifier is one separately collected row; none is combined into an aggregate control.
 */
public enum class CycleSelectionControl {
    CleanRetainedFrame,
    OneOptionUnchangedFrame,
    DisabledIgnoredInput,
    IgnoredPointerButton,
    ZeroScroll,
    ForwardWrap,
    BackwardWrap,
    PointerPrimaryCycle,
    ScrollForward,
    ScrollBackward,
    KeyLeft,
    KeyRight,
    KeySpace,
    KeyEnter,
    ExternalSelectedWrite,
    EqualDistinctNoOpWrite,
    InvalidOutsideValueRejected,
    LabelsSameTextDifferentValues,
    EmptyLabels,
    NormalInactiveGeometryParity,
    WidthOnlyRedeclaration,
    LabelAndGlyphReplacement,
    StateReplacementSameSelection,
    StateReplacementDifferentOrder,
    MutationBeforeAttach,
    MutationDuringDetachReattach,
    EarlierObserverThrows,
    LaterObserverReentersSelection,
    ActionCallbackReentersSelection,
    DisposeCloseRelease,
}
