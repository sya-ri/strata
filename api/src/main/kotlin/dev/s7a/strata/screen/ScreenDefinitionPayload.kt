package dev.s7a.strata.screen

import dev.s7a.strata.component.UiScope
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.UiText

/**
 * Privileged ownership payload atomically removed from one [ScreenDefinition].
 *
 * A runtime adapter uniquely owns the payload after transfer and must release its captured content on every terminal path.
 * Application code must not access this bridge type.
 *
 * @property title exact unresolved screen title.
 * @property pausesGame whether the screen pauses its host game.
 * @property content owner-thread declarative callback evaluated by the runtime exactly once.
 */
@InternalStrataRuntimeApi
public class ScreenDefinitionPayload internal constructor(
    public val title: UiText,
    public val pausesGame: Boolean,
    public val content: UiScope.() -> Unit,
)
