package dev.s7a.strata.quality.benchmark

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sun.jdi.Bootstrap
import com.sun.jdi.VirtualMachine
import com.sun.jdi.event.MethodEntryEvent
import com.sun.jdi.event.MethodExitEvent
import com.sun.jdi.event.VMDisconnectEvent
import com.sun.jdi.request.EventRequest
import dev.s7a.strata.performance.ArtifactIdentity
import dev.s7a.strata.performance.PerformanceJson
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.concurrent.thread

/**
 * Standard-JDK untimed debug process and immutable provenance for real application-owned work observers.
 * Production requests are enabled only between marker calls, excluding fixture setup and cleanup.
 * All process and request ownership terminates on success or failure; no production field or array is modified.
 */
internal object RemoteUntimedObservation {
    /**
     * Launches a frozen probe with fresh count/provenance paths and the common source/archive plan.
     * Caller interpretation supplies typed cases and counters; this helper performs no timing or inference.
     */
    fun run(
        args: Array<String>,
        probeClass: Class<*>,
        fixtureClass: Class<*>,
        workloadId: String,
        observation: RemoteProbeObservation,
    ) {
        require(args.size == 3)
        val output = Path.of(args[0]).toAbsolutePath().normalize()
        val child = Path.of(args[1]).toAbsolutePath().normalize()
        val planFile = Path.of(args[2]).toAbsolutePath().normalize()
        require(Files.exists(output).not() && Files.exists(child).not() && output != child)
        val planHash = ArtifactIdentity.file(planFile)
        val observerClasses = listOf(observation.javaClass, javaClass, RemoteProbeObservation::class.java, RemoteProbeMarker::class.java)
        val observer = ArtifactIdentity.applicationTrees(observerClasses)
        val jvmArguments = ManagementFactory.getRuntimeMXBean().inputArguments
        val vm = launch(probeClass, child, jvmArguments)
        val process = vm.process()
        val readers = mutableListOf<Thread>()
        try {
            readers += thread(name = "remote-untimed-stdout") { process.inputStream.use { it.copyTo(System.out) } }
            readers += thread(name = "remote-untimed-stderr") { process.errorStream.use { it.copyTo(System.err) } }
            observe(vm, probeClass, observation)
            check(process.waitFor() == 0) { "Untimed probe failed; inspect the retained child output" }
            readers.forEach(Thread::join)
            check(observation.complete())
            val provenance = read(child)
            val childArguments = provenance.getAsJsonArray("jvm_arguments").map { it.asString }
            val ordinaryArguments = childArguments.filterNot { it.contentEquals("-Xdebug") || it.startsWith("-Xrunjdwp:") || it.startsWith("-agentlib:jdwp=") }
            require(ordinaryArguments.takeLast(jvmArguments.size) == jvmArguments) { "Untimed child changed the inherited JVM profile" }
            val executable =
                Path.of(
                    ProcessHandle
                        .current()
                        .info()
                        .command()
                        .orElseThrow(),
                )
            require(provenance.get("java_executable_sha256").asString == ArtifactIdentity.file(executable))
            val revision = sourceRevision(planFile, planHash, fixtureClass, provenance)
            require(ArtifactIdentity.applicationTrees(observerClasses) == observer)
            PerformanceJson.writeNew(
                output,
                JsonObject().apply {
                    addProperty("workload_id", workloadId)
                    addProperty("status", "passed")
                    addProperty("untimed", true)
                    add("observer_identity", Gson().toJsonTree(observer))
                    addProperty("source_revision", revision)
                    addProperty("source_plan_sha256", planHash)
                    addProperty("child_provenance", child.toString())
                    addProperty("child_provenance_sha256", ArtifactIdentity.file(child))
                    add("child", provenance)
                    add("phases", observation.rows)
                },
            )
        } finally {
            if (process.isAlive) process.destroyForcibly().waitFor()
            readers.forEach(Thread::join)
        }
    }

    private fun launch(
        probeClass: Class<*>,
        child: Path,
        jvmArguments: List<String>,
    ): VirtualMachine {
        val connector = Bootstrap.virtualMachineManager().defaultConnector()
        val arguments = connector.defaultArguments()
        val classpath = System.getProperty("java.class.path")
        require((jvmArguments + classpath + child.toString()).all { it.contains('"').not() && it.contains('\n').not() && it.contains('\r').not() })
        arguments.getValue("home").setValue(System.getProperty("java.home"))
        arguments.getValue("options").setValue(jvmArguments.joinToString(" ") { "\"$it\"" } + " -cp \"$classpath\"")
        arguments.getValue("main").setValue("${probeClass.name} \"$child\"")
        return connector.launch(arguments)
    }

    private fun observedRequests(
        vm: VirtualMachine,
        classes: List<String>,
    ): List<EventRequest> =
        classes
            .flatMap { name ->
                listOf(
                    vm.eventRequestManager().createMethodEntryRequest().apply { addClassFilter(name) },
                    vm.eventRequestManager().createMethodExitRequest().apply { addClassFilter(name) },
                )
            }
            .onEach { it.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD) }

    private fun observe(
        vm: VirtualMachine,
        probeClass: Class<*>,
        observation: RemoteProbeObservation,
    ) {
        require(vm.canGetMethodReturnValues()) { "The probe requires JDI method return values" }
        val requests = observedRequests(vm, observation.observedClasses)
        vm.eventRequestManager().createMethodEntryRequest().apply {
            addClassFilter(probeClass.name)
            setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD)
            enable()
        }
        vm.eventRequestManager().createMethodExitRequest().apply {
            addClassFilter(probeClass.name)
            setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD)
            enable()
        }
        var observing = false
        var connected = true
        while (connected) {
            val events = vm.eventQueue().remove(60000)
            checkNotNull(events) { "The untimed probe stopped producing events" }
            for (event in events) {
                when (event) {
                    is MethodEntryEvent -> observation.enter(event)
                    is MethodExitEvent -> observation.exit(event)
                    is VMDisconnectEvent -> connected = false
                }
            }
            if (observing != observation.active) {
                if (observation.active) {
                    requests.forEach { it.enable() }
                } else {
                    requests.forEach { it.disable() }
                }
                observing = observation.active
            }
            events.resume()
        }
    }

    private fun sourceRevision(
        planFile: Path,
        planHash: String,
        fixtureClass: Class<*>,
        provenance: JsonObject,
    ): String {
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
        require(plan.get("fixture_tree_sha256") == provenance.getAsJsonObject("fixture_identity").get(fixtureClass.name))
        val source = sources.single { it.getAsJsonObject("archives") == archives }
        require(ArtifactIdentity.file(planFile) == planHash)
        return source.get("revision").asString
    }

    /**
     * Runs the declared untimed controls and preserves their exact input, origin and artifact receipts.
     * Callers run their complete work verifier first and validate their original typed rows before publication.
     */
    fun preflight(
        requestPath: Path,
        observe: (Array<String>) -> Unit,
        verifyRows: (JsonObject) -> Unit,
    ) {
        val requestHash = ArtifactIdentity.file(requestPath)
        val request = read(requestPath)
        require(request.get("schema").asString.contentEquals("strata-paired-jvm-preflight-request-v1"))
        val output = Path.of(request.get("output").asString).toAbsolutePath().normalize()
        val results = JsonArray()
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
            observe(arrayOf(counts.toString(), child.toString(), plan.toString()))
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
}
