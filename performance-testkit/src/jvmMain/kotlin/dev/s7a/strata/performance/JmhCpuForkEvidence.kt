package dev.s7a.strata.performance

import com.google.gson.JsonObject
import org.openjdk.jmh.infra.BenchmarkParams
import org.openjdk.jmh.infra.IterationParams
import org.openjdk.jmh.results.IterationResult
import org.openjdk.jmh.runner.IterationType
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

/**
 * Untimed invocation-bound identities for every real fork and iteration in an opted-in CPU attempt.
 */
internal class JmhCpuForkEvidence(
    private val expected: JsonObject,
) {
    private val forkId = UUID.randomUUID().toString()
    private val process = ProcessHandle.current()
    private val javaCommand = Path.of(checkNotNull(process.info().command().orElse(null)))
    private val javaHash = ArtifactIdentity.file(javaCommand)
    private var iteration = 0
    private var before: JsonObject? = null
    private var measured = 0
    private val processStarted = checkNotNull(process.info().startInstant().orElse(null)).toEpochMilli()
    private var verifiedReady: Long? = null

    /**
     * Verifies the real fork conditions before its first warm-up interval.
     */
    internal fun ready() {
        if (before == null) {
            before = JmhCpuForkHost.capture(expected.objectField("cpu").objectField("context"), process, javaCommand)
            verifiedReady = Instant.now().toEpochMilli().also { check(processStarted <= it) { "CPU fork startup clock moved backwards" } }
        }
    }

    /**
     * Publishes fresh identities after the mandatory loaded-artifact check has completed.
     */
    internal fun record(
        benchmark: BenchmarkParams,
        parameters: IterationParams,
        result: IterationResult,
    ) {
        val cpu = expected.objectField("cpu")
        val destination = Path.of(cpu.textField("directory"))
        val index = iteration++
        val context = cpu.objectField("context")
        if (parameters.type == IterationType.MEASUREMENT) measured++
        val completed = parameters.type == IterationType.MEASUREMENT && measured == parameters.count
        val after = if (completed) JmhCpuForkHost.capture(context, process, javaCommand) else checkNotNull(before)
        PerformanceJson.writeNew(
            destination.resolve("$forkId-$index.json"),
            JsonObject().apply {
                addProperty("contract", "strata-jmh-cpu-fork-v1")
                addProperty("status", "passed")
                addProperty("run_id", cpu.textField("run_id"))
                addProperty("fork_id", forkId)
                addProperty("process_id", process.pid())
                addProperty("java_command", javaCommand.toString())
                addProperty("java_sha256", javaHash)
                addProperty("process_start_unix_millis", processStarted)
                addProperty("verified_ready_unix_millis", checkNotNull(verifiedReady))
                add("executor_observation", checkNotNull(before))
                add("executor_after", after)
                addProperty("iteration_id", UUID.randomUUID().toString())
                addProperty("iteration", index)
                addProperty("all_operations", result.metadata.allOps)
                addProperty("kind", parameters.type.name)
                addProperty("workload", JmhPerformanceRunner.workloadIdentity(benchmark.benchmark, benchmark.mode.shortLabel(), benchmark.paramsKeys.associateWith(benchmark::getParam)))
                add("context", cpu.objectField("context"))
                add("loaded_identity", expected.deepCopy().apply { remove("cpu") })
            },
        )
    }
}
