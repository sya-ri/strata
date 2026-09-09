package dev.s7a.strata.runtime.diagnostics

import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Actual callback and ownership work, never inferred from pixels or elapsed time. Counts are attempts unless named successful.
 */
@InternalStrataRuntimeApi
public enum class UiRenderMetric {
    /**
     * Structural application Observe invocation.
     */
    ObserveEvaluation,

    /**
     * Direct StateSource component argument invocation.
     */
    StateComponentEvaluation,

    /**
     * Requested host frame.
     */
    FrameAttempt,

    /**
     * Successfully completed host frame.
     */
    FrameSuccess,

    /**
     * Failed host frame.
     */
    FrameFailure,

    /**
     * Exact retained frame reuse.
     */
    FrameCacheHit,

    /**
     * Screen root callback invocation.
     */
    RootEvaluation,

    /**
     * Deferred region callback invocation, excluding cached child access.
     */
    ContentEvaluation,

    /**
     * Deferred callback returned successfully.
     */
    ContentEvaluationSuccess,

    /**
     * Materialized virtual row callback invocation.
     */
    RowEvaluation,

    /**
     * Newly created retained component or modifier.
     */
    NodeCreate,

    /**
     * Actual element update callback invocation.
     */
    NodeUpdate,

    /**
     * Retained entry cleanup began.
     */
    NodeDispose,

    /**
     * Actual measure callback.
     */
    Measure,

    /**
     * Actual layout callback.
     */
    Layout,

    /**
     * Actual ordinary paint callback.
     */
    Paint,

    /**
     * Actual post-child paint callback.
     */
    OverlayPaint,

    /**
     * Actual root-coordinate overlay callback.
     */
    RootOverlayPaint,

    /**
     * Actual semantics callback.
     */
    Semantics,

    /**
     * Nonempty local phase invalidation.
     */
    LocalInvalidation,

    /**
     * Phase invalidation propagated from a descendant.
     */
    AncestorInvalidation,

    /**
     * Direct-child structure changed.
     */
    StructureInvalidation,

    /**
     * Initial deferred declaration.
     */
    InitialContent,

    /**
     * Deferred source value changed.
     */
    SourceContent,

    /**
     * Deferred source identity changed.
     */
    SourceReplacementContent,

    /**
     * Parent supplied a new deferred definition.
     */
    ParentDefinitionContent,

    /**
     * Root value changed after the frame cutoff.
     */
    RootValueChange,

    /**
     * Derived transformation invocation.
     */
    Projection,

    /**
     * Equal derived output stopped propagation.
     */
    ProjectionEqual,

    /**
     * UI consumer received a committed tuple.
     */
    ConsumerNotification,

    /**
     * Root subscription established.
     */
    SubscriptionOpen,

    /**
     * Root subscription close attempted.
     */
    SubscriptionClose,
}
