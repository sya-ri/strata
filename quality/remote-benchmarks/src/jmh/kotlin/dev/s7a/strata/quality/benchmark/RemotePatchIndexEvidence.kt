package dev.s7a.strata.quality.benchmark

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.sun.jdi.ArrayReference
import com.sun.jdi.IntegerValue
import com.sun.jdi.LongValue
import com.sun.jdi.StringReference
import com.sun.jdi.event.MethodEntryEvent
import com.sun.jdi.event.MethodExitEvent
import dev.s7a.strata.runtime.remote.RemoteDeclaration
import dev.s7a.strata.runtime.remote.RemoteTree

/**
 * Untimed observation of actual component identity reads used by tree-constructor indexing on both runtime archives.
 * Modifier validation reads are separate preserved-work controls, classified by explicit fixture component membership.
 * No map wrapper, reflection assignment, production counter, object-layout assumption, or sampling is used.
 */
public object RemotePatchIndexEvidence {
    /**
     * Accepts fresh count/provenance paths and the same frozen source/archive plan as JMH and supplemental CPU.
     * The shared standard-JDK launcher verifies actual child archives and complete immutable observer identity.
     */
    @JvmStatic
    public fun main(args: Array<String>) {
        RemoteUntimedObservation.run(args, RemotePatchIndexProbe::class.java, RemotePatchIndexBenchmark::class.java, listOf(RemoteDeclaration::class.java.name), "remote-patch-index-observation-v1", Observation())
    }

    /**
     * One current operation with no retained debugger frame or production tree; rows contain only primitive counts.
     */
    private class Observation : RemoteProbeObservation {
        override val rows = JsonArray()
        override val active: Boolean get() = current != null
        private val seen = mutableSetOf<List<Any>>()
        private var current: JsonObject? = null
        private var components = emptySet<Long>()
        private var owner: Long? = null
        private var constructorRead = false

        /**
         * Decodes marker arguments into typed cases and records whether the actual getter caller is a tree constructor.
         */
        override fun enter(event: MethodEntryEvent) {
            val method = event.method()
            if (method.declaringType().name() == RemotePatchIndexProbe::class.java.name) {
                when (RemoteProbeMarker.decode(method.name())) {
                    RemoteProbeMarker.Begin -> {
                        check(current == null && constructorRead.not())
                        val arguments = event.thread().frame(0).getArgumentValues()
                        val nodes = (arguments[0] as IntegerValue).value()
                        val shape = RemotePatchIndexBenchmark.Shape.valueOf((arguments[1] as StringReference).value())
                        val change = RemotePatchIndexBenchmark.Change.valueOf((arguments[2] as StringReference).value())
                        val operation = RemotePatchIndexOperation.valueOf((arguments[3] as StringReference).value())
                        check(seen.add(listOf(nodes, shape, change, operation)))
                        components = (arguments[4] as ArrayReference).getValues().map { (it as LongValue).value() }.toSet()
                        owner = event.thread().uniqueID()
                        current = JsonObject().apply {
                            addProperty("nodes", nodes)
                            addProperty("shape", shape.name)
                            addProperty("change", change.name)
                            addProperty("operation", operation.name)
                            addProperty("index_component_identity_reads", 0L)
                            addProperty("validation_modifier_identity_reads", 0L)
                        }
                    }
                    RemoteProbeMarker.Finish -> {
                        check(owner == event.thread().uniqueID() && constructorRead.not())
                        rows.add(checkNotNull(current))
                        current = null
                        components = emptySet()
                        owner = null
                    }
                    null -> Unit
                }
                return
            }
            if (current == null || method.name().contentEquals("getIdentity").not()) return
            check(owner == event.thread().uniqueID() && constructorRead.not())
            val caller = event.thread().frame(1).location().method()
            constructorRead = caller.isConstructor && caller.declaringType().name() == RemoteTree::class.java.name
        }

        /**
         * Counts the returned identity only for a proven tree-construction getter invocation.
         */
        override fun exit(event: MethodExitEvent) {
            if (current == null || event.method().name().contentEquals("getIdentity").not() || constructorRead.not()) return
            check(owner == event.thread().uniqueID())
            val identity = (event.returnValue() as LongValue).value()
            val field = if (identity in components) "index_component_identity_reads" else "validation_modifier_identity_reads"
            val row = checkNotNull(current)
            row.addProperty(field, row.get(field).asLong + 1)
            constructorRead = false
        }

        /**
         * Rejects missing node/shape/change/operation combinations or an unfinished observed call.
         */
        override fun complete(): Boolean = current == null && constructorRead.not() && seen.size == 72
    }
}
