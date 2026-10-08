package dev.s7a.strata.quality.benchmark

/**
 * Frozen logical sizes and queue controls; boundary sizes include the unchanged message codec bytes.
 */
public enum class OutgoingFragmentWorkload {
    Hello,
    Control,
    SmallAction,
    OneFragment,
    MultiFragment,
    OneMiB,
    NearLimit,
    NegotiatedSmall,
    ImmediateMany,
    DelayedMany,
    UnsentCancellation,
    PartialCancellation,
    EntryCapacity,
    ByteCapacity,
}
