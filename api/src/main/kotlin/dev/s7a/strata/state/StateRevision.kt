package dev.s7a.strata.state

/**
 * Identifies one observation in the ordered history of a state source.
 *
 * Revisions are non-negative and compare in ascending numeric order.
 * They are meaningful only within one source instance and are never inferred from a snapshot value.
 * A source advances the revision for every newer observation.
 * Once [Long.MAX_VALUE] is published, that source cannot publish a later snapshot and must fail without mutating or notifying.
 *
 * @property value the non-negative revision number.
 * @throws IllegalArgumentException when [value] is negative.
 */
@JvmInline
public value class StateRevision(
    public val value: Long,
) : Comparable<StateRevision> {
    init {
        require(0 <= value) { "State revisions must be non-negative." }
    }

    /**
     * Compares this revision with [other] in ascending numeric order.
     *
     * @param other the revision to compare.
     * @return a negative number, zero, or a positive number when this revision is earlier than, equal to, or later than [other].
     */
    override fun compareTo(other: StateRevision): Int = value.compareTo(other.value)
}
