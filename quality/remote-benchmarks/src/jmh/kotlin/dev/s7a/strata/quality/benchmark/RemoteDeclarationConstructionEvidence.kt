package dev.s7a.strata.quality.benchmark

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.sun.jdi.StringReference
import com.sun.jdi.event.MethodEntryEvent
import com.sun.jdi.event.MethodExitEvent

/**
 * Untimed actual retained/detached declaration and detached-node object counts on both archived runtimes.
 * Constructor delegation is deduplicated by debugger object identity without retaining a production object or frame.
 * These selected sites exclude values, lists, maps, metadata and total allocation; no timing benefit is inferred.
 */
public object RemoteDeclarationConstructionEvidence {
    /**
     * Accepts fresh counts/child-provenance paths and the common immutable source/archive plan used by JMH/CPU.
     */
    @JvmStatic
    public fun main(args: Array<String>) {
        RemoteUntimedObservation.run(args, RemoteDeclarationProbe::class.java, RemoteDeclarationBenchmark::class.java, RemoteDeclarationObservedClass.entries.map { it.externalName }, "remote-declaration-construction-observation-v1", Observation())
    }

    /**
     * Retains only typed current-case keys and selected numeric object identities, released after each operation pair.
     */
    private class Observation : RemoteProbeObservation {
        override val rows = JsonArray()
        override val active: Boolean get() = current != null
        private var current: JsonObject? = null
        private var owner: Long? = null
        private val seen = mutableSetOf<Pair<RemoteDeclarationBenchmark.Size, RemoteDeclarationBenchmark.Workload>>()
        private val identities = RemoteDeclarationObservedClass.entries.associateWith { mutableSetOf<Long>() }

        override fun enter(event: MethodEntryEvent) {
            val method = event.method()
            if (method.declaringType().name() == RemoteDeclarationProbe::class.java.name) {
                when (RemoteProbeMarker.decode(method.name())) {
                    RemoteProbeMarker.Begin -> {
                        check(current == null)
                        val args = event.thread().frame(0).getArgumentValues()
                        val size = RemoteDeclarationBenchmark.Size.valueOf((args[0] as StringReference).value())
                        val workload = RemoteDeclarationBenchmark.Workload.valueOf((args[1] as StringReference).value())
                        check(seen.add(size to workload))
                        owner = event.thread().uniqueID()
                        identities.values.forEach { it.clear() }
                        current = JsonObject().apply {
                            addProperty("size", size.name)
                            addProperty("workload", workload.name)
                            addProperty("observed_operations", 2)
                            RemoteDeclarationObservedClass.entries.forEach { addProperty(it.reportField, 0L) }
                        }
                    }
                    RemoteProbeMarker.Finish -> {
                        check(owner == event.thread().uniqueID())
                        val row = checkNotNull(current)
                        identities.forEach { (kind, instances) -> row.addProperty(kind.reportField, instances.size.toLong()) }
                        rows.add(row)
                        identities.values.forEach { it.clear() }
                        current = null
                        owner = null
                    }
                    null -> Unit
                }
                return
            }
            if (current == null || method.isConstructor.not()) return
            val kind = RemoteDeclarationObservedClass.decode(method.declaringType().name()) ?: return
            check(owner == event.thread().uniqueID())
            identities.getValue(kind).add(checkNotNull(event.thread().frame(0).thisObject()).uniqueID())
        }

        override fun exit(event: MethodExitEvent): Unit = Unit

        override fun complete(): Boolean = current == null && seen.size == 24 && identities.values.all { it.isEmpty() }
    }
}
