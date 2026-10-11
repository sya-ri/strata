package dev.s7a.strata.quality.benchmark

/** Executes the natural-bounds row inside its external untimed trace boundary. */
internal object UnihexBoundsTrace {
    /** Prepares the bounds state before [begin], then passes exactly one bounds result to [end]. */
    internal fun naturalBounds(
        fixture: UnihexBoundsFixture?,
        begin: () -> Unit,
        end: (Any) -> Unit,
    ) {
        val state =
            UnihexBoundsBenchmark.BoundsState().also {
                it.fixture = checkNotNull(fixture)
                it.setup()
            }
        begin()
        end(state.bounds())
    }
}
