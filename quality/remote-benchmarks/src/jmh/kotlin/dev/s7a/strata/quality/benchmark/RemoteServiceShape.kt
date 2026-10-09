package dev.s7a.strata.quality.benchmark

/**
 * Real fleet input shared by timed enum topologies and the independent two-peer callback gates.
 * Session counts are bounded by the existing production HUD limit and one foreground owner.
 */
internal data class RemoteServiceShape(
    val peers: Int,
    val huds: Int,
    val screens: Int,
) {
    init {
        require(0 <= peers && huds in 0..16 && screens in 0..1)
        require(peers != 0 || (huds == 0 && screens == 0))
    }

    /**
     * Current sessions on each peer; identities and encounter order belong to actual public opens.
     */
    val sessionsPerPeer: Int get() = huds + screens

    /**
     * Initial session count, excluding churn's additional distinct peer.
     */
    val sessions: Int get() = peers * sessionsPerPeer

    /**
     * Fixed output passes for the production connection's eight-frame default flush budget.
     * Each frozen three-node snapshot/update fits one frame; real work verification rejects a different shape.
     * Empty fleets retain one explicit phase, and no readiness result changes the number of ticks.
     */
    val deliveryTicks: Int get() = maxOf(1, (sessionsPerPeer + 7) / 8)
}
