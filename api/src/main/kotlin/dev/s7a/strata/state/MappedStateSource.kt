package dev.s7a.strata.state

import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Immutable projection descriptor; every ordinary subscription owns an independent delivery gate and upstream handle.
 * Retained runtimes use the derived capability to share upstream observations without subscribing to this adapter.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class MappedStateSource<T, R>(
    override val upstream: StateSource<T>,
    private val transform: (T) -> R,
) : DerivedStateSource<R> {
    @Suppress("UNCHECKED_CAST")
    override fun derive(value: Any?): R = transform(value as T)

    override fun subscribe(observer: (StateSnapshot<R>) -> Unit): StateSubscription<R> {
        val delivery = MappedStateDelivery(observer)
        val subscription =
            upstream.subscribe { snapshot ->
                val mapped = StateSnapshot(snapshot.revision, transform(snapshot.value))
                delivery.deliver(mapped)
            }
        return runCatching {
            val initial = subscription.initialSnapshot
            val mapped = StateSnapshot(initial.revision, transform(initial.value))
            val closeUpstream = subscription.retainCloseAction()
            StateSubscription(mapped) {
                delivery.close()
                closeUpstream()
            }
        }.getOrElse { failure ->
            delivery.close()
            val cleanup = runCatching(subscription::close).exceptionOrNull()
            if (cleanup != null && cleanup !== failure) failure.addSuppressed(cleanup)
            throw failure
        }
    }
}
