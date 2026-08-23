package dev.s7a.strata.action

/**
 * Stable typed action keys emitted by standard interactive components.
 *
 * Custom components define independent [ActionKey] instances instead of adding application-domain events here.
 */
public object ComponentActions {
    /**
     * Checkbox selected value after a successful toggle.
     */
    public val CheckedChange: ActionKey<Boolean> = ActionKey("checked change")

    /**
     * Slider numeric value after pointer or keyboard adjustment.
     */
    public val SliderChange: ActionKey<Double> = ActionKey("slider change")

    /**
     * Type-erased CycleButton value after a successful cycle operation.
     */
    public val Cycle: ActionKey<Any> = ActionKey("cycle")

    /**
     * Stable item key after a SelectionList selection change.
     */
    public val SelectionChange: ActionKey<Any> = ActionKey("selection change")

    /**
     * Virtual list request for more items before its loaded window.
     */
    public val LeadingItemsRequested: ActionKey<ListLoadRequest> = ActionKey("leading items requested")

    /**
     * Virtual list request for more items after its loaded window.
     */
    public val TrailingItemsRequested: ActionKey<ListLoadRequest> = ActionKey("trailing items requested")
}
