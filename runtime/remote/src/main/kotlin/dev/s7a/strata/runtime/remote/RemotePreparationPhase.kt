package dev.s7a.strata.runtime.remote

/**
 * Ordering for client state preparation, independent of component order in a received tree.
 * Owners prepare compound native state before references such as scrollbars attach to its shared position.
 */
public enum class RemotePreparationPhase {
    Owners,
    References,
}
