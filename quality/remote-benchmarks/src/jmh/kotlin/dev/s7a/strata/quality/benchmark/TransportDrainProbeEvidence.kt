package dev.s7a.strata.quality.benchmark

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.sun.jdi.Bootstrap
import com.sun.jdi.VMDisconnectedException
import com.sun.jdi.event.BreakpointEvent
import com.sun.jdi.event.ClassPrepareEvent
import com.sun.jdi.event.MethodEntryEvent
import com.sun.jdi.event.VMDeathEvent
import com.sun.jdi.event.VMDisconnectEvent
import com.sun.jdi.request.BreakpointRequest
import com.sun.jdi.request.EventRequest
import dev.s7a.strata.performance.ArtifactIdentity
import dev.s7a.strata.performance.LoadedArtifactMetadata
import dev.s7a.strata.performance.PerformanceJson
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Counts actual drain probes through JDK debugger breakpoints, separately from all CPU/allocation intervals.
 * Each source file is supplied under its actual loaded runtime archive hash; absent or ambiguous symbols fail.
 * Debugger suspension is instrumentation and must never be reported as transport performance.
 */
@Suppress("StringLiteralComparison") // Decodes external JDI method names at the debugger adapter boundary.
public object TransportDrainProbeEvidence {
    /**
     * Accepts a fresh report, source archive root, peer count and workload on the same frozen measurement classpath.
     */
    @JvmStatic
    @Suppress("LongMethod", "CyclomaticComplexMethod") // Keeps debugger setup, interval boundaries and teardown in one untimed transaction.
    public fun main(args: Array<String>) {
        require(args.size == 4)
        val sites =
            listOf(
                Site("dev.s7a.strata.runtime.remote.RemoteScreenService", "peer.inbox.poll()"),
                Site("dev.s7a.strata.runtime.remote.RemotePacketStream", "pending.remove(nextIncoming)"),
                Site("dev.s7a.strata.runtime.remote.RemoteConnection", "pending.firstOrNull()"),
            )
        val sourceRoot = Path.of(args[1])
        val lines = sites.associateWith { site ->
            val type = Class.forName(site.type)
            val source = sourceRoot.resolve(ArtifactIdentity.fullCodeSource(type)).resolve("${type.simpleName}.kt")
            val matches = Files.readAllLines(source).withIndex().filter { it.value.contains(site.expression) }
            check(matches.size == 1) { "Missing or ambiguous probe source: $source" }
            matches.single().index + 1
        }
        val connector = Bootstrap.virtualMachineManager().defaultConnector()
        val arguments = connector.defaultArguments()
        arguments.getValue("main").setValue("${TransportDrainProbeTarget::class.java.name} ${args[2]} ${args[3]}")
        arguments.getValue("options").setValue("-cp \"${System.getProperty("java.class.path")}\"")
        val vm = connector.launch(arguments)
        val output = Executors.newFixedThreadPool(2)
        val readers = listOf(vm.process().inputStream, vm.process().errorStream).map { stream -> output.submit<String> { stream.bufferedReader().use { it.readText() } } }
        try {
            val manager = vm.eventRequestManager()
            sites.forEach { site ->
                manager.createClassPrepareRequest().apply {
                    addClassFilter(site.type)
                    setSuspendPolicy(EventRequest.SUSPEND_ALL)
                    enable()
                }
            }
            manager.createMethodEntryRequest().apply {
                addClassFilter(TransportDrainProbeTarget::class.java.name)
                setSuspendPolicy(EventRequest.SUSPEND_ALL)
                enable()
            }
            val probes = sites.associate { it.type to 0L }.toMutableMap()
            val installed = mutableSetOf<String>()
            val breakpoints = mutableListOf<BreakpointRequest>()
            var active = false
            var complete = false
            var finished = false
            while (finished.not()) {
                val events = checkNotNull(vm.eventQueue().remove(60_000)) { "Transport probe target stalled." }
                events.forEach { event ->
                    when (event) {
                        is ClassPrepareEvent -> {
                            val site = sites.single { it.type == event.referenceType().name() }
                            val locations = event.referenceType().locationsOfLine(lines.getValue(site))
                            check(locations.size == 1) { "Missing or ambiguous compiled drain probe: ${site.type}" }
                            breakpoints.add(manager.createBreakpointRequest(locations.single()).apply {
                                putProperty("site", site.type)
                                setSuspendPolicy(EventRequest.SUSPEND_ALL)
                            })
                            installed.add(site.type)
                        }
                        is MethodEntryEvent -> when (event.method().name()) {
                            "begin" -> {
                                check(installed.size == sites.size)
                                active = true
                                breakpoints.forEach { it.enable() }
                            }
                            "end" -> {
                                check(active)
                                active = false
                                breakpoints.forEach { it.disable() }
                                complete = true
                            }
                        }
                        is BreakpointEvent -> if (active) {
                            val site = event.request().getProperty("site") as String
                            probes[site] = probes.getValue(site) + 1
                        }
                        is VMDeathEvent, is VMDisconnectEvent -> finished = true
                    }
                }
                if (finished.not()) events.resume()
            }
            check(complete && vm.process().waitFor(10, TimeUnit.SECONDS) && vm.process().exitValue() == 0)
            val logs = readers.map { it.get(10, TimeUnit.SECONDS) }
            PerformanceJson.writeNew(Path.of(args[0]), JsonObject().apply {
                addProperty("contract", "bounded-transport-probes-v1")
                addProperty("status", "passed")
                addProperty("peers", args[2].toInt())
                addProperty("workload", TransportDrainWorkload.valueOf(args[3]).name)
                add("probes", Gson().toJsonTree(probes))
                add("runtime_metadata", LoadedArtifactMetadata.capture(javaClass.classLoader, mapOf("remote" to sites.first().type), setOf("remote")))
                add("logs", Gson().toJsonTree(logs))
            })
        } finally {
            try {
                vm.dispose()
            } catch (_: VMDisconnectedException) {
                // Normal target completion already detached the debugger.
            }
            vm.process().destroy()
            output.shutdownNow()
            check(output.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    /**
     * One source-verified probe expression in the actual runtime, without modified bytecode or queue doubles.
     */
    private data class Site(
        val type: String,
        val expression: String,
    )
}
