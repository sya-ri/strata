package dev.s7a.strata.runtime.velocity

/**
 * Fixed native-free command corpus on the actual dedicated worker, including both sides of its 64-command budget.
 * BusyProducer starts with one callback that admits the other 64 commands from a separate producer before emptiness.
 * Arrival after an empty observation is a separate untimed latency control.
 */
internal enum class VelocityDrainWorkload(val commands: Int) {
    Idle(0),
    One(1),
    Seven(7),
    Eight(8),
    SixtyThree(63),
    ExactLimit(64),
    LimitPlusOne(65),
    BusyProducer(65),
}
