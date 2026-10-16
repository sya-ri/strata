package dev.s7a.strata.quality.benchmark

import com.google.gson.JsonObject
import dev.s7a.strata.performance.ArtifactIdentity
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.runner.options.CommandLineOptions
import java.lang.management.ManagementFactory
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
     * Keeps the exact selected inventory on the admission JVM without forwarding it to timed forks.
     * All other inherited arguments retain their bytes and order through standard JMH options.
     * Explicit fork replacements are refused instead of overriding a consumer's chosen conditions.
     */
    fun jmhArguments(
        options: List<String>,
        fixtures: List<Class<*>>,
        inputs: Map<String, Path>,
    ): List<String> {
        val inherited = ManagementFactory.getRuntimeMXBean().inputArguments
        val selected = System.getProperty(PROPERTY)
        capture(inherited, selected, inputs)
        return if (selected == null) options else forkArguments(options, fixtures, inherited.filterNot { it.startsWith(PREFIX) })
    }

    private fun forkArguments(
        options: List<String>,
        fixtures: List<Class<*>>,
        inherited: List<String>,
    ): List<String> {
        val parsed = CommandLineOptions(*options.toTypedArray())
        require(listOf(parsed.jvmArgs, parsed.jvmArgsPrepend, parsed.jvmArgsAppend).all { it.orElse(null) == null }) {
            "Explicit JMH JVM arguments cannot be combined with an admission-only inventory."
        }
        require(
            fixtures.all { fixture ->
                generateSequence<Class<*>>(fixture) { it.superclass }.all { type ->
                    type.isAnnotationPresent(Fork::class.java).not() && type.declaredMethods.none { it.isAnnotationPresent(Fork::class.java) }
                }
            },
        ) { "Annotated JMH forks cannot be combined with an admission-only inventory." }
        val arguments = options + if (inherited.isEmpty()) listOf("-jvmArgs", "") else inherited.flatMap { listOf("-jvmArgs", it) }
        require(CommandLineOptions(*arguments.toTypedArray()).jvmArgs.orElse(emptyList()).toList() == inherited) {
            "Standard JMH options did not preserve the complete inherited argument sequence."
        }
        return arguments
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
        verifyForkArguments(ordinary)
    }

    private fun verifyForkArguments(ordinary: List<String>) {
        val options = listOf("RemoteProtocolBenchmark", "-wi", "3", "-i", "5", "-f", "1")
        val fixtures = listOf(RemoteProtocolBenchmark::class.java)
        val inherited = ordinary + listOf("-Dfixture=C:\\source with spaces\\fixed", "-Dquoted=a\"b")
        val actual = forkArguments(options, fixtures, inherited)
        check(actual.take(options.size) == options)
        check(CommandLineOptions(*actual.toTypedArray()).jvmArgs.orElse(emptyList()).toList() == inherited)
        check(CommandLineOptions(*forkArguments(options, fixtures, emptyList()).toTypedArray()).jvmArgs.orElse(ordinary).isEmpty())
        listOf("-jvmArgs", "-jvmArgsPrepend", "-jvmArgsAppend").forEach { flag ->
            check(runCatching { forkArguments(options + listOf(flag, "-Xmx2g"), fixtures, ordinary) }.exceptionOrNull() is IllegalArgumentException)
        }
        check(runCatching { forkArguments(options, listOf(AnnotatedForkControl::class.java), ordinary) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Fork
    private class AnnotatedForkControl
}
