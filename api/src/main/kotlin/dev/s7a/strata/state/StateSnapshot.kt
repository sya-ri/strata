package dev.s7a.strata.state

/**
 * Carries a source observation and its publication revision.
 *
 * The carrier is immutable, but it does not copy or make [value] deeply immutable.
 * A source must therefore publish values whose later mutation cannot change an already published observation.
 * Equal revisions within one source denote the same logical observation; they are not a second publication that can replace the earlier value.
 *
 * @param T the observed value type.
 * @property revision the source-assigned non-negative revision.
 * @property value the observed value at [revision].
 */
public data class StateSnapshot<out T>(
    public val revision: StateRevision,
    public val value: T,
)
