package dev.s7a.strata.runtime.velocity

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.s7a.strata.performance.LoadedArtifactMetadata
import dev.s7a.strata.performance.PerformanceJson
import java.nio.file.Path

/**
 * Untimed actual-handler child; markers bound debugger observations and never provide performance timings.
 */
internal object NativeRoutingProbeTarget {
    /**
     * Writes independently checked complete work rows with the actual loaded plugin/common runtime identities.
     */
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 1)
        val runtime = NativeRoutingCpuEvidence.runtime()
        LoadedArtifactMetadata.verifyComplete(runtime)
        val rows = JsonArray()
        listOf(1, 8).forEach { players ->
            NativeRoutingWorkload.entries.forEach { workload ->
                NativeRoutingDirection.entries.forEach { direction ->
                    NativeRoutingPhase.entries.filter { it != NativeRoutingPhase.OwnerProcessing || direction == NativeRoutingDirection.ClientProxy }.forEach { phase ->
                        rows.add(collect(players, workload, direction, phase))
                    }
                }
            }
        }
        check(NativeRoutingCpuEvidence.runtime() == runtime)
        PerformanceJson.writeNew(
            Path.of(args[0]),
            JsonObject().apply {
                add("runtime_metadata", runtime)
                add("fixture_identity", Gson().toJsonTree(NativeRoutingCpuEvidence.identity()))
                add("rows", rows)
            },
        )
    }

    private fun collect(
        players: Int,
        workload: NativeRoutingWorkload,
        direction: NativeRoutingDirection,
        phase: NativeRoutingPhase,
    ): JsonObject =
        NativeRoutingFixture(players, workload, direction).use { fixture ->
            fixture.prepare()
            val work = fixture.inputCounts().toMutableMap()
            if (phase == NativeRoutingPhase.OwnerProcessing) {
                fixture.callback()
                work.putAll(fixture.verifyCallback())
            }
            val operation = {
                begin(players, workload.name, direction.name, phase.name)
                val value =
                    when (phase) {
                        NativeRoutingPhase.Callback -> fixture.callback()
                        NativeRoutingPhase.PublicDecode -> fixture.decodePublic()
                        NativeRoutingPhase.OwnerProcessing -> fixture.processOwner()
                    }
                end(if (phase == NativeRoutingPhase.Callback) fixture.snapshotArrays() else emptyArray())
                value
            }
            val value = if (phase == NativeRoutingPhase.OwnerProcessing) fixture.onOwner(operation) else operation()
            if (phase == NativeRoutingPhase.Callback) work.putAll(fixture.verifyCallback())
            if (phase == NativeRoutingPhase.OwnerProcessing) work.putAll(fixture.verifyOwnerProcessing())
            val row =
                JsonObject().apply {
                    addProperty("players", players)
                    addProperty("workload", workload.name)
                    addProperty("direction", direction.name)
                    addProperty("phase", phase.name)
                    addProperty("operation_result", value)
                    add("work", Gson().toJsonTree(work))
                }
            fixture.finish()
            row
        }

    /**
     * Marker arguments identify one actual callback, public decoding or UI-owner interval to the external debugger.
     */
    @Suppress("UnusedParameter") // JDI reads actual argument values at method entry.
    fun begin(
        players: Int,
        workload: String,
        direction: String,
        phase: String,
    ) = Unit

    /**
     * Transfers only debugger-visible actual queued arrays for untimed defensive-snapshot identity checks.
     */
    @Suppress("UnusedParameter") // JDI reads the actual snapshot array identities at method entry.
    fun end(snapshots: Array<ByteArray>) = Unit
}
