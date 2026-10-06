package dev.s7a.strata.performance

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Detached JVM report serialization, shared by every consumer and host adapter.
 */
public object PerformanceJson {
    private val gson = GsonBuilder().setPrettyPrinting().serializeNulls().create()
    private val loadedCollectorIdentity =
        JsonObject().apply {
            addProperty("contract", "strata-performance-testkit-v1")
            addProperty("code_source_sha256", ArtifactIdentity.fullCodeSource(JvmPerformanceMeter::class.java))
        }

    /**
     * Serializes a nonempty distribution using explicit nanosecond units.
     */
    public fun distribution(values: List<Long>): JsonObject {
        val result = PerformanceDistribution.of(values)
        return JsonObject().apply {
            add("raw", gson.toJsonTree(values))
            addProperty("samples", result.samples)
            addProperty("p50_ns", result.p50)
            addProperty("p95_ns", result.p95)
            addProperty("p99_ns", result.p99)
            addProperty("total_ns", result.total)
            addProperty("max_ns", result.maximum)
        }
    }

    /**
     * Saves evidence outside timed intervals; failure is propagated to the caller.
     */
    public fun write(
        path: Path,
        report: JsonObject,
    ) {
        val destination = path.toAbsolutePath().normalize()
        Files.createDirectories(checkNotNull(destination.parent))
        val detached = report.deepCopy()
        detached.add("collector_identity", collectorIdentity())
        Files.writeString(destination, gson.toJson(detached))
    }

    /**
     * Publishes a new collector-bound receipt outside timing, refusing to replace any existing evidence.
     */
    public fun writeNew(
        path: Path,
        report: JsonObject,
    ) {
        val destination = path.toAbsolutePath().normalize()
        Files.createDirectories(checkNotNull(destination.parent))
        val detached = report.deepCopy()
        detached.add("collector_identity", collectorIdentity())
        Files.writeString(destination, gson.toJson(detached), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
    }

    /**
     * Records actual collector bytes separately from the measured application and Strata runtime.
     * A collector change requires fresh evidence on both sides of a comparison.
     */
    public fun collectorIdentity(): JsonObject = loadedCollectorIdentity.deepCopy()

    /**
     * Captures complete runtime diagnostics; truncated node inventories cannot satisfy work assertions.
     */
    public fun diagnostics(snapshot: Any): JsonObject {
        val overflowed = HostReflection.invoke(snapshot, "getOverflowed") as Boolean
        check(overflowed.not()) { "Performance diagnostics overflowed" }
        return JsonObject().apply {
            add("counts", gson.toJsonTree(HostReflection.invoke(snapshot, "getCounts")))
            addProperty("active_subscriptions", HostReflection.invoke(snapshot, "getActiveSubscriptions") as Number)
            addProperty("node_inventory_size", (HostReflection.invoke(snapshot, "getNodes") as List<*>).size)
            addProperty("node_inventory_truncated", false)
        }
    }

    /**
     * Projects complete detached diagnostic counts and gauges for declarative work expectations.
     */
    public fun work(snapshot: JsonObject): Map<String, Long> {
        check(snapshot.get("node_inventory_truncated").asBoolean.not()) { "Performance diagnostics overflowed" }
        val counts = snapshot.getAsJsonObject("counts").entrySet().associate { (key, value) -> key to value.asLong }
        return counts +
            mapOf(
                "ActiveSubscriptions" to snapshot.get("active_subscriptions").asLong,
                "NodeInventorySize" to snapshot.get("node_inventory_size").asLong,
            )
    }

    /**
     * Records actual frame inventory and ordered semantics without retaining the frame.
     */
    public fun frame(
        report: JsonObject,
        frame: Any,
    ) {
        val commands = HostReflection.invoke(frame, "getDrawCommands") as List<*>
        val semantics = HostReflection.invoke(frame, "getSemantics") as List<*>
        report.addProperty("draw_commands", commands.size)
        report.addProperty("semantics", semantics.size)
        val text =
            semantics.joinToString("\n") { entry ->
                val actual = checkNotNull(entry)
                "${HostReflection.invoke(actual, "getBounds")}: ${HostReflection.invoke(actual, "getSemantics")}"
            }
        report.addProperty("semantics_sha256", EntryIdentity.sha256(text.byteInputStream(Charsets.UTF_8)))
    }
}
