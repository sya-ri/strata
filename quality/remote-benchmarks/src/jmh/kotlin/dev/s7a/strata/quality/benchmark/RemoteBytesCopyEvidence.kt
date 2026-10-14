package dev.s7a.strata.quality.benchmark

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sun.jdi.ArrayReference
import com.sun.jdi.Method
import com.sun.jdi.ObjectReference
import com.sun.jdi.StringReference
import com.sun.jdi.event.MethodEntryEvent
import com.sun.jdi.event.MethodExitEvent
import dev.s7a.strata.performance.ArtifactIdentity
import dev.s7a.strata.projection.ProjectionValue
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Observes actual Bytes snapshot arrays in a separate untimed JDI process, using only the standard JDK.
 * Array identities distinguish a constructor copy from an ownership transfer; delegating constructors count once.
 * The observer never changes a debuggee field or array, reads contents, or supplies production callbacks.
 * This records snapshot sites only, excluding output growth, incoming reads, final wire snapshots and framing arrays.
 */
public object RemoteBytesCopyEvidence {
    /**
     * Accepts one shared preflight request, or fresh count and child-provenance paths plus the source/archive plan.
     * Run once per measured source on the frozen fixture classpath, outside all timing invocations.
     * The child deadline is extended solely for debug suspension; all observed rows are explicitly untimed.
     */
    @JvmStatic
    public fun main(args: Array<String>) {
        if (args.size == 1) {
            preflight(Path.of(args.single()))
            return
        }
        RemoteUntimedObservation.run(args, RemoteBytesCopyProbe::class.java, RemoteBytesBenchmark::class.java, "remote-bytes-copy-observation-v1", Observation())
    }

    private fun preflight(requestPath: Path) {
        val requestHash = ArtifactIdentity.file(requestPath)
        val request = read(requestPath)
        require(request.get("schema").asString.contentEquals("strata-paired-jvm-preflight-request-v1"))
        val output = Path.of(request.get("output").asString).toAbsolutePath().normalize()
        val results = JsonArray()
        RemoteBytesBenchmark.verifyWork()
        for (entry in request.getAsJsonArray("controls")) {
            val control = entry.asJsonObject
            val id = control.get("id").asString
            val arguments = control.getAsJsonArray("arguments").map { it.asString }
            require(arguments.size == 1 && id.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]*")))
            val input = request.getAsJsonObject("inputs").getAsJsonObject(arguments.single())
            val plan = Path.of(input.get("path").asString)
            require(ArtifactIdentity.file(plan) == input.get("sha256").asString)
            val directory = output.resolve(id)
            Files.createDirectory(directory)
            val counts = directory.resolve("counts.json")
            val child = directory.resolve("child.json")
            main(arrayOf(counts.toString(), child.toString(), plan.toString()))
            val observed = read(counts)
            verifyRows(observed)
            results.add(
                JsonObject().apply {
                    addProperty("id", id)
                    addProperty("status", "passed")
                    add("observations", observed)
                    add("origins", origins(request, read(child)))
                    add(
                        "artifacts",
                        JsonArray().apply {
                            listOf(counts, child).forEach { file ->
                                add(
                                    JsonObject().apply {
                                        addProperty("path", output.relativize(file).toString().replace('\\', '/'))
                                        addProperty("sha256", ArtifactIdentity.file(file))
                                    },
                                )
                            }
                        },
                    )
                },
            )
            require(ArtifactIdentity.file(plan) == input.get("sha256").asString)
        }
        require(ArtifactIdentity.file(requestPath) == requestHash)
        val receipt =
            JsonObject().apply {
                addProperty("schema", "strata-paired-jvm-preflight-receipt-v1")
                addProperty("status", "passed")
                addProperty("request_sha256", requestHash)
                add("controls", results)
            }
        Files.writeString(output.resolve("receipt.json"), receipt.toString(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
    }

    private fun verifyRows(observed: JsonObject) {
        val rows = observed.getAsJsonArray("phases").map { it.asJsonObject }
        val typed = rows.map { Triple(RemoteBytesBenchmark.Corpus.valueOf(it.get("corpus").asString), RemoteBytesOperation.valueOf(it.get("operation").asString), it) }
        require(typed.map { it.first to it.second }.toSet().size == 64 && typed.size == 64)
        val construction = typed.single { it.first == RemoteBytesBenchmark.Corpus.Small32 && it.second == RemoteBytesOperation.ProjectionConstruction }.third
        require(construction.get("constructor_snapshot_arrays").asLong == 1L && construction.get("constructor_snapshot_bytes").asLong == 32L)
        require(
            typed.filter { it.first == RemoteBytesBenchmark.Corpus.ByteFree || it.second == RemoteBytesOperation.ActionFraming }.all { row ->
                listOf("constructor_snapshot_arrays", "constructor_snapshot_bytes", "extraction_snapshot_arrays", "extraction_snapshot_bytes").all { row.third.get(it).asLong == 0L }
            },
        )
    }

    private fun origins(
        request: JsonObject,
        provenance: JsonObject,
    ): JsonObject {
        val runtimeClasses =
            provenance
                .getAsJsonObject("runtime_metadata")
                .getAsJsonArray("modules")
                .map { it.asJsonObject.get("representativeClass").asString }
                .toSet()
        val result = JsonObject()
        request.getAsJsonObject("required_classes").keySet().forEach { name ->
            val pid =
                if (name in runtimeClasses) {
                    provenance.get("pid").asLong
                } else {
                    Class.forName(name, false, javaClass.classLoader)
                    ProcessHandle.current().pid()
                }
            result.add(name, JsonObject().apply { addProperty("pid", pid) })
        }
        return result
    }

    private fun read(path: Path): JsonObject = Files.newBufferedReader(path, Charsets.UTF_8).use { JsonParser.parseReader(it).asJsonObject }

    /**
     * Fixed complete corpus counts with only currently executing constructors retained until their matching return.
     */
    private class Observation : RemoteProbeObservation {
        override val observedClasses = listOf(ProjectionValue.Bytes::class.java.name)
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
                        current =
                            JsonObject().apply {
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

                    null -> {}
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
