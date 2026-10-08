package dev.s7a.strata.runtime.velocity

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.s7a.strata.performance.ArtifactIdentity
import dev.s7a.strata.performance.JvmPerformanceInputs
import dev.s7a.strata.performance.JvmPerformanceRunner
import dev.s7a.strata.performance.LoadedArtifactMetadata
import dev.s7a.strata.performance.PerformanceJson
import dev.s7a.strata.performance.PerformancePlan
import java.lang.management.ManagementFactory
import java.nio.file.Path
import java.util.UUID

/**
 * Supplemental CPU/allocation on the actual dedicated Velocity worker, through the existing shared meter.
 * Includes actual API submission, captured ticker execution and exact callback/future checks for each bounded cycle.
 * CPU uses the unchanged real queue; actual probe instrumentation and post-empty latency controls are untimed.
 */
internal object VelocityDrainCpuEvidence {
    /**
     * Accepts one fresh receipt and repetition 0/1/2 on a frozen fixture/collector/runtime/control classpath.
     * Supply the frozen resolved libraries via strata.performance.inputs and the documented JDK opens option.
     */
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 2)
        val repetition = args[1].toInt()
        require(repetition in 0..2)
        val targets = mapOf(
            "api" to "dev.s7a.strata.projection.ProjectionValue",
            "core" to "dev.s7a.strata.runtime.spi.RuntimeUiSession",
            "remote" to "dev.s7a.strata.runtime.remote.RemoteTree",
            "velocity-api" to "dev.s7a.strata.velocity.VelocityUi",
            "velocity" to "dev.s7a.strata.runtime.velocity.VelocityScreens",
        )
        val runtime = LoadedArtifactMetadata.capture(javaClass.classLoader, targets, targets.keys)
        LoadedArtifactMetadata.verifyComplete(runtime)
        val identity = ArtifactIdentity.applicationTrees(listOf(VelocityDrainFixture::class.java, VelocityDrainWorkload::class.java, javaClass))
        val inputManifest = Path.of(checkNotNull(System.getProperty("strata.performance.inputs")))
        val inputs = JvmPerformanceInputs.read(inputManifest).mapValues { ArtifactIdentity.file(it.value) }
        val probes = verifyWork()
        val plan = PerformancePlan(warmup = 100, samples = 200)
        val phases = JsonArray()
        VelocityDrainWorkload.entries.forEach { workload -> phases.add(collect(workload, plan, probes.getValue(workload))) }
        check(LoadedArtifactMetadata.capture(javaClass.classLoader, targets, targets.keys) == runtime)
        check(ArtifactIdentity.applicationTrees(listOf(VelocityDrainFixture::class.java, VelocityDrainWorkload::class.java, javaClass)) == identity)
        check(JvmPerformanceInputs.read(inputManifest).mapValues { ArtifactIdentity.file(it.value) } == inputs)
        PerformanceJson.writeNew(
            Path.of(args[0]),
            JsonObject().apply {
                addProperty("schema_version", 1)
                addProperty("workload_id", "bounded-velocity-drain-v1")
                addProperty("status", "passed")
                addProperty("run_id", UUID.randomUUID().toString())
                addProperty("repetition", repetition)
                add("runtime_metadata", runtime)
                add("fixture_identity", Gson().toJsonTree(identity))
                add("inputs", Gson().toJsonTree(inputs))
                add("environment", environment())
                add("phases", phases)
                addProperty("native_uploads", "N/A: no native presentation")
                addProperty("gpu_time", "N/A: no native presentation")
                addProperty("fps", "N/A: no frame-rate claim")
            },
        )
    }

    /**
     * Automatically verifies all ready/busy bursts, callback failure/cancellation and post-empty latency before timing.
     */
    private fun verifyWork(): Map<VelocityDrainWorkload, WorkControls> =
        VelocityDrainWorkload.entries.associateWith { workload ->
            VelocityDrainFixture(workload, instrumentProbes = true).use { fixture ->
                fixture.onOwner {
                    val probes = fixture.probeCycle()
                    fixture.verifyFailures()
                    val latency = fixture.lateArrival()
                    check(latency[0] in setOf(0L, 7L) && latency[2] == 7L)
                    println("Velocity $workload: polls=$probes; post-empty [first delivery, first polls, final delivery, next polls]=$latency")
                    WorkControls(probes, latency)
                }
            }
        }

    /**
     * Uses the shared fixed owner-thread plan; probe collection never enters a measured interval.
     */
    private fun collect(workload: VelocityDrainWorkload, plan: PerformancePlan, controls: WorkControls): JsonObject =
        VelocityDrainFixture(workload).use { fixture ->
            fixture.onOwner {
                val batch = maxOf(1, 1000 / maxOf(1, workload.commands))
                val sample =
                    JvmPerformanceRunner.measure(workload.name, plan) {
                        var processed = 0L
                        repeat(batch) { processed = fixture.cycle() }
                        processed
                    }
                sample.evidence.apply {
                    addProperty("workload", workload.name)
                    addProperty("queued_commands_per_cycle", workload.commands)
                    addProperty("cycles_per_sample", batch)
                    addProperty("measured_cycles", plan.samples.toLong() * batch)
                    addProperty("warmup", plan.warmup)
                    addProperty("processed_commands", sample.value)
                    addProperty("untimed_command_polls_per_cycle", controls.probes)
                    add("post_empty_arrival", Gson().toJsonTree(controls.lateArrival))
                    addProperty("host_peers", 0)
                }
            }
        }

    /**
     * Detached untimed scalar controls, retained separately from all measured owner intervals.
     */
    private data class WorkControls(val probes: Long, val lateArrival: List<Long>)

    private fun environment(): JsonObject =
        Gson()
            .toJsonTree(
                listOf("java.version", "java.vendor", "java.vm.name", "os.name", "os.version", "os.arch").associateWith(System::getProperty) +
                    mapOf("available_processors" to Runtime.getRuntime().availableProcessors().toString(), "host" to System.getenv("COMPUTERNAME"), "jvm_arguments" to ManagementFactory.getRuntimeMXBean().inputArguments),
            ).asJsonObject
}
