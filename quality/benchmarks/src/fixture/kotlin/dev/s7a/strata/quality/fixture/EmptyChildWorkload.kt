package dev.s7a.strata.quality.fixture

/**
 * Frozen empty-child workload identities shared by portable qualification and the precompiled JVM fixture.
 * Counts describe eligible leaves or transition target parents, never operations per frame.
 */
public enum class EmptyChildWorkload(
    /**
     * Complete public operation executed inside sampling.
     */
    public val operation: Operation,
    /**
     * Description change applied inside sampling.
     */
    public val payload: Payload,
    /**
     * Number of independent target components.
     */
    public val count: Int,
) {
    DirectChanged1(Operation.DirectTreeUpdate, Payload.Changed, 1),
    DirectChanged128(Operation.DirectTreeUpdate, Payload.Changed, 128),
    DirectChanged4096(Operation.DirectTreeUpdate, Payload.Changed, 4096),
    ObservedChanged1(Operation.ObservedRegionFrame, Payload.Changed, 1),
    ObservedChanged128(Operation.ObservedRegionFrame, Payload.Changed, 128),
    ObservedChanged4096(Operation.ObservedRegionFrame, Payload.Changed, 4096),
    DirectEqual1(Operation.DirectTreeUpdate, Payload.FreshEqual, 1),
    DirectEqual128(Operation.DirectTreeUpdate, Payload.FreshEqual, 128),
    DirectEqual4096(Operation.DirectTreeUpdate, Payload.FreshEqual, 4096),
    ObservedEqual1(Operation.ObservedRegionFrame, Payload.FreshEqual, 1),
    ObservedEqual128(Operation.ObservedRegionFrame, Payload.FreshEqual, 128),
    ObservedEqual4096(Operation.ObservedRegionFrame, Payload.FreshEqual, 4096),
    Same1(Operation.SameDescriptionTreeUpdate, Payload.FreshEqual, 1),
    Same128(Operation.SameDescriptionTreeUpdate, Payload.FreshEqual, 128),
    Same4096(Operation.SameDescriptionTreeUpdate, Payload.FreshEqual, 4096),
    Clean1(Operation.CleanSessionFrame, Payload.FreshEqual, 1),
    Clean128(Operation.CleanSessionFrame, Payload.FreshEqual, 128),
    Clean4096(Operation.CleanSessionFrame, Payload.FreshEqual, 4096),
    DirectEmptyToOne1(Operation.DirectTreeUpdate, Payload.EmptyToOne, 1),
    DirectEmptyToOne128(Operation.DirectTreeUpdate, Payload.EmptyToOne, 128),
    ObservedEmptyToOne1(Operation.ObservedRegionFrame, Payload.EmptyToOne, 1),
    ObservedEmptyToOne128(Operation.ObservedRegionFrame, Payload.EmptyToOne, 128),
    DirectOneToEmpty1(Operation.DirectTreeUpdate, Payload.OneToEmpty, 1),
    DirectOneToEmpty128(Operation.DirectTreeUpdate, Payload.OneToEmpty, 128),
    ObservedOneToEmpty1(Operation.ObservedRegionFrame, Payload.OneToEmpty, 1),
    ObservedOneToEmpty128(Operation.ObservedRegionFrame, Payload.OneToEmpty, 128),
    DirectNonempty1(Operation.DirectTreeUpdate, Payload.NonemptyToNonempty, 1),
    DirectNonempty128(Operation.DirectTreeUpdate, Payload.NonemptyToNonempty, 128),
    ObservedNonempty1(Operation.ObservedRegionFrame, Payload.NonemptyToNonempty, 1),
    ObservedNonempty128(Operation.ObservedRegionFrame, Payload.NonemptyToNonempty, 128);

    /**
     * Public update/frame boundary; setup and cleanup remain outside sampling.
     */
    public enum class Operation {
        DirectTreeUpdate,
        ObservedRegionFrame,
        SameDescriptionTreeUpdate,
        CleanSessionFrame,
    }

    /**
     * Compatible leaf payload or target-parent child-list transition.
     */
    public enum class Payload {
        Changed,
        FreshEqual,
        EmptyToOne,
        OneToEmpty,
        NonemptyToNonempty,
    }
}
