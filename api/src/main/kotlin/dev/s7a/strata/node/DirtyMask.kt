package dev.s7a.strata.node

/**
 * Immutable set of phases affected by a property change.
 *
 * @property bits the private phase bit set.
 */
@JvmInline
public value class DirtyMask private constructor(
    private val bits: Int,
) {
    /**
     * Returns whether [phase] is included in this mask.
     *
     * @param phase the phase to query.
     * @return true when the phase is dirty.
     */
    public operator fun contains(phase: DirtyPhase): Boolean = bits and (1 shl phase.ordinal) != 0

    /**
     * Combines this mask with [other].
     *
     * @param other the phases to add.
     * @return a mask containing phases from both operands.
     */
    public operator fun plus(other: DirtyMask): DirtyMask = DirtyMask(bits or other.bits)

    /**
     * Removes phases in [other] from this mask.
     *
     * @param other the phases to clear.
     * @return a mask without the specified phases.
     */
    public operator fun minus(other: DirtyMask): DirtyMask = DirtyMask(bits and other.bits.inv())

    /**
     * Standard masks and constructors.
     */
    public companion object {
        /**
         * No phases are dirty.
         */
        public val None: DirtyMask = DirtyMask(0)

        /**
         * Every supported phase is dirty.
         */
        public val All: DirtyMask = of(*DirtyPhase.entries.toTypedArray())

        /**
         * Builds a mask from [phases].
         *
         * @param phases the phases to include.
         * @return an immutable mask.
         */
        public fun of(vararg phases: DirtyPhase): DirtyMask = phases.fold(None) { mask, phase -> mask + DirtyMask(1 shl phase.ordinal) }
    }
}
