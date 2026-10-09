package dev.s7a.strata.quality.benchmark

/**
 * Frozen native-free transport controls; each cycle leaves every queue empty and preserves increasing wire identities.
 * Gap and producer controls exercise two ordered arrivals rather than dropping or replaying packets.
 */
public enum class TransportDrainWorkload(
    public val frames: Int,
) {
    Idle(0),
    One(1),
    Sparse(3),
    Seven(7),
    Eight(8),
    SixtyThree(63),
    ExactLimit(64),
    LimitPlusOne(65),
    SequenceGap(2),
    BusyProducer(65),
}
