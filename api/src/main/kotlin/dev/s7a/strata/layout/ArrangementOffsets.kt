package dev.s7a.strata.layout

/**
 * Calculates the absolute slack offset for one child in an arranged sequence.
 *
 * This stateless calculation is shared by linear layouts and individual flow rows.
 * Integer division rounds non-negative slack toward the start edge without accumulating rounding error.
 *
 * @param slack non-negative space remaining after child extents and fixed gaps.
 * @param index zero-based child index within the sequence.
 * @param childCount positive number of children in the sequence.
 * @return the additional main-axis offset before this child.
 * @throws ArithmeticException when checked intermediate arithmetic overflows.
 */
internal fun Arrangement.offset(
    slack: Long,
    index: Int,
    childCount: Int,
): Long =
    when (this) {
        Arrangement.Start -> {
            0L
        }

        Arrangement.Center -> {
            slack / 2L
        }

        Arrangement.End -> {
            slack
        }

        Arrangement.SpaceBetween -> {
            if (1 < childCount) {
                Math.multiplyExact(slack, index.toLong()) / (childCount - 1).toLong()
            } else {
                0L
            }
        }

        Arrangement.SpaceAround -> {
            val numerator =
                Math.multiplyExact(
                    slack,
                    Math.addExact(Math.multiplyExact(2L, index.toLong()), 1L),
                )
            numerator / Math.multiplyExact(2L, childCount.toLong())
        }

        Arrangement.SpaceEvenly -> {
            Math.multiplyExact(slack, Math.addExact(index.toLong(), 1L)) /
                Math.addExact(childCount.toLong(), 1L)
        }
    }
