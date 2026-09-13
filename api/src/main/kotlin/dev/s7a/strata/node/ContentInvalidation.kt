package dev.s7a.strata.node

import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Owner-thread reasons for deferred declaration work, independent of retained layout invalidation.
 */
@InternalStrataRuntimeApi
public enum class ContentInvalidation {
    /**
     * The retained region has not evaluated yet.
     */
    Initial,

    /**
     * A frame-committed input changed by equality.
     */
    SourceValue,

    /**
     * A parent replaced an input source identity.
     */
    SourceReplacement,

    /**
     * The parent supplied a new deferred callback or literal arguments.
     */
    ParentDefinition,
}
