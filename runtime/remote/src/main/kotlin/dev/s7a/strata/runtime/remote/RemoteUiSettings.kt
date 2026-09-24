package dev.s7a.strata.runtime.remote

import dev.s7a.strata.ui.UiCategory
import dev.s7a.strata.ui.UiInputPolicy
import dev.s7a.strata.ui.UiPresentation
import dev.s7a.strata.ui.UiVisibilityPolicy

/**
 * Immutable, typed presentation settings negotiated with the remote UI's initial snapshot.
 */
public data class RemoteUiSettings(
    public val presentation: UiPresentation = UiPresentation.Screen,
    public val category: UiCategory? = null,
    public val inputPolicy: UiInputPolicy = UiInputPolicy.BlockAll,
    public val visibility: UiVisibilityPolicy = UiVisibilityPolicy(),
    public val hudOrder: Int = 0,
)
