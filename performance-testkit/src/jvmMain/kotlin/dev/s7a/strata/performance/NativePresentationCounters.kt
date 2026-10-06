package dev.s7a.strata.performance

import com.google.gson.GsonBuilder
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

    /**
     * Reads verified fields from the measured screen hierarchy, never from a replacement presenter.
     */
    public fun read(screen: Any): Map<String, Long> {
        val owner = nativeOwner(screen)
        return names.associateWith { name ->
            val field = field(owner, name)
            check(field.trySetAccessible()) { "Inaccessible native performance metric: $name" }
            field.getLong(owner)
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
        report.add("native_counter_delta", gson.toJsonTree(delta))
        report.add("native_retained", gson.toJsonTree(current.filterKeys { it.contains("Retained") }))
        val owner = nativeOwner(screen)
        val prepared = field(owner, "preparedCommands")
        check(prepared.trySetAccessible())
        val commands = checkNotNull(prepared.get(owner)) as List<*>
        report.addProperty("draw_commands", commands.size)
        report.add("command_types", gson.toJsonTree(commands.groupingBy { checkNotNull(it).javaClass.simpleName }.eachCount()))
    }

    private fun nativeOwner(screen: Any): Any {
        if (generateSequence(screen.javaClass as Class<*>?) { it.superclass }.any { type -> type.declaredFields.any { it.name == names.first() } }) return screen
        val presenter = field(screen, "presentation")
        check(presenter.trySetAccessible())
        return checkNotNull(presenter.get(screen))
    }

    private fun field(
        owner: Any,
        name: String,
    ): Field =
        generateSequence(owner.javaClass as Class<*>?) { it.superclass }
            .firstNotNullOfOrNull { type -> type.declaredFields.firstOrNull { it.name == name } } ?: error("Missing native performance field: $name")
}
