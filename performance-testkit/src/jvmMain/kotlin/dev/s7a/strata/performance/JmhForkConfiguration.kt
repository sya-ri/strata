package dev.s7a.strata.performance

import com.google.gson.Gson
import com.google.gson.JsonObject
import org.openjdk.jmh.runner.BenchmarkList
import org.openjdk.jmh.runner.Runner
import org.openjdk.jmh.runner.format.OutputFormatFactory
import org.openjdk.jmh.runner.options.Options
import org.openjdk.jmh.runner.options.VerboseMode
import java.lang.management.ManagementFactory
import java.nio.file.Path

/**
 * Captures one detached JMH fork contract, including generated metadata and effective annotation/CLI JVM arguments.
 */
internal object JmhForkConfiguration {
    /**
     * Rejects unsupported fork arguments before output acquisition and captures the actual loaded parent identities.
     */
    internal fun capture(
        options: Options,
        fixtures: List<Class<*>>,
        targets: Map<String, String>,
        inputs: Map<String, Path>,
    ): JsonObject {
        require(0 < options.forkCount.orElse(1)) { "JMH evidence requires an independent fork" }
        val inherited = ManagementFactory.getRuntimeMXBean().inputArguments
        verifyArguments(options.jvmArgs.orElse(inherited) + options.jvmArgsPrepend.orElse(emptyList()) + options.jvmArgsAppend.orElse(emptyList()))
        val output = OutputFormatFactory.createFormatInstance(System.out, VerboseMode.SILENT)
        val entries = BenchmarkList.defaultList().find(output, options.includes, options.excludes).filter { entry -> fixtures.any { it.name == entry.userClassQName } }
        require(entries.isNotEmpty()) { "Missing generated JMH fixture metadata" }
        entries.forEach { entry ->
            require(0 < options.forkCount.orElse(entry.forks.orElse(1))) { "JMH evidence requires an independent fork" }
            verifyArguments(
                options.jvmArgs.orElse(entry.jvmArgs.orElse(inherited)) +
                    options.jvmArgsPrepend.orElse(entry.jvmArgsPrepend.orElse(emptyList())) +
                    options.jvmArgsAppend.orElse(entry.jvmArgsAppend.orElse(emptyList())),
            )
        }
        val loader = ClassLoader.getSystemClassLoader()
        val certified = fixtures + entries.map { Class.forName(it.generatedTarget().substringBeforeLast('.'), false, loader) }
        val runtime = LoadedArtifactMetadata.capture(loader, targets, targets.keys)
        LoadedArtifactMetadata.verifyComplete(runtime)
        val gson = Gson()
        return JsonObject().apply {
            add("representatives", gson.toJsonTree(targets))
            add("runtime", runtime)
            add("fixtures", gson.toJsonTree(ArtifactIdentity.applicationTrees(certified)))
            addProperty("harness", ArtifactIdentity.fullCodeSource(Runner::class.java))
            add("collector", PerformanceJson.collectorIdentity())
            add("inputs", gson.toJsonTree(inputs.values.associate { path -> path.toString() to ArtifactIdentity.file(path) }))
        }
    }

    private fun verifyArguments(arguments: Collection<String>) {
        val redirects = listOf("-cp", "-classpath", "--class-path", "--module-path", "-p", "--patch-module", "--upgrade-module-path", "-Xbootclasspath", "-Djava.system.class.loader", "-Djava.class.path", "-javaagent", "-agentlib", "-agentpath")
        require(arguments.none { argument -> redirects.any { flag -> argument == flag || argument.startsWith("$flag=") || argument.startsWith("$flag:") || argument.startsWith("$flag/") || argument.startsWith("$flag ") } }) {
            "JMH evidence cannot certify a redirected or instrumented fork classpath"
        }
    }
}
