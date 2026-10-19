package dev.s7a.strata.performance

import com.google.gson.JsonParser
import org.openjdk.jmh.runner.BenchmarkList
import org.openjdk.jmh.runner.format.OutputFormatFactory
import org.openjdk.jmh.runner.options.VerboseMode
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/**
 * Selects compiled fixtures from JMH metadata without a separate corpus registry.
 * Selection and optional deterministic work checks run before timing or creating receipt directories.
 */
public object JmhFixtureSelection {
    /**
     * Resolves comma-separated qualified or unambiguous simple class names, preserving caller order.
     * Defaults are used only when no selection is supplied; unknown, empty and duplicate names fail.
     */
    public fun select(
        defaults: List<Class<*>>,
        requested: String? = System.getProperty("strata.performance.benchmarks"),
    ): List<Class<*>> {
        val available = entries().map { it.userClassQName }.toSet()
        val names = requested?.split(',')?.map(String::trim) ?: defaults.map { it.name.replace('$', '.') }
        return resolve(names, available).map(::load)
    }

    /**
     * Returns every generated fixture on this collector's classpath for untimed completeness checks.
     */
    public fun all(): List<Class<*>> =
        entries()
            .map { it.userClassQName }
            .distinct()
            .sorted()
            .map(::load)

    /**
     * Uses anchored class names so similarly named fixtures cannot enter the selected matrix.
     */
    public fun includes(fixtures: List<Class<*>>): List<String> = fixtures.map { "^${Regex.escape(it.name.replace('$', '.'))}\\.[^.]+$" }

    /**
     * Resolves optional qualified or unambiguous `Class.method` IDs within the selected fixtures.
     */
    public fun methods(
        fixtures: List<Class<*>>,
        requested: String? = System.getProperty("strata.performance.workloads"),
    ): List<String> {
        val available = JmhWorkloadInventory.capture(fixtures, setOf("avgt")).map { JsonParser.parseString(it).asJsonArray[0].asString }.toSet()
        if (requested == null) return available.sorted()
        val names = requested.split(',').map(String::trim)
        require(names.all(String::isNotBlank)) { "Empty JMH method selection" }
        val selected =
            names.map { name ->
                val qualified = "${name.substringBeforeLast('.').replace('$', '.')}.${name.substringAfterLast('.')}"
                val matches =
                    if (name in available) {
                        listOf(name)
                    } else if (qualified in available) {
                        listOf(qualified)
                    } else {
                        available.filter { "${it.substringBeforeLast('.').substringAfterLast('.').substringAfterLast('$')}.${it.substringAfterLast('.')}" == name }
                    }
                require(matches.size == 1) { "Unknown or ambiguous JMH method: $name" }
                matches.single()
            }
        require(selected.distinct().size == selected.size) { "Duplicate JMH method selection" }
        return selected
    }

    /**
     * Invokes each selected fixture's optional public static no-argument work check once.
     * A failed check propagates its original cause before any JMH fork starts.
     */
    public fun verifyWork(fixtures: List<Class<*>>) {
        fixtures.distinct().forEach { fixture ->
            val method =
                try {
                    fixture.getMethod("verifyWork")
                } catch (_: NoSuchMethodException) {
                    null
                }
            if (method != null && Modifier.isStatic(method.modifiers)) {
                require(method.returnType == Void.TYPE) { "Fixture work verification must return Unit: ${fixture.name}" }
                try {
                    method.invoke(null)
                } catch (failure: InvocationTargetException) {
                    throw failure.cause ?: failure
                }
            }
        }
    }

    /**
     * Reads optional compiled-parameter subsets from a UTF-8 properties file.
     */
    public fun parameters(): Map<String, Set<String>> =
        System
            .getProperty("strata.performance.parameters")
            ?.let { value ->
                val properties = Properties().apply { Files.newBufferedReader(Path.of(value), Charsets.UTF_8).use(::load) }
                properties.stringPropertyNames().associateWith { name ->
                    val values = properties.getProperty(name).split(',').map(String::trim)
                    require(values.all(String::isNotBlank) && values.distinct().size == values.size) { "Empty or duplicate JMH parameter selection: $name" }
                    values.toSet()
                }
            }.orEmpty()

    /**
     * Reads optional external fixture files for preservation alongside resolved control libraries.
     */
    public fun inputs(): Map<String, Path> = System.getProperty("strata.performance.fixtureInputs")?.let { JvmPerformanceInputs.read(Path.of(it)) }.orEmpty()

    /**
     * Rejects include filters that admit any method outside the registered complete workload matrix.
     */
    public fun verifyIncludes(
        expected: Set<String>,
        includes: List<String>,
    ) {
        val methods = expected.map { JsonParser.parseString(it).asJsonArray[0].asString }.toSet()
        require(entries(includes).map { it.username }.toSet() == methods) { "JMH include filters select methods outside the registered fixture matrix" }
    }

    /**
     * Resolves names independently of class initialization so invalid selections cannot run fixture code.
     */
    internal fun resolve(
        names: List<String>,
        available: Set<String>,
    ): List<String> {
        require(names.isNotEmpty() && names.all(String::isNotBlank)) { "Empty JMH fixture selection" }
        val resolved =
            names.map { name ->
                val qualified = name.replace('$', '.')
                val matches =
                    if (name in available) {
                        listOf(name)
                    } else if (qualified in available) {
                        listOf(qualified)
                    } else {
                        available.filter { it.substringAfterLast('.').substringAfterLast('$') == name }
                    }
                require(matches.size == 1) { "Unknown or ambiguous JMH fixture: $name" }
                matches.single()
            }
        require(resolved.distinct().size == resolved.size) { "Duplicate JMH fixture selection" }
        return resolved
    }

    // JMH records Java source names; resolve binary nesting without initializing fixture classes.
    private fun load(name: String): Class<*> {
        var binary = name
        while (true) {
            try {
                return Class.forName(binary, false, javaClass.classLoader)
            } catch (failure: ClassNotFoundException) {
                val separator = binary.lastIndexOf('.')
                if (separator < 0) throw failure
                binary = binary.substring(0, separator) + '$' + binary.substring(separator + 1)
            }
        }
    }

    private fun entries(includes: List<String> = emptyList()) = BenchmarkList.defaultList().find(OutputFormatFactory.createFormatInstance(System.out, VerboseMode.SILENT), includes, emptyList())
}
