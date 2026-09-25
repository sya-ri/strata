package dev.s7a.strata.runtime.spi

import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.ui.UiInputPolicy
import dev.s7a.strata.ui.UiInteractionMode
import dev.s7a.strata.ui.UiPresentation

/**
 * Complete requested control state; a driver applies it atomically or rejects it without changing native state.
 */
@InternalStrataRuntimeApi
public data class RuntimeUiControl(
    public val sequence: Long,
    public val presentation: UiPresentation,
    public val inputPolicy: UiInputPolicy,
    public val interactionMode: UiInteractionMode,
)
