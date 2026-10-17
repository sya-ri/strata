package dev.s7a.strata.quality.benchmark

/**
 * Declared core CPU interval boundaries; literal source construction is outside every timed scope.
 * JMH measures each selected core operation together with its invocation preparation, parity and release.
 */
public enum class IncomingFragmentPhase {
    /**
     * Decodes existing native bytes and admits into the real reorder queue; assembly follows outside timing.
     */
    Admission,

    /**
     * Includes inbox snapshots, decoding, reorder admission, ordered drains and logical assembly.
     */
    Assembly,

    /**
     * Includes inbox snapshots and actual common-service ingress, with logical assembly outside timing.
     */
    ServerIngress,
}
