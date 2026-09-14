package dev.s7a.strata.layout

/**
 * Calculates the absolute slack offset for one child in an arranged sequence.
 *
 * This stateless calculation is shared by linear layouts and individual flow rows.
 * Integer division rounds non-negative slack toward the start edge without accumulating rounding error.
 *
 * @param slack non-negative space remaining within an Int-sized container after child extents and fixed gaps.
 * @param index zero-based child index within the sequence.
 * @param childCount positive number of children in the sequence.
 * @return the additional main-axis offset before this child.
 */
internal fun Arrangement.offset(
    slack: Int,
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
            slack.toLong()
        }

        Arrangement.SpaceBetween -> {
            if (1 < childCount) {
                slack.toLong() * index / (childCount - 1)
            } else {
                0L
            }
        }

        Arrangement.SpaceAround -> {
            slack.toLong() * (2L * index + 1L) / (2L * childCount)
        }

        Arrangement.SpaceEvenly -> {
            slack.toLong() * (index.toLong() + 1L) / (childCount.toLong() + 1L)
        }
    }
