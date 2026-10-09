package dev.s7a.strata.quality.benchmark

/**
 * Frozen public-route fleet shapes; peers are the initial population and churn adds one distinct peer.
 * Empty source topologies retain construction, negotiation and terminal controls without invented sessions.
 */
public enum class RemoteServiceTopology(
    public val peers: Int,
    public val huds: Int,
    public val screens: Int,
) {
    Empty(0, 0, 0),
    Peers1Huds0Screens0(1, 0, 0),
    Peers1Huds0Screens1(1, 0, 1),
    Peers1Huds1Screens0(1, 1, 0),
    Peers1Huds1Screens1(1, 1, 1),
    Peers1Huds4Screens0(1, 4, 0),
    Peers1Huds4Screens1(1, 4, 1),
    Peers1Huds16Screens0(1, 16, 0),
    Peers1Huds16Screens1(1, 16, 1),
    Peers16Huds0Screens0(16, 0, 0),
    Peers16Huds0Screens1(16, 0, 1),
    Peers16Huds1Screens0(16, 1, 0),
    Peers16Huds1Screens1(16, 1, 1),
    Peers16Huds4Screens0(16, 4, 0),
    Peers16Huds4Screens1(16, 4, 1),
    Peers16Huds16Screens0(16, 16, 0),
    Peers16Huds16Screens1(16, 16, 1),
    Peers128Huds0Screens0(128, 0, 0),
    Peers128Huds0Screens1(128, 0, 1),
    Peers128Huds1Screens0(128, 1, 0),
    Peers128Huds1Screens1(128, 1, 1),
    Peers128Huds4Screens0(128, 4, 0),
    Peers128Huds4Screens1(128, 4, 1),
    Peers128Huds16Screens0(128, 16, 0),
    Peers128Huds16Screens1(128, 16, 1),
    ;

    /**
     * Current sessions per peer, including the independent foreground owner.
     */
    public val sessionsPerPeer: Int get() = huds + screens

    /**
     * Initial total session population; the churn peak includes one additional peer.
     */
    public val sessions: Int get() = peers * sessionsPerPeer
}
