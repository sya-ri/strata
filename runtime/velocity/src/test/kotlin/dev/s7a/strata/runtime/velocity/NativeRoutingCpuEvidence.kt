package dev.s7a.strata.runtime.velocity

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.s7a.strata.performance.ArtifactIdentity
import dev.s7a.strata.performance.JmhFixtureSelection
import dev.s7a.strata.performance.JvmApiInventory
import dev.s7a.strata.performance.JvmPerformanceInputs
import dev.s7a.strata.performance.JvmPerformanceRunner
import dev.s7a.strata.performance.LoadedArtifactMetadata
import dev.s7a.strata.performance.PerformanceJson
import dev.s7a.strata.performance.PerformancePlan
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * Complete actual-plugin consumer of the shared CPU/allocation phase runner.
 * Callback/public decoding runs on its caller; asynchronous endpoint work runs on the actual UI owner.
 * Setup, events, assertions, dispatch/waits, debugger probes and terminal release stay outside sampled operations.
 */
internal object NativeRoutingCpuEvidence {
    private val targets =
        mapOf(
            "api" to "dev.s7a.strata.projection.ProjectionValue",
            "core" to "dev.s7a.strata.runtime.spi.RuntimeUiSession",
            "remote" to "dev.s7a.strata.runtime.remote.RemoteTree",
            "velocity-api" to "dev.s7a.strata.velocity.VelocityUi",
            "velocity" to "dev.s7a.strata.runtime.velocity.StrataVelocityPlugin",
        )

