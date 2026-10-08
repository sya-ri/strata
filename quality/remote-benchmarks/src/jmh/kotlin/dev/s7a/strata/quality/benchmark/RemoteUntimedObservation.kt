package dev.s7a.strata.quality.benchmark

import com.google.gson.Gson
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
import java.nio.file.Files
import java.nio.file.Path

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
        observedClasses: List<String>,
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
        val connector = Bootstrap.virtualMachineManager().defaultConnector()
        val arguments = connector.defaultArguments()
        val classpath = System.getProperty("java.class.path")
        require(classpath.contains('"').not() && child.toString().contains('"').not())
        arguments.getValue("home").setValue(System.getProperty("java.home"))
        arguments.getValue("options").setValue("-cp \"$classpath\"")
        arguments.getValue("main").setValue("${probeClass.name} \"$child\"")
        val vm = connector.launch(arguments)
        val process = vm.process()
        try {
            observe(vm, probeClass, observedClasses, observation)
            check(process.waitFor() == 0) { "Untimed probe failed: ${process.errorStream.bufferedReader().readText()}" }
            check(observation.complete())
            val provenance = read(child)
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
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private fun observe(
        vm: VirtualMachine,
        probeClass: Class<*>,
        observedClasses: List<String>,
        observation: RemoteProbeObservation,
    ) {
        require(vm.canGetMethodReturnValues()) { "The probe requires JDI method return values" }
        val entries = observedClasses.map { name ->
            vm.eventRequestManager().createMethodEntryRequest().apply {
                addClassFilter(name)
                setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD)
            }
        }
        val exits = observedClasses.map { name ->
            vm.eventRequestManager().createMethodExitRequest().apply {
                addClassFilter(name)
                setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD)
            }
        }
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
                    entries.forEach { it.enable() }
                    exits.forEach { it.enable() }
                } else {
                    entries.forEach { it.disable() }
                    exits.forEach { it.disable() }
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

    private fun read(path: Path): JsonObject = Files.newBufferedReader(path, Charsets.UTF_8).use { JsonParser.parseReader(it).asJsonObject }
}
