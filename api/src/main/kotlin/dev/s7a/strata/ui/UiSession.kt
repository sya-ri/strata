package dev.s7a.strata.ui

/**
 * Owner-thread handle for one retained UI, shared by client and remote event callbacks.
 * Controls requested inside an event are applied after event delivery finishes.
 * Terminal handles never reopen and retain their last applied presentation.
 */
public interface UiSession : AutoCloseable {
    /**
     * Applied native presentation, or null before the initial application acknowledgement.
     */
    public val presentation: UiPresentation?

    /**
     * Current lifecycle, including pending switches and terminal reasons.
     */
    public val status: UiSessionStatus

    /**
     * Applied gameplay permissions while this session owns input.
     */
    public val inputPolicy: UiInputPolicy

    /**
     * Applied interaction mode; hiding a HUD ends its interaction.
     */
    public val interactionMode: UiInteractionMode

    /**
     * Requests a new native wrapper without replacing the contents or identity.
     */
    public fun switch(presentation: UiPresentation): UiOperationResult

    /**
     * Changes permissions and releases previously forwarded pressed inputs.
     */
    public fun setInputPolicy(policy: UiInputPolicy): UiOperationResult

    /**
     * Selects cursor or camera interaction, or stops HUD interaction.
     */
    public fun setInteractionMode(mode: UiInteractionMode): UiOperationResult

    /**
     * Idempotently ends the session; takes precedence over unapplied controls.
     */
    override fun close()
}
