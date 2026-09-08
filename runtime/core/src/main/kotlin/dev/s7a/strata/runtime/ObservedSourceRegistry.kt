package dev.s7a.strata.runtime

import dev.s7a.strata.node.StateObserverNode
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.StateSource
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Tree-owned identity registry sharing source subscriptions across retained observation regions.
 * Owner-thread operations retain one binding per referenced source and one ordered source list per node.
 * External callbacks only enqueue into bindings; capture precedes every commit and terminal close releases all sources.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class ObservedSourceRegistry : AutoCloseable {
    private val bindings = IdentityHashMap<StateSource<*>, ObservedSourceBinding>()
    private val owners = IdentityHashMap<StateObserverNode, List<StateSource<*>>>()
    private val unusedBindings = LinkedHashSet<ObservedSourceBinding>()
    private var frameActive = false
    private var operationActive = false

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
        val sources = node.observedSources.toList()
        val previous = owners[node]
        if (previous != null && previous.size == sources.size && previous.indices.all { previous[it] === sources[it] }) return
        sources.forEach { source ->
            val binding = bindings[source] ?: ObservedSourceBinding(source).also { bindings[source] = it }
            binding.references += 1
            unusedBindings.remove(binding)
        }
        owners[node] = sources
        if (previous != null) release(previous)
        node.commitObservedValues(values(sources))
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
        var changed = false
        bindings.values.forEach { binding ->
            if (binding.commit()) changed = true
        }
        if (changed) owners.forEach { (node, sources) -> node.commitObservedValues(values(sources)) }
    }

    /**
     * Removes a node's references before user cleanup callbacks can run.
     * The current frame or operation may retain its last binding for a later replacement node.
     */
    fun remove(node: StateObserverNode) {
        owners.remove(node)?.let(::release)
    }

    private fun values(sources: List<StateSource<*>>): List<Any?> = Collections.unmodifiableList(sources.map { source -> checkNotNull(bindings[source]).value })

    private fun release(sources: List<StateSource<*>>) {
        sources.forEach { source ->
            val binding = checkNotNull(bindings[source])
            binding.references -= 1
            if (binding.references == 0) unusedBindings.add(binding)
        }
        releaseUnused()
    }

    private fun releaseUnused() {
        if (frameActive || operationActive || unusedBindings.isEmpty()) return
        val closing = unusedBindings.toList()
        unusedBindings.clear()
        bindings.entries.removeIf { entry -> entry.value.references == 0 }
        val failures = FailureAccumulator()
        closing.forEach { binding -> failures.capture(binding::close) }
        failures.throwIfPresent()
    }

    override fun close() {
        frameActive = false
        operationActive = false
        owners.clear()
        unusedBindings.clear()
        val closing = bindings.values.toList()
        bindings.clear()
        val failures = FailureAccumulator()
        closing.forEach { binding -> failures.capture(binding::close) }
        failures.throwIfPresent()
    }
}
