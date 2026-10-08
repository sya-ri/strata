package dev.s7a.strata.quality.benchmark

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.sun.jdi.ArrayReference
import com.sun.jdi.Method
import com.sun.jdi.ObjectReference
import com.sun.jdi.StringReference
import com.sun.jdi.event.MethodEntryEvent
import com.sun.jdi.event.MethodExitEvent

/**
 * Observes actual Bytes snapshot arrays in a separate untimed JDI process, using only the standard JDK.
 * Array identities distinguish a constructor copy from an ownership transfer; delegating constructors count once.
 * The observer never changes a debuggee field or array, reads contents, or supplies production callbacks.
 * This records snapshot sites only, excluding output growth, incoming reads, final wire snapshots and framing arrays.
 */
public object RemoteBytesCopyEvidence {
    /**
     * Accepts fresh count and child-provenance paths plus the same frozen source/archive plan used by JMH.
     * Run once per measured source on the frozen fixture classpath, outside all timing invocations.
     * The child deadline is extended solely for debug suspension; all observed rows are explicitly untimed.
     */
    @JvmStatic
    public fun main(args: Array<String>) {
        RemoteUntimedObservation.run(args, RemoteBytesCopyProbe::class.java, RemoteBytesBenchmark::class.java, listOf("dev.s7a.strata.projection.ProjectionValue\$Bytes"), "remote-bytes-copy-observation-v1", Observation())
    }

    /**
     * Fixed complete corpus counts with only currently executing constructors retained until their matching return.
     */
    private class Observation : RemoteProbeObservation {
        override val rows = JsonArray()
        override val active: Boolean get() = current != null
        private val seen = mutableSetOf<Pair<RemoteBytesBenchmark.Corpus, RemoteBytesOperation>>()
        private val calls = ArrayDeque<Call>()
        private var current: JsonObject? = null
        private var owner: Long? = null

        /**
         * Reads arguments while suspended, then keeps only object mirrors required by the current invocation.
         */
        override fun enter(event: MethodEntryEvent) {
            val method = event.method()
            if (method.declaringType().name() == RemoteBytesCopyProbe::class.java.name) {
                when (RemoteProbeMarker.decode(method.name())) {
                    RemoteProbeMarker.Begin -> {
                        check(current == null && calls.isEmpty())
                        val arguments = event.thread().frame(0).getArgumentValues()
                        val corpus = RemoteBytesBenchmark.Corpus.valueOf((arguments[0] as StringReference).value())
                        val operation = RemoteBytesOperation.valueOf((arguments[1] as StringReference).value())
                        check(seen.add(corpus to operation))
                        owner = event.thread().uniqueID()
                        current = JsonObject().apply {
                            addProperty("corpus", corpus.name)
                            addProperty("operation", operation.name)
                            addProperty("constructor_snapshot_arrays", 0L)
                            addProperty("constructor_snapshot_bytes", 0L)
                            addProperty("extraction_snapshot_arrays", 0L)
                            addProperty("extraction_snapshot_bytes", 0L)
                        }
                    }
                    RemoteProbeMarker.Finish -> {
                        check(owner == event.thread().uniqueID() && calls.isEmpty())
                        rows.add(checkNotNull(current))
                        current = null
                        owner = null
                    }
                    null -> Unit
                }
                return
            }
            if (current == null || (method.isConstructor.not() && method.name().contentEquals("toByteArray").not())) return
            check(owner == event.thread().uniqueID())
            val frame = event.thread().frame(0)
            val instance = checkNotNull(frame.thisObject())
            val input = if (method.isConstructor) frame.getArgumentValues().first() as ArrayReference else content(instance)
            if (method.isConstructor) calls.lastOrNull()?.takeIf { it.instance.uniqueID() == instance.uniqueID() }?.delegates = true
            calls.addLast(Call(method, instance, input.uniqueID(), input.length()))
        }

        /**
         * Counts only actual distinct output arrays after successful constructors or defensive extraction return.
         */
        override fun exit(event: MethodExitEvent) {
            if (current == null || calls.lastOrNull()?.method != event.method()) return
            check(owner == event.thread().uniqueID())
            val call = calls.removeLast()
            if (call.delegates) return
            val output = if (call.method.isConstructor) content(call.instance) else event.returnValue() as ArrayReference
            check(output.length() == call.length)
            if (call.inputIdentity != output.uniqueID()) {
                val prefix = if (call.method.isConstructor) "constructor" else "extraction"
                val row = checkNotNull(current)
                row.addProperty(prefix + "_snapshot_arrays", row.get(prefix + "_snapshot_arrays").asLong + 1)
                row.addProperty(prefix + "_snapshot_bytes", row.get(prefix + "_snapshot_bytes").asLong + call.length)
            }
        }

        /**
         * Rejects missing cases, unfinished operations, and outstanding snapshot calls.
         */
        override fun complete(): Boolean = current == null && calls.isEmpty() && seen.size == RemoteBytesBenchmark.Corpus.entries.size * RemoteBytesOperation.entries.size

        private fun content(instance: ObjectReference): ArrayReference = instance.getValue(checkNotNull(instance.referenceType().fieldByName("content"))) as ArrayReference
    }

    /**
     * One current call's source identity, exact length and object mirror; outer delegation has no independent copy.
     */
    private class Call(
        val method: Method,
        val instance: ObjectReference,
        val inputIdentity: Long,
        val length: Int,
        var delegates: Boolean = false,
    )
}
