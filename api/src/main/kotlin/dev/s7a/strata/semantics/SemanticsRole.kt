package dev.s7a.strata.semantics

/**
 * A value-typed semantics role model.
 *
 * Unknown application-defined roles remain intact for adapters rather than being rejected by the API.
 * Custom implementations must be immutable and provide stable value-based equality and hash semantics.
 */
public interface SemanticsRole {
    /**
     * A button-like control.
     */
    public data object Button : SemanticsRole

    /**
     * A text presentation.
     */
    public data object Text : SemanticsRole

    /**
     * An editable single-line text field.
     */
    public data object TextField : SemanticsRole

    /**
     * An editable multiline text area with independently controlled vertical scrolling.
     */
    public data object TextArea : SemanticsRole

    /**
     * One selectable tab in an externally controlled tab group.
     */
    public data object Tab : SemanticsRole

    /**
     * A binary checkable control.
     */
    public data object Checkbox : SemanticsRole

    /**
     * A bounded numeric adjustment control.
     */
    public data object Slider : SemanticsRole

    /**
     * A button that cycles through a finite option set.
     */
    public data object CycleButton : SemanticsRole

    /**
     * A read-only progress indicator.
     */
    public data object ProgressBar : SemanticsRole

    /**
     * A selectable list container.
     */
    public data object SelectionList : SemanticsRole
}
