package dev.s7a.strata.quality.benchmark

import com.google.gson.JsonParser
import dev.s7a.strata.performance.JmhFixtureSelection
import dev.s7a.strata.performance.JmhWorkloadInventory
import dev.s7a.strata.performance.JvmApiInventory
import dev.s7a.strata.performance.PerformanceCoverage
import dev.s7a.strata.performance.PerformanceHost
import dev.s7a.strata.performance.PerformanceInventory
import dev.s7a.strata.performance.PerformancePhase
import dev.s7a.strata.performance.PerformanceScenario
import dev.s7a.strata.runtime.remote.RemoteMessage
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import java.nio.file.Files
import java.nio.file.Path

/**
 * Actual protocol workload and byte-parity admission without time thresholds or a second benchmark runner.
 */
@OptIn(InternalStrataRuntimeApi::class)
public object RemoteWorkEvidence {
    /**
     * Checks all compiled combinations, exact sparse/full changes, immutable patch parity and real fragmentation.
     */
    @JvmStatic
    public fun main(args: Array<String>) {
        require(args.size <= 1)
        if (args.isNotEmpty()) {
            val destination = Path.of(args.single()).toAbsolutePath().normalize()
            Files.createDirectories(checkNotNull(destination.parent))
            Files.writeString(destination, protocolSurface().getValue("remote").sorted().joinToString("\n", postfix = "\n"))
            return
        }
        verifySurface()
        JmhFixtureSelection.verifyWork(JmhFixtureSelection.all())
        check(JmhWorkloadInventory.capture(listOf(RemoteProtocolBenchmark::class.java), setOf("avgt")).size == 30)
        check(JmhWorkloadInventory.capture(listOf(RemoteProtocolBenchmark::class.java), setOf("avgt"), includes = listOf("RemoteProtocolBenchmark.diff")).size == 6)
        val benchmark = RemoteProtocolBenchmark()
        listOf(100, 8192).forEach { count ->
            RemoteChange.entries.forEach { change ->
                val state = RemoteProtocolBenchmark.Protocol()
                state.nodes = count
                state.change = change
                state.setup()
                val expected =
                    when (change) {
                        RemoteChange.Stable -> 0
                        RemoteChange.Single -> 1
                        RemoteChange.All -> count
                    }
                check(benchmark.diff(state).changed.size == expected)
                check(benchmark.apply(state) == state.after)
                check((benchmark.snapshotCodec(state) as RemoteMessage.Snapshot).tree == state.after)
                val encoded = state.codec.encode(state.update)
                check(state.codec.encode(benchmark.updateCodec(state)).contentEquals(encoded))
                check(benchmark.fragments(state).contentEquals(encoded))
                check(state.before.nodes.size == count)
                println("Remote protocol $count $change: $expected changed records, ${encoded.size} update bytes")
            }
        }
    }

    /**
     * Rejects exact protocol API registration changes before full or smoke collection.
     * Archived-runtime comparisons may select an explicit hashed fixture-input inventory; ordinary checks use the current repository registration.
     * Protocol and retained-session inputs register idle, update and lifetime operations without claiming host transport latency.
     */
    public fun verifySurface() {
        val selectedInventory = System.getProperty("strata.performance.remoteInventory")?.let { Path.of(it).toAbsolutePath().normalize() }
        val symbols =
            if (selectedInventory == null) {
                checkNotNull(javaClass.getResourceAsStream("/remote-api.tsv")).bufferedReader(Charsets.UTF_8).use { it.readLines() }
            } else {
                require(JmhFixtureSelection.inputs().values.any { it.toAbsolutePath().normalize() == selectedInventory }) { "Archived remote inventory must be an explicitly frozen fixture input" }
                Files.readAllLines(selectedInventory, Charsets.UTF_8)
            }
        require(symbols.toSet().size == symbols.size) { "Duplicate remote performance API registration" }
        val feature = "Remote"
        val scenarios =
            JmhWorkloadInventory.capture(listOf(RemoteProtocolBenchmark::class.java), setOf("avgt")).map { identity ->
                val change =
                    RemoteChange.valueOf(
                        JsonParser
                            .parseString(identity)
                            .asJsonArray[2]
                            .asJsonObject
                            .get("change")
                            .asString,
                    )
                val phase = if (change == RemoteChange.Stable) PerformancePhase.Idle else PerformancePhase.Update
                PerformanceScenario(identity, setOf(feature), setOf(PerformanceHost.Jvm), setOf(phase), "remote-protocol-v1")
            }
        val lifetimePhases = mapOf("idle" to PerformancePhase.Idle, "update" to PerformancePhase.Update, "lifecycle" to PerformancePhase.Release)
        val lifetimes =
            JmhWorkloadInventory.capture(listOf(RemoteSessionBenchmark::class.java), setOf("avgt")).map { identity ->
                val method =
                    JsonParser
                        .parseString(identity)
                        .asJsonArray[0]
                        .asString
                        .substringAfterLast('.')
                PerformanceScenario(identity, setOf(feature), setOf(PerformanceHost.Jvm), setOf(requireNotNull(lifetimePhases[method])), "retained-remote-v1")
            }
        val surface = PerformanceInventory(protocolSurface(), mapOf("remote" to symbols.associateWith { feature }), mapOf("runtime/remote/src/" to setOf(feature)))
        val coverage = PerformanceCoverage(mapOf(feature to setOf(PerformanceHost.Jvm)), scenarios + lifetimes, mapOf(feature to setOf(PerformancePhase.Idle, PerformancePhase.Update, PerformancePhase.Release)))
        coverage.selectChangedPaths(surface)
        println("Verified ${symbols.size} exact remote API symbols against ${scenarios.size} protocol and ${lifetimes.size} retained-session cases")
    }

    private fun protocolSurface(): Map<String, Set<String>> = JvmApiInventory.capture(javaClass.classLoader, mapOf("remote" to "dev.s7a.strata.runtime.remote.RemoteTree"))
}
