package dev.s7a.strata.quality.benchmark

/** Shared CPU intervals; JMH measures complete declared connection or actual-service lifecycles instead. */
public enum class OutgoingFragmentPhase {
    /** Message encode/queue and declared immediate/cancellation/capacity controls. */
    Queue,
    /** Remaining bounded flushes after queue/control preparation outside the sample. */
    Flush,
    /** Message production, queue/control work and all remaining bounded flushes. */
    Cycle,
    /** Actual service construction and private peer tick, with preparation outside CPU sampling. */
    ServerCycle,
}
