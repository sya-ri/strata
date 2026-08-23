package dev.s7a.strata.action

/**
 * Immutable demand emitted when a virtual list approaches one loaded boundary.
 *
 * @property suggestedCount positive number of additional rows requested by the current viewport and overscan policy.
 */
public data class ListLoadRequest(
    public val suggestedCount: Int,
) {
    init {
        require(0 < suggestedCount) { "A list load request count must be positive." }
    }
}
