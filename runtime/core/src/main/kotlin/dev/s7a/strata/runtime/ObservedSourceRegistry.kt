package dev.s7a.strata.runtime

import dev.s7a.strata.node.StateObserverNode
import dev.s7a.strata.runtime.diagnostics.UiRenderMetric
import dev.s7a.strata.runtime.platform.Collections
import dev.s7a.strata.runtime.platform.IdentityMap
import dev.s7a.strata.runtime.platform.identitySet
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.DerivedStateSource
import dev.s7a.strata.state.StateSource

/**
 * Tree-owned identity registry sharing source subscriptions across retained observation regions.
 * Owner-thread operations retain one binding per referenced source and one ordered source list per node.
 * External callbacks only enqueue into bindings; capture precedes every commit and terminal close releases all sources.
 */
@OptIn(InternalStrataRuntimeApi::class)
@Suppress("TooManyFunctions") // Subscription and dependency lifetimes must share one operation boundary.
internal class ObservedSourceRegistry(
    private val monitoring: RenderMonitoring = RenderMonitoring(),
) : AutoCloseable {
    private val bindings = IdentityMap<StateSource<*>, ObservedSourceBinding>()
    private val owners = IdentityMap<StateObserverNode, List<StateSource<*>>>()
    private val unusedBindings = LinkedHashSet<ObservedSourceBinding>()
    private val acquiring: MutableSet<StateSource<*>> = identitySet()
    private var frameActive = false
    private var operationActive = false
    private var contentUpdates = false
    private val changed = ArrayDeque<ObservedSourceBinding>()
    private val notified: MutableSet<StateObserverNode> = identitySet()

    /**
     * Number of currently acquired external subscriptions, excluding derived graph edges.
     */
    var activeSubscriptions: Int = 0
        private set

    /**
     * Consumes the owner-thread scheduling signal without evaluating any content.
     */
    fun takeContentUpdates(): Boolean = contentUpdates.also { contentUpdates = false }

    /**
     * Retains temporarily unreferenced bindings during one owner-thread tree operation.
     */
    fun beginOperation() {
        check(operationActive.not()) { "An observation operation is already active." }
        operationActive = true
    }

    /**
     * Finishes an operation and releases unused sources unless an owning frame still needs their snapshots.
     * Every unused subscription is closed before return, with later cleanup failures suppressed on the first.
     */
    fun endOperation() {
        operationActive = false
        releaseUnused()
    }

    /**
     * Ends the owning frame's retention interval; the surrounding tree operation completes deferred cleanup.
     */
    fun finishFrame() {
        frameActive = false
        releaseUnused()
    }

    /**
     * Acquires newly referenced sources before releasing old ones and supplies the frame-consistent values.
     */
    fun synchronize(node: StateObserverNode) {
        val declaredSources = node.observedSources
        val previous = owners[node]
        if (previous != null && previous.size == declaredSources.size && previous.indices.all { previous[it] === declaredSources[it] }) return
        val sources = declaredSources.toList()
        sources.forEach { source ->
            acquire(source)
        }
        owners[node] = sources
        previous?.forEach { source -> checkNotNull(bindings[source]).consumers.remove(node) }
        sources.forEach { source -> checkNotNull(bindings[source]).consumers.add(node) }
        if (previous != null) release(previous)
        monitoring.record(UiRenderMetric.ConsumerNotification)
        node.commitObservedValues(values(sources))
        contentUpdates = true
    }

    /**
     * Captures all current sources before arbitrary equality code or observer publication can run.
     */
    fun capture() {
        frameActive = true
        bindings.values.forEach(ObservedSourceBinding::capture)
    }

    /**
     * Commits every captured source before notifying any region; notifications never evaluate content.
     */
    fun commit() {
        bindings.values.forEach { binding ->
            if (binding.commit()) {
                monitoring.record(UiRenderMetric.RootValueChange)
                changed.addLast(binding)
            }
        }
        if (changed.isEmpty()) return
        while (changed.isNotEmpty()) {
            val binding = changed.removeFirst()
            notified.addAll(binding.consumers)
            binding.dependents.forEach { dependent ->
                monitoring.record(UiRenderMetric.Projection)
                if (dependent.derive()) changed.addLast(dependent) else monitoring.record(UiRenderMetric.ProjectionEqual)
            }
        }
        notified.forEach { node ->
            monitoring.record(UiRenderMetric.ConsumerNotification)
            node.commitObservedValues(values(checkNotNull(owners[node])))
        }
        if (notified.isNotEmpty()) contentUpdates = true
        notified.clear()
    }

    /**
     * Removes a node's references before user cleanup callbacks can run.
     * The current frame or operation may retain its last binding for a later replacement node.
     */
    fun remove(node: StateObserverNode) {
        owners.remove(node)?.let { sources ->
            sources.forEach { source -> checkNotNull(bindings[source]).consumers.remove(node) }
            release(sources)
        }
    }

    private fun acquire(source: StateSource<*>): ObservedSourceBinding {
        val binding = bindings[source] ?: createBinding(source).also { bindings[source] = it }
        binding.references += 1
        unusedBindings.remove(binding)
        return binding
    }

    private fun createBinding(source: StateSource<*>): ObservedSourceBinding {
        check(acquiring.add(source)) { "Derived state dependencies must be acyclic." }
        try {
            val upstream = (source as? DerivedStateSource<*>)?.let { acquire(it.upstream) }
            if (upstream != null) monitoring.record(UiRenderMetric.Projection)
            return runCatching { ObservedSourceBinding(source, upstream) }
                .getOrElse { failure ->
                    if (upstream != null) releaseBinding(upstream)
                    throw failure
                }.also { binding ->
                    if (upstream == null) {
                        activeSubscriptions += 1
                        monitoring.subscription(true)
                    } else {
                        upstream.dependents.add(binding)
                    }
                }
        } finally {
            acquiring.remove(source)
        }
    }

    private fun values(sources: List<StateSource<*>>): List<Any?> = Collections.unmodifiableList(sources.map { source -> checkNotNull(bindings[source]).value })

    private fun release(sources: List<StateSource<*>>) {
        sources.forEach { source ->
            releaseBinding(checkNotNull(bindings[source]))
        }
        releaseUnused()
    }

    private fun releaseBinding(binding: ObservedSourceBinding) {
        binding.references -= 1
        if (binding.references == 0) unusedBindings.add(binding)
    }

    private fun releaseUnused() {
        if (frameActive || operationActive || unusedBindings.isEmpty()) return
        val failures = FailureAccumulator()
        while (unusedBindings.isNotEmpty()) {
            val binding = unusedBindings.first()
            unusedBindings.remove(binding)
            bindings.remove(binding.source)
            binding.upstream?.let { upstream ->
                upstream.dependents.remove(binding)
                releaseBinding(upstream)
            }
            failures.capture { closeBinding(binding) }
        }
        failures.throwIfPresent()
    }

    override fun close() {
        frameActive = false
        operationActive = false
        owners.clear()
        acquiring.clear()
        unusedBindings.clear()
        changed.clear()
        notified.clear()
        contentUpdates = false
        val closing = bindings.values.toList()
        bindings.clear()
        val failures = FailureAccumulator()
        closing.forEach { binding -> failures.capture { closeBinding(binding) } }
        failures.throwIfPresent()
    }

    private fun closeBinding(binding: ObservedSourceBinding) {
        if (binding.upstream == null) {
            activeSubscriptions -= 1
            monitoring.subscription(false)
        }
        binding.close()
    }
}
