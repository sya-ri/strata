package dev.s7a.strata.quality.benchmark

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sun.jdi.ArrayReference
import com.sun.jdi.Bootstrap
import com.sun.jdi.Method
import com.sun.jdi.ObjectReference
import com.sun.jdi.StringReference
import com.sun.jdi.event.MethodEntryEvent
import com.sun.jdi.event.MethodExitEvent
import com.sun.jdi.event.VMDisconnectEvent
import com.sun.jdi.request.EventRequest
import dev.s7a.strata.performance.ArtifactIdentity
import dev.s7a.strata.performance.PerformanceJson
import java.nio.file.Files
import java.nio.file.Path

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
        require(args.size == 3)
        val output = Path.of(args[0]).toAbsolutePath().normalize()
        val child = Path.of(args[1]).toAbsolutePath().normalize()
        val planFile = Path.of(args[2]).toAbsolutePath().normalize()
        require(Files.exists(output).not() && Files.exists(child).not() && output != child)
        val planHash = ArtifactIdentity.file(planFile)
        val observer = ArtifactIdentity.applicationTrees(listOf(javaClass))
        val connector = Bootstrap.virtualMachineManager().defaultConnector()
        val arguments = connector.defaultArguments()
        val classpath = System.getProperty("java.class.path")
        require(classpath.contains('"').not() && child.toString().contains('"').not())
        arguments.getValue("home").setValue(System.getProperty("java.home"))
        arguments.getValue("options").setValue("-cp \"$classpath\"")
        arguments.getValue("main").setValue("${RemoteBytesCopyProbe::class.java.name} \"$child\"")
        val vm = connector.launch(arguments)
        val process = vm.process()
        val observation = Observation()
        try {
            require(vm.canGetMethodReturnValues()) { "The probe requires JDI array return identities" }
            for (name in listOf("dev.s7a.strata.projection.ProjectionValue\$Bytes", RemoteBytesCopyProbe::class.java.name)) {
                vm.eventRequestManager().createMethodEntryRequest().apply {
                    addClassFilter(name)
                    setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD)
                    enable()
                }
                vm.eventRequestManager().createMethodExitRequest().apply {
                    addClassFilter(name)
                    setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD)
                    enable()
                }
            }
            var connected = true
            while (connected) {
                val events = vm.eventQueue().remove(60000)
                checkNotNull(events) { "The copy probe stopped producing events" }
                for (event in events) {
                    when (event) {
                        is MethodEntryEvent -> observation.enter(event)
                        is MethodExitEvent -> observation.exit(event)
                        is VMDisconnectEvent -> connected = false
                    }
                }
                events.resume()
            }
            check(process.waitFor() == 0) { "Copy probe failed: ${process.errorStream.bufferedReader().readText()}" }
            check(observation.complete())
            val provenance = read(child)
            val plan = read(planFile)
            val archives = JsonObject()
            provenance.getAsJsonObject("runtime_metadata").getAsJsonArray("modules").forEach { entry ->
                val module = entry.asJsonObject
                archives.addProperty(module.get("module").asString, module.getAsJsonObject("codeSource").get("sha256").asString)
            }
            val sources = plan.getAsJsonArray("sources").map { it.asJsonObject }
            require(sources.size == 2 && sources.map { it.get("revision").asString }.distinct().size == 2)
            val revision = Regex("[0-9a-f]{40}")
            val hash = Regex("[0-9a-f]{64}")
            require(plan.get("fixture_revision").asString.matches(revision))
            sources.forEach { source ->
                require(source.get("revision").asString.matches(revision))
                val recorded = source.getAsJsonObject("archives")
                require(recorded.keySet() == setOf("api", "core", "remote") && recorded.entrySet().all { it.value.asString.matches(hash) })
            }
            require(plan.get("fixture_tree_sha256") == provenance.getAsJsonObject("fixture_identity").get(RemoteBytesBenchmark::class.java.name))
            val source = sources.single { it.getAsJsonObject("archives") == archives }
            require(ArtifactIdentity.file(planFile) == planHash)
            require(ArtifactIdentity.applicationTrees(listOf(javaClass)) == observer)
            PerformanceJson.writeNew(
                output,
                JsonObject().apply {
                    addProperty("workload_id", "remote-bytes-copy-observation-v1")
                    addProperty("status", "passed")
                    addProperty("untimed", true)
                    add("observer_identity", Gson().toJsonTree(observer))
                    addProperty("source_revision", source.get("revision").asString)
                    addProperty("source_plan_sha256", planHash)
                    addProperty("child_provenance", child.toString())
                    addProperty("child_provenance_sha256", ArtifactIdentity.file(child))
                    add("child", provenance)
                    add("phases", observation.rows)
                },
            )
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private fun read(path: Path): JsonObject = Files.newBufferedReader(path, Charsets.UTF_8).use { JsonParser.parseReader(it).asJsonObject }

    /**
     * Fixed complete corpus counts with only currently executing constructors retained until their matching return.
     */
    private class Observation {
        val rows = JsonArray()
        private val seen = mutableSetOf<Pair<RemoteBytesBenchmark.Corpus, RemoteBytesOperation>>()
        private val calls = ArrayDeque<Call>()
        private var current: JsonObject? = null
        private var owner: Long? = null

        /**
         * Reads arguments while suspended, then keeps only object mirrors required by the current invocation.
         */
        fun enter(event: MethodEntryEvent) {
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
        fun exit(event: MethodExitEvent) {
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
        fun complete(): Boolean = current == null && calls.isEmpty() && seen.size == RemoteBytesBenchmark.Corpus.entries.size * RemoteBytesOperation.entries.size

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
