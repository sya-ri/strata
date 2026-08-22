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
     * One selectable tab in an externally controlled tab group.
     */
    public data object Tab : SemanticsRole
}
