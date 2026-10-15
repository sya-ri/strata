package dev.s7a.strata.quality.benchmark

import com.google.gson.JsonObject
import dev.s7a.strata.performance.ArtifactIdentity
import java.nio.file.Files
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
     * Revalidates a recorded selection against the actual complete immutable input map.
     * Raw arguments stay intact; only the one admitted inventory argument has a comparison representation.
     */
    fun verify(
        arguments: List<String>,
        recorded: JsonObject?,
        inputs: Map<String, Path>,
    ): List<String> {
        val selected =
            recorded?.get("argument")?.asString?.let { argument ->
                require(argument.startsWith(PREFIX)) { "Recorded inventory argument has the wrong property." }
                argument.removePrefix(PREFIX)
            }
        val actual = capture(arguments, selected, inputs)
        require(actual.second == recorded) { "Recorded inventory selection differs from its actual input." }
        return actual.first
    }

    /**
     * Independent untimed argument controls using the shipped inventory as fixed file data.
     * Directory and JAR loads stream the same shipped bytes into one owned file released before return.
     * All ordinary arguments retain their exact order and values, including unrelated properties.
     */
    fun verifyWork() {
        val path = Files.createTempFile("strata-remote-inventory-control-", ".tsv")
        AutoCloseable { Files.delete(path) }.use {
            checkNotNull(javaClass.getResourceAsStream("/remote-api.tsv")).use { source ->
                Files.newOutputStream(path).use { output -> source.copyTo(output) }
            }
            verifyControlFile(path)
        }
    }

    private fun verifyControlFile(path: Path) {
        val ordinary = listOf("-Xmx768m", "-XX:MaxMetaspaceSize=384m", "-Dunrelated=unchanged")
        check(capture(ordinary, null, emptyMap()) == (ordinary to null))
        val argument = PREFIX + path
        val input = mapOf("inventory-control" to path)
        val actual = capture(ordinary + argument, path.toString(), input)
        check(actual.first == ordinary + (PREFIX + "<declared-inventory-input>"))
        check(actual.second?.get("argument")?.asString == argument)
        check(actual.second?.get("sha256")?.asString == ArtifactIdentity.file(path))
        check(verify(ordinary, null, emptyMap()) == ordinary)
        check(verify(ordinary + argument, actual.second, input) == actual.first)
        for (field in listOf("property", "argument", "input_label", "sha256")) {
            val changed = checkNotNull(actual.second).deepCopy().apply { addProperty(field, "changed") }
            check(runCatching { verify(ordinary + argument, changed, input) }.exceptionOrNull() is IllegalArgumentException)
        }
        val extra = checkNotNull(actual.second).deepCopy().apply { addProperty("unknown", "field") }
        check(runCatching { verify(ordinary + argument, extra, input) }.exceptionOrNull() is IllegalArgumentException)
        check(verify(ordinary + argument + "-Dunrelated=changed", actual.second, input) != actual.first)
        listOf(ordinary, ordinary + listOf(argument, argument), ordinary + (PREFIX + "different")).forEach { invalid ->
            check(runCatching { capture(invalid, path.toString(), input) }.exceptionOrNull() is IllegalArgumentException)
        }
        check(runCatching { capture(ordinary + argument, null, input) }.exceptionOrNull() is IllegalArgumentException)
        check(runCatching { capture(ordinary + argument, path.toString(), emptyMap()) }.exceptionOrNull() is IllegalArgumentException)
        check(runCatching { capture(ordinary + argument, path.toString(), input + ("ambiguous" to path)) }.exceptionOrNull() is IllegalArgumentException)
    }
}
