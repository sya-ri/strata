package dev.s7a.strata.quality.benchmark

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.s7a.strata.performance.ArtifactIdentity
import dev.s7a.strata.performance.JmhFixtureSelection
import dev.s7a.strata.performance.JvmApiInventory
import dev.s7a.strata.performance.JvmPerformanceInputs
import dev.s7a.strata.performance.JvmPerformanceRunner
import dev.s7a.strata.performance.LoadedArtifactMetadata
import dev.s7a.strata.performance.PerformanceJson
import dev.s7a.strata.performance.PerformancePlan
import java.lang.management.ManagementFactory
import java.nio.file.Path
import java.util.UUID

/**
 * Shared-meter owner CPU/allocation consumer for the complete incoming-fragment corpus.
 * Source and invocation preparation, parity, identity/copy probes and cleanup are outside each measured interval.
 * Warm-up still executes the same operation and checks, with no competing custom sampling or aggregation engine.
 */
public object IncomingFragmentCpuEvidence {
    /**
     * Accepts a fresh report path and repetition 0, 1 or 2 on the frozen fixture/collector and selected runtime classpath.
     */
    @JvmStatic
    public fun main(args: Array<String>) {
        require(args.size == 2)
        val repetition = args[1].toInt()
        require(repetition in 0..2)
        RemoteWorkEvidence.verifySurface()
        IncomingFragmentBenchmark.verifyWork()
        val targets = mapOf("api" to "dev.s7a.strata.projection.ProjectionValue", "core" to "dev.s7a.strata.runtime.spi.RuntimeUiSession", "remote" to "dev.s7a.strata.runtime.remote.RemoteTree")
        val runtime = LoadedArtifactMetadata.capture(javaClass.classLoader, targets, targets.keys)
        LoadedArtifactMetadata.verifyComplete(runtime)
        val surface = JvmApiInventory.capture(javaClass.classLoader, mapOf("remote" to targets.getValue("remote")))
        val fixtures = listOf(IncomingFragmentBenchmark::class.java, IncomingFragmentFixture::class.java, IncomingFragmentDecoder::class.java, IncomingFragmentWorkload::class.java, IncomingFragmentPhase::class.java, IncomingFragmentRoute::class.java)
        val identity = ArtifactIdentity.applicationTrees(fixtures)
        val inputs = inputs().mapValues { ArtifactIdentity.file(it.value) }
        val plan = PerformancePlan(warmup = 100, samples = 200)
        val phases = JsonArray()
        listOf(1, 8).forEach { owners ->
            IncomingFragmentWorkload.entries.forEach { workload ->
                IncomingFragmentPhase.entries.forEach { phase ->
                    val routes = if (phase == IncomingFragmentPhase.ServerIngress) listOf(IncomingFragmentRoute.Production) else IncomingFragmentRoute.entries
                    routes.forEach { route -> phases.add(collect(owners, workload, phase, route, plan)) }
                }
            }
        }
        check(LoadedArtifactMetadata.capture(javaClass.classLoader, targets, targets.keys) == runtime)
        check(JvmApiInventory.capture(javaClass.classLoader, mapOf("remote" to targets.getValue("remote"))) == surface)
        check(ArtifactIdentity.applicationTrees(fixtures) == identity)
        check(inputs().mapValues { ArtifactIdentity.file(it.value) } == inputs)
        PerformanceJson.writeNew(
            Path.of(args[0]),
            JsonObject().apply {
                addProperty("schema_version", 1)
                addProperty("workload_id", "incoming-fragment-ownership-v1")
                addProperty("status", "passed")
                addProperty("run_id", UUID.randomUUID().toString())
                addProperty("repetition", repetition)
                add("runtime_metadata", runtime)
                add("remote_api_symbols", Gson().toJsonTree(surface))
                add("fixture_identity", Gson().toJsonTree(identity))
                add("inputs", Gson().toJsonTree(inputs))
                add("environment", environment())
                add("phases", phases)
                addProperty("native_uploads", "N/A: native-independent incoming transport")
                addProperty("gpu_time", "N/A: no native presentation")
                addProperty("fps", "N/A: no frame-rate claim")
            },
        )
    }

    private fun collect(
        owners: Int,
        workload: IncomingFragmentWorkload,
        phase: IncomingFragmentPhase,
        route: IncomingFragmentRoute,
        plan: PerformancePlan,
    ): JsonObject =
        IncomingFragmentFixture(owners, workload, route).use { fixture ->
            val counts = fixture.workCounts()
            var measured = false
            val sample =
                JvmPerformanceRunner.measure(
                    "$owners/$workload/$phase/$route",
                    plan,
                    beforeSample = {
                        fixture.prepare(phase)
                        measured = true
                    },
                    afterOperation = { _, _ -> fixture.verifyAndClose() },
                    afterSample = { measured = false },
                ) {
                    if (measured) {
                        fixture.receive()
                    } else {
                        fixture.prepare(phase)
                        val result = fixture.receive()
                        fixture.verifyAndClose()
                        result
                    }
                }
            sample.evidence.apply {
                addProperty("owners", owners)
                addProperty("workload", workload.name)
                addProperty("phase", phase.name)
                addProperty("route", route.name)
                addProperty("measured_cycles", plan.samples)
                addProperty("warmup", plan.warmup)
                counts.forEach { (key, value) -> addProperty(key, value * owners) }
                addProperty("timed_assembly_bytes", if (phase == IncomingFragmentPhase.Assembly) counts.getValue("assembly_bytes") * owners else 0)
            }
        }

    private fun inputs(): Map<String, Path> {
        val libraries = JvmPerformanceInputs.read(Path.of(checkNotNull(System.getProperty("strata.performance.inputs"))))
        val fixtures = JmhFixtureSelection.inputs()
        require(libraries.keys.intersect(fixtures.keys).isEmpty())
        return libraries + fixtures
    }

    private fun environment(): JsonObject =
        Gson().toJsonTree(
            listOf("java.version", "java.vendor", "java.vm.name", "os.name", "os.version", "os.arch").associateWith(System::getProperty) +
                mapOf("available_processors" to Runtime.getRuntime().availableProcessors().toString(), "host" to System.getenv("COMPUTERNAME"), "jvm_arguments" to ManagementFactory.getRuntimeMXBean().inputArguments),
        ).asJsonObject
}
