package dev.s7a.strata.ui

/**
 * Terminal reason retained by the public session handle.
 */
public enum class UiCloseReason {
    Closed,
    Replaced,
    Visibility,
    OwnerDisabled,
    Disconnected,
    WorldExited,
    ContainerChanged,
    Unsupported,
    ResourceLimit,
    Failed,
}
