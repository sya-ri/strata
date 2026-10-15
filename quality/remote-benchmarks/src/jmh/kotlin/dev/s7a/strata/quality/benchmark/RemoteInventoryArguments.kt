package dev.s7a.strata.quality.benchmark

import com.google.gson.JsonObject
import dev.s7a.strata.performance.ArtifactIdentity
import java.nio.file.Path

/**
 * Separates the one explicitly frozen compiler-inventory argument from otherwise exact JVM conditions.
 * The original argument, selected immutable input label and actual file hash remain in each raw report.
 * No other JVM argument is removed, reordered or normalized.
 */
internal object RemoteInventoryArguments {
    private const val PROPERTY = "strata.performance.remoteInventory"
    private const val PREFIX = "-D$PROPERTY="

    /**
     * Captures no selection, or exactly one actual argument matching one complete frozen input entry.
     * Missing, duplicate, mismatched or ambiguous selections fail rather than broadening environment equality.
     */
    fun capture(
        arguments: List<String>,
        selected: String?,
        inputs: Map<String, Path>,
    ): Pair<List<String>, JsonObject?> {
        val matches = arguments.filter { it.startsWith(PREFIX) }
        if (selected == null) {
            require(matches.isEmpty()) { "Inventory argument has no matching JVM property." }
            return arguments.toList() to null
        }
        val argument = PREFIX + selected
        require(matches == listOf(argument)) { "Inventory argument is absent, repeated or differs from the actual JVM property." }
        val path = Path.of(selected).toAbsolutePath().normalize()
        val entries = inputs.filterValues { it.toAbsolutePath().normalize() == path }
        require(entries.size == 1) { "Inventory argument must select exactly one immutable input entry." }
        val evidence =
            JsonObject().apply {
                addProperty("property", PROPERTY)
                addProperty("argument", argument)
                addProperty("input_label", entries.keys.single())
                addProperty("sha256", ArtifactIdentity.file(path))
            }
        return arguments.map { if (it == argument) PREFIX + "<declared-inventory-input>" else it } to evidence
    }

    /**
     * Independent untimed argument controls using the shipped inventory as fixed file data.
     * All ordinary arguments retain their exact order and values, including unrelated properties.
     */
    fun verifyWork() {
        val path = Path.of(checkNotNull(javaClass.getResource("/remote-api.tsv")).toURI()).toAbsolutePath().normalize()
        val ordinary = listOf("-Xmx768m", "-XX:MaxMetaspaceSize=384m", "-Dunrelated=unchanged")
        check(capture(ordinary, null, emptyMap()) == (ordinary to null))
        val argument = PREFIX + path
        val input = mapOf("inventory-control" to path)
        val actual = capture(ordinary + argument, path.toString(), input)
        check(actual.first == ordinary + (PREFIX + "<declared-inventory-input>"))
        check(actual.second?.get("argument")?.asString == argument)
        check(actual.second?.get("sha256")?.asString == ArtifactIdentity.file(path))
        listOf(ordinary, ordinary + listOf(argument, argument), ordinary + (PREFIX + "different")).forEach { invalid ->
            check(runCatching { capture(invalid, path.toString(), input) }.exceptionOrNull() is IllegalArgumentException)
        }
        check(runCatching { capture(ordinary + argument, null, input) }.exceptionOrNull() is IllegalArgumentException)
        check(runCatching { capture(ordinary + argument, path.toString(), emptyMap()) }.exceptionOrNull() is IllegalArgumentException)
        check(runCatching { capture(ordinary + argument, path.toString(), input + ("ambiguous" to path)) }.exceptionOrNull() is IllegalArgumentException)
    }
}
