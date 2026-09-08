package dev.s7a.strata.state

/**
 * Creates a lazy, caller-owned projection of this source, supporting nullable values and chained projections.
 * Construction does not subscribe or evaluate [transform]. Keep the returned identity when consumers share a projection.
 * The transformation must be deterministic, side-effect-free, and return normally; ordinary subscriptions may transform on any notifying thread.
 * Ordinary subscriptions preserve every revision, including equal results. Retained UI consumers instead share the upstream subscription,
 * transform a frame-committed input, and suppress dependent UI work when the transformed result is equal by `==`.
 * No source history or global cache is retained. Heavy work and I/O belong outside the transformation.
 *
 * @param transform pure transformation from each upstream value to the published value.
 * @return an immutable source which begins observation only when subscribed or attached to a consuming UI.
 */
public fun <T, R> StateSource<T>.map(transform: (T) -> R): StateSource<R> = MappedStateSource(this, transform)
