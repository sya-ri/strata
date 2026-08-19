package dev.s7a.strata.state

/**
 * An externally owned source of revisioned state snapshots.
 *
 * [subscribe] atomically registers an observer and captures the latest snapshot at one linearization point, then returns that snapshot together with its close handle.
 * Concurrent subscriptions are supported.
 * Notifications may occur on any thread and may race the return from [subscribe].
 * Every source observation after the linearization point is delivered to the new observer without omission while the subscription remains open, including observations published during the return race.
 * A conforming source serializes callbacks for one subscription so they never overlap or re-enter, and gives each callback a strictly greater revision than the previous callback.
 * Equal revisions within one source identify the same logical observation for every subscription.
 * An observer must return normally.
 * Throwing is a subscriber contract violation; the exception may escape on the delivery thread and all subscription-state guarantees after that violation are unspecified.
 * A successful [StateSubscription.close] prevents the source from starting another callback for that subscription.
 * A notification whose delivery races [StateSubscription.close] may be delivered or suppressed.
 * The source owns its values and subscription resources; the subscriber owns the returned handle.
 *
 * @param T the observed value type.
 */
public fun interface StateSource<out T> {
    /**
     * Establishes one observation and returns its initial value and close handle.
     *
     * @param observer receives newer snapshots and may run on any thread; it must return normally.
     * @return the initial snapshot and an idempotent handle for this observation.
     * @throws Throwable when establishment fails before its linearization point; no observer callback starts and no resource transfers to the caller in that case.
     */
    public fun subscribe(observer: (StateSnapshot<T>) -> Unit): StateSubscription<T>
}
