package dev.s7a.strata.integration.web

/**
 * Finite public-API and lifecycle controls exported by the compiled untimed Button fixture.
 * Fault controls distinguish removal of Button's unused Canvas prerequisite from Text failure parity.
 */
internal enum class WebButtonMeasurementControl(
    val token: String,
    val fault: CanvasFault = CanvasFault.None,
) {
    LiteralUiText("literal-ui-text"),
    LiteralString("literal-string"),
    ObservedUiLabel("observed-ui-label"),
    ObservedUiEnabled("observed-ui-enabled"),
    ObservedUiBoth("observed-ui-both"),
    ObservedStringLabel("observed-string-label"),
    ObservedStringEnabled("observed-string-enabled"),
    ObservedStringBoth("observed-string-both"),
    ZeroWidth("zero-width"),
    OneWidth("one-width"),
    UnicodeLongLabel("unicode-long-label"),
    TranslatedKey("translated-key"),
    TranslatedFallback("translated-fallback"),
    ConcatenatedLabel("concatenated-label"),
    NegativeWidth("negative-width"),
    TranslatedArguments("translated-arguments"),
    ResourceFontRejection("resource-font-rejection"),
    PlatformTextRejection("platform-text-rejection"),
    AutomaticTextWidth("automatic-text-width"),
    AutomaticTextMeasureFailure("automatic-text-measure-failure", CanvasFault.MeasureFailure),
    AutomaticTextNullContext("automatic-text-null-context", CanvasFault.NullContext),
    ButtonNullContext("button-null-context", CanvasFault.NullContext),
    ButtonMeasureFailure("button-measure-failure", CanvasFault.MeasureFailure),
    ButtonCanvasCreationFailure("button-canvas-creation-failure", CanvasFault.CreationFailure),
    EnabledPointerActivation("enabled-pointer-activation"),
    DisabledPointerActivation("disabled-pointer-activation"),
    FocusedKeyboardActivation("focused-keyboard-activation"),
    KeyedReorder("keyed-reorder"),
    RemoveInsert("remove-insert"),
    InitialHtmlAdoption("initial-html-adoption"),
    HydrationRejection("hydration-rejection"),
    IndependentHostThemes("independent-host-themes"),
    ModifierConstraints("modifier-constraints"),
    IdleResize("idle-resize"),
    EqualSourcePublication("equal-source-publication"),
    FailureCloseRelease("failure-close-release"),
    ;

    /**
     * Fault injected into native Canvas methods only while this control executes.
     */
    enum class CanvasFault {
        None,
        NullContext,
        MeasureFailure,
        CreationFailure,
    }
}
