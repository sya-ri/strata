package dev.s7a.strata.layout

/**
 * Scope for reading typed parent data from one direct child.
 *
 * Access is valid only during the callback lifetime of the extending measure or layout scope and only on the owning tree thread.
 * A component scope starts at the requested direct child's outermost modifier and scans its consecutive modifier chain through the innermost modifier, stopping before that component node.
 * A modifier scope starts at its requested effective child and scans only the consecutive inner modifier chain, also stopping before the logical component node.
 * The innermost matching referential key wins.
 * The component node and all entries below it are never queried.
 */
public interface ParentDataScope {
    /**
     * Returns the current parent-data value supplied by one direct child's modifier chain.
     *
     * The query does not measure or place the child and does not mutate retained measurement or placement state.
     * A provider is read only after the complete consecutive chain has been scanned.
     *
     * @param index the absolute direct-child index.
     * @param key the referential parent-data token to find.
     * @return the innermost matching provider value, or null when no provider uses [key].
     * @throws IllegalArgumentException when [index] is outside the direct-child range.
     * @throws IllegalStateException when the callback scope is no longer active or the child scope rejects the access.
     * @throws Throwable when the selected provider fails; the original failure is propagated unchanged.
     */
    public fun <D : Any> childParentData(
        index: Int,
        key: ParentDataKey<D>,
    ): D?
}
