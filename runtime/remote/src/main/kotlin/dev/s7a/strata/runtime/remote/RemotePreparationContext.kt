package dev.s7a.strata.runtime.remote

/**
 * Callback-lifetime context for preparing decoded state before a client declaration becomes visible.
 * Factories retain values through [states], never this callback-lifetime context.
 */
public class RemotePreparationContext internal constructor(
    public val identity: Long,
    public val states: RemoteClientStates,
)
