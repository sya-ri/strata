package dev.s7a.strata.quality.benchmark

/**
 * Frozen incoming byte/ordering corpus; logical bytes exclude both transport headers.
 * NegotiatedSmall retains ordinary pending admission while tightening inner fragments to 64 bytes.
 */
public enum class IncomingFragmentWorkload(
    public val logicalBytes: Int,
) {
    Minimum(1),
    Small(48),
    Maximum(24534),
    OneMiB(1048576),
    NearLimit(8 * 1024 * 1024 - 1),
    Reversed(1048576),
    Gap(1048576),
    Duplicate(48),
    Stale(48),
    OtherIncarnation(24534),
    Discovery(0),
    NegotiatedSmall(4096),
}
