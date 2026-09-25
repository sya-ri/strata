package dev.s7a.strata.runtime.remote

import dev.s7a.strata.ui.UiCloseReason

/**
 * Detached terminal reasons understood without exposing server exception messages or stack traces.
 */
public enum class RemoteFailure(
    public val uiReason: UiCloseReason,
) {
    UnsupportedProtocol(UiCloseReason.Unsupported),
    UnsupportedType(UiCloseReason.Unsupported),
    InvalidMessage(UiCloseReason.Failed),
    ResourceLimit(UiCloseReason.ResourceLimit),
    TimedOut(UiCloseReason.Disconnected),
    Disconnected(UiCloseReason.Disconnected),
    PeerClosed(UiCloseReason.Closed),
    OwnerClosed(UiCloseReason.Closed),
    Replaced(UiCloseReason.Replaced),
    HandlerFailed(UiCloseReason.Failed),
    ContainerChanged(UiCloseReason.ContainerChanged),
    Visibility(UiCloseReason.Visibility),
    WorldExited(UiCloseReason.WorldExited),
    OwnerDisabled(UiCloseReason.OwnerDisabled),
}