    /**
     * Accepts a fresh report path and independent repetition0/1/2 on the frozen fixture/control/runtime classpath.
     */
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 2)
        val repetition = args[1].toInt()
        require(repetition in 0..2)
        val runtime = runtime()
        LoadedArtifactMetadata.verifyComplete(runtime)
        val identity = identity()
        val inputs = inputs().mapValues { ArtifactIdentity.file(it.value) }
        val surface = JvmApiInventory.capture(javaClass.classLoader, targets.filterKeys { it in setOf("remote", "velocity") })
        val probes = probes(runtime, identity)
        val phases = JsonArray()
        val plan = PerformancePlan(warmup = 100, samples = 200)
        probes.getAsJsonArray("rows").forEach { value ->
            val row = value.asJsonObject
            val players = row.get("players").asInt
            val workload = NativeRoutingWorkload.valueOf(row.get("workload").asString)
            val direction = NativeRoutingDirection.valueOf(row.get("direction").asString)
            val phase = NativeRoutingPhase.valueOf(row.get("phase").asString)
            phases.add(collect(players, workload, direction, phase, row, plan))
        }
        check(runtime() == runtime && identity() == identity)
        check(inputs().mapValues { ArtifactIdentity.file(it.value) } == inputs)
        check(JvmApiInventory.capture(javaClass.classLoader, targets.filterKeys { it in setOf("remote", "velocity") }) == surface)
        PerformanceJson.writeNew(
            Path.of(args[0]),
            JsonObject().apply {
                addProperty("schema_version", 1)
                addProperty("workload_id", "native-envelope-routing-v1")
                addProperty("status", "passed")
                addProperty("run_id", UUID.randomUUID().toString())
                addProperty("repetition", repetition)
                add("runtime_metadata", runtime)
                add("runtime_api_symbols", Gson().toJsonTree(surface))
                add("fixture_identity", Gson().toJsonTree(identity))
                add("inputs", Gson().toJsonTree(inputs))
                add("environment", environment())
                add("phases", phases)
                addProperty("native_uploads", "N/A: native routing has no presentation uploads")
                addProperty("gpu_time", "N/A: no native presentation")
                addProperty("fps", "N/A: no frame-rate claim")
            },
        )
    }

    /**
     * Actual plugin and common runtime code-source and class-tree provenance, including API adapters.
     */
    fun runtime(): JsonObject = LoadedArtifactMetadata.capture(javaClass.classLoader, targets, targets.keys)

    /**
     * The same frozen application trees cover all actual-handler helpers and the existing authenticated Harness.
     */
    fun identity() = ArtifactIdentity.applicationTrees(listOf(NativeRoutingFixture::class.java, VelocityScreensTest.Harness::class.java, javaClass))

    private fun collect(
        players: Int,
        workload: NativeRoutingWorkload,
        direction: NativeRoutingDirection,
        phase: NativeRoutingPhase,
        row: JsonObject,
        plan: PerformancePlan,
    ): JsonObject =
        NativeRoutingFixture(players, workload, direction).use { fixture ->
            val expected = row.getAsJsonObject("work")
            val prepare = { prepareSample(fixture, phase, expected) }
            val verify = {
                if (phase == NativeRoutingPhase.Callback) verifyCounts(fixture.verifyCallback(), expected)
                if (phase == NativeRoutingPhase.OwnerProcessing) verifyCounts(fixture.verifyOwnerProcessing(), expected)
                fixture.finish()
            }
            val operation = {
                when (phase) {
                    NativeRoutingPhase.Callback -> fixture.callback()
                    NativeRoutingPhase.PublicDecode -> fixture.decodePublic()
                    NativeRoutingPhase.OwnerProcessing -> fixture.processOwner()
                }
            }
            val measure = {
                var measured = false
                val sample =
                    JvmPerformanceRunner.measure(
                        "$players/$workload/$direction/$phase",
                        plan,
                        beforeSample = {
                            prepare()
                            measured = true
                        },
                        afterOperation = { _, value ->
                            check(value == row.get("operation_result").asInt)
                            verify()
                        },
                        afterSample = { measured = false },
                    ) {
                        if (measured) {
                            operation()
                        } else {
                            prepare()
                            val result = operation()
                            check(result == row.get("operation_result").asInt)
                            verify()
                            result
                        }
                    }
                sample.evidence.apply {
                    addProperty("players", players)
                    addProperty("workload", workload.name)
                    addProperty("direction", direction.name)
                    addProperty("phase", phase.name)
                    addProperty("meter_thread", if (phase == NativeRoutingPhase.OwnerProcessing) "actual-ui-owner" else "callback-caller")
                    addProperty("measured_cycles", plan.samples)
                    addProperty("warmup", plan.warmup)
                    addProperty("operation_result", sample.value)
                    add("work", expected.deepCopy())
                    add("untimed_probes", row.getAsJsonObject("probes").deepCopy())
                }
            }
            if (phase == NativeRoutingPhase.OwnerProcessing) fixture.onOwner(measure) else measure()
        }

    private fun prepareSample(
        fixture: NativeRoutingFixture,
        phase: NativeRoutingPhase,
        expected: JsonObject,
    ) {
        fixture.prepare()
        check(
            Gson()
                .toJsonTree(fixture.inputCounts())
                .asJsonObject
                .entrySet()
                .all { (key, value) -> expected.get(key) == value },
        )
        if (phase == NativeRoutingPhase.OwnerProcessing) {
            fixture.callback()
            verifyCounts(fixture.verifyCallback(), expected)
        }
    }

    private fun verifyCounts(
        actual: Map<String, Long>,
        expected: JsonObject,
    ) {
        actual.forEach { (key, value) -> check(expected.get(key).asLong == value) }
    }

    @Suppress("StringLiteralComparison") // Decodes the external frozen probe contract before matching the actual runtime.
    private fun probes(runtime: JsonObject, identity: Any): JsonObject {
        val matches =
            JmhFixtureSelection.inputs().values.mapNotNull { path ->
                if (path.fileName
                        .toString()
                        .endsWith(".json")
                        .not()
                ) {
                    return@mapNotNull null
                }
                val report = Files.newBufferedReader(path).use { JsonParser.parseReader(it).asJsonObject }
                if (report.get("contract")?.asString == "native-routing-return-arrays-v1" && report.get("runtime_metadata") == runtime) report else null
            }
        require(matches.size == 1) { "Exactly one immutable actual-runtime native routing probe must match; freeze both variants as identical fixture inputs" }
        val report = matches.single()
        require(report.get("status").asString == "passed" && report.get("fixture_identity") == Gson().toJsonTree(identity))
        val rows = report.getAsJsonArray("rows")
        val expected =
            listOf(1, 8)
                .flatMap { players ->
                    NativeRoutingWorkload.entries.flatMap { workload ->
                        NativeRoutingDirection.entries.flatMap { direction ->
                            NativeRoutingPhase.entries.filter { it != NativeRoutingPhase.OwnerProcessing || direction == NativeRoutingDirection.ClientProxy }.map { phase -> listOf(players.toString(), workload.name, direction.name, phase.name) }
                        }
                    }
                }.toSet()
        val actual = rows.map { row -> listOf("players", "workload", "direction", "phase").map { row.asJsonObject.get(it).asString } }
        require(actual.size == expected.size && actual.toSet() == expected) { "Incomplete or duplicate native routing probe matrix" }
        return report
    }

    private fun inputs(): Map<String, Path> {
        val libraries = JvmPerformanceInputs.read(Path.of(checkNotNull(System.getProperty("strata.performance.inputs"))))
        val fixtures = JmhFixtureSelection.inputs()
        require(libraries.keys.intersect(fixtures.keys).isEmpty())
        return libraries + fixtures
    }

    private fun environment(): JsonObject =
        Gson()
            .toJsonTree(
                listOf("java.version", "java.vendor", "java.vm.name", "os.name", "os.version", "os.arch").associateWith(System::getProperty) +
                    mapOf("available_processors" to Runtime.getRuntime().availableProcessors().toString(), "host" to System.getenv("COMPUTERNAME"), "jvm_arguments" to ManagementFactory.getRuntimeMXBean().inputArguments),
            ).asJsonObject
}
