package dev.s7a.strata.ui

import dev.s7a.strata.component.UiScope
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.UiText

/**
 * Uniquely transferred definition, owned by its accepting runtime until terminal cleanup.
 */
@InternalStrataRuntimeApi
public class UiDefinitionPayload internal constructor(
    public val title: UiText,
    public val presentation: UiPresentation,
    public val category: UiCategory?,
    public val inputPolicy: UiInputPolicy,
    public val visibility: UiVisibilityPolicy,
    public val hudOrder: Int,
    public val pausesGame: Boolean,
    public val content: UiScope.() -> Unit,
)
