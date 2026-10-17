package dev.s7a.strata.performance

import com.google.gson.GsonBuilder
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import java.lang.reflect.Field

/**
 * Actual native work and retained payload; missing adapter metrics cannot become zero work.
 */
public object NativePresentationCounters {
    private val names =
        listOf(
            "renderExtractionCount",
            "hostFrameCount",
            "framePreparationCount",
            "portableRasterizationCount",
            "textureUploadCount",
            "sampledImageDirectHitCount",
            "sampledImageDirectMissCount",
            "sampledImageUploadCount",
            "sampledImageDrawCount",
            "sampledImageEvictionCount",
            "sampledImageCapacityFallbackCount",
            "sampledImageIneligibleFallbackCount",
            "sampledImageRetainedEntryCount",
            "sampledImageRetainedByteCount",
        )
    private val gson = GsonBuilder().serializeNulls().create()
    private val payloadNames = listOf("sourceUploadByteCount", "rasterUploadByteCount", "samplingUploadByteCount", "tintFallbackCount", "alphaCutoffFallbackCount", "otherIneligibleFallbackCount")

    /**
     * Reads verified fields from the measured screen hierarchy, never from a replacement presenter.
     */
    public fun read(screen: Any): Map<String, Long> {
        val owner = nativeOwner(screen)
        val counters =
            names.mapNotNull { name ->
                val field = optionalField(owner, name) ?: return@mapNotNull null
                check(field.trySetAccessible()) { "Inaccessible native performance metric: $name" }
                name to field.getLong(owner)
            }.toMap()
        val payloadField = optionalField(owner, "uploadWork") ?: return counters
        check(payloadField.trySetAccessible())
        val payload = payloadField.get(owner) ?: return counters
        return counters +
            payloadNames.associateWith { name ->
                val field = field(payload, name)
                check(field.trySetAccessible()) { "Inaccessible native payload metric: $name" }
                field.getLong(payload)
            }
    }

    /**
     * Adds work deltas, retained counters, and the actual command inventory outside timed extraction.
     */
    public fun append(
        report: JsonObject,
        screen: Any,
        baseline: Map<String, Long>,
    ) {
        val current = read(screen)
        val delta = current.mapValues { (name, value) -> value - baseline.getValue(name) }
        check(delta.filterKeys { it.contains("Retained").not() }.values.all { 0 <= it }) { "Native counters changed generation" }
        report.add("native_counter_delta", gson.toJsonTree(delta).asJsonObject.apply {
            names.forEach { if (has(it).not()) add(it, JsonNull.INSTANCE) }
        })
        report.add("native_counter_availability", JsonObject().apply {
            addProperty("available", names.all { it in delta })
            if (names.any { (it in delta).not() }) addProperty("reason", "The production runtime has no internal presentation-measurement counters.")
        })
        report.add("native_retained", gson.toJsonTree(current.filterKeys { it.contains("Retained") }).asJsonObject.apply {
            names.filter { it.contains("Retained") }.forEach { if (has(it).not()) add(it, JsonNull.INSTANCE) }
        })
        report.add(
            "native_payload",
            JsonObject().apply {
                val available = payloadNames.all { it in delta }
                addProperty("available", available)
                if (available) {
                    payloadNames.forEach { addProperty(it, delta.getValue(it)) }
                    addProperty("unit", "RGBA8 payload bytes; successful CPU uploads only")
                } else {
                    addProperty("reason", "The measured runtime does not expose upload payload counters.")
                    payloadNames.forEach { add(it, JsonNull.INSTANCE) }
                }
            },
        )
        report.add(
            "native_gpu",
            JsonObject().apply {
                addProperty("available", false)
                addProperty("reason", "Extraction callbacks do not identify GPU submission or completion.")
                add("duration", JsonNull.INSTANCE)
                add("operation_to_completion_observation", JsonNull.INSTANCE)
            },
        )
        val owner = nativeOwner(screen)
        val prepared = field(owner, "preparedCommands")
        check(prepared.trySetAccessible())
        val commands = checkNotNull(prepared.get(owner)) as List<*>
        report.addProperty("draw_commands", commands.size)
        report.add("command_types", gson.toJsonTree(commands.groupingBy { checkNotNull(it).javaClass.simpleName }.eachCount()))
    }

    private fun nativeOwner(screen: Any): Any {
        if (optionalField(screen, "preparedCommands") != null || optionalField(screen, names.first()) != null) return screen
        val presenter = field(screen, "presentation")
        check(presenter.trySetAccessible())
        return checkNotNull(presenter.get(screen))
    }

    private fun field(
        owner: Any,
        name: String,
    ): Field = optionalField(owner, name) ?: error("Missing native performance field: $name")

    private fun optionalField(
        owner: Any,
        name: String,
    ): Field? =
        generateSequence(owner.javaClass as Class<*>?) { it.superclass }
            .firstNotNullOfOrNull { type -> type.declaredFields.firstOrNull { it.name == name } }
}
