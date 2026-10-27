package dev.s7a.strata.runtime.remote

import dev.s7a.strata.element.Element

/**
 * Trusted client inputs for one active modifier factory.
 * [identity] remains stable while the corresponding server modifier survives reconciliation.
 * State reads are confined to the client session's owner thread after preparation.
 */
public class RemoteModifierContext internal constructor(
    public val identity: Long,
    public val actions: RemoteClientActions,
    public val states: RemoteClientStates,
    /**
     * Display-only auxiliary root, reconstructed before its owning modifier.
     */
    public val content: Element? = null,
)
