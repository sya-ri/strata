package dev.s7a.strata.runtime.remote

/**
 * Trusted client inputs for one active modifier factory.
 * [identity] remains stable while the corresponding server modifier survives reconciliation.
 * State reads are confined to the client session's owner thread after preparation.
 */
public class RemoteModifierContext internal constructor(
    public val identity: Long,
    public val actions: RemoteClientActions,
    public val states: RemoteClientStates,
)
