package dev.s7a.strata.modifier

/**
 * Polymorphic resolution policy for one constrained size axis.
 *
 * Each policy resolves itself against the inclusive parent range without a sentinel or nullable value representing the policy.
 */
internal sealed interface AxisConstraint {
    /**
     * Resolves this policy against one parent axis range.
     *
     * @param parentMin the inclusive parent minimum.
     * @param parentMax the inclusive parent maximum.
     * @return the valid child range selected by this policy.
     */
    fun resolve(
        parentMin: Int,
        parentMax: Int,
    ): ResolvedAxis

    /**
     * Leaves the parent range unchanged.
     */
    data object Unchanged : AxisConstraint {
        override fun resolve(
            parentMin: Int,
            parentMax: Int,
        ): ResolvedAxis = ResolvedAxis(parentMin, parentMax)
    }

    /**
     * Pins the child axis to one validated value, clamped into the parent range.
     *
     * @property value the requested non-negative exact value.
     * @throws IllegalArgumentException when [value] is negative.
     */
    data class Exact public constructor(
        val value: Int,
    ) : AxisConstraint {
        init {
            require(0 <= value) { "Exact size must be non-negative." }
        }

        override fun resolve(
            parentMin: Int,
            parentMax: Int,
        ): ResolvedAxis {
            val resolved = value.coerceIn(parentMin, parentMax)
            return ResolvedAxis(resolved, resolved)
        }
    }

    /**
     * Applies one validated requested range and clamps both endpoints independently into the parent range.
     *
     * @property min the requested non-negative minimum.
     * @property max the requested maximum, not smaller than [min].
     * @throws IllegalArgumentException when either endpoint is negative or [min] exceeds [max].
     */
    data class Range public constructor(
        val min: Int,
        val max: Int,
    ) : AxisConstraint {
        init {
            require(0 <= min) { "Minimum size must be non-negative." }
            require(0 <= max) { "Maximum size must be non-negative." }
            require(min <= max) { "Minimum size must not exceed maximum size." }
        }

        override fun resolve(
            parentMin: Int,
            parentMax: Int,
        ): ResolvedAxis {
            val resolvedMin = min.coerceIn(parentMin, parentMax)
            val resolvedMax = max.coerceIn(parentMin, parentMax)
            return ResolvedAxis(resolvedMin, resolvedMax)
        }
    }

    /**
     * Fills a bounded parent axis and preserves an unbounded parent axis.
     */
    data object Fill : AxisConstraint {
        override fun resolve(
            parentMin: Int,
            parentMax: Int,
        ): ResolvedAxis =
            if (parentMax == Int.MAX_VALUE) {
                ResolvedAxis(parentMin, parentMax)
            } else {
                ResolvedAxis(parentMax, parentMax)
            }
    }

    /**
     * A valid inclusive axis range selected for a child.
     *
     * @property min the inclusive minimum.
     * @property max the inclusive maximum.
     */
    data class ResolvedAxis public constructor(
        val min: Int,
        val max: Int,
    )
}
