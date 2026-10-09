package dev.s7a.strata.performance

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.openjdk.jmh.runner.Runner
import org.openjdk.jmh.runner.options.CommandLineOptions
import java.net.JarURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Untimed complete generated-inventory admission for an external CPU executor plan.
 */
internal object JmhCpuAdmission {
    /**
     * Captures the complete compiled corpus and preserves its actual bytes without starting JMH.
     */
    internal fun capture(
        destination: Path,
        arguments: Array<String>,
        fixtures: List<Class<*>>,
        targets: Map<String, String>,
        workloads: Set<String>,
        inputs: Map<String, Path>,
    ) {
        val modes = workloads.map { JsonParser.parseString(it).asJsonArray[1].asString }.toSet()
        require(modes.size == 1 && modes.all { JmhEvidenceMode.decode(it) in JmhEvidenceMode.entries })
        require(JmhWorkloadInventory.capture(fixtures, modes) == workloads) { "CPU admission requires the complete compiled corpus" }
        require(System.getProperty("strata.performance.quick", "false").toBooleanStrict().not()) { "Quick collection cannot admit a CPU plan" }
        require(System.getProperty("strata.performance.smoke", "false").toBooleanStrict().not()) { "Smoke collection cannot admit a CPU plan" }
        JmhFixtureSelection.verifyWork(fixtures)
        val completeArguments = JmhFixtureSelection.includes(fixtures) + arguments.dropWhile { it.startsWith('-').not() }
        val identity = JmhForkConfiguration.capture(CommandLineOptions(*completeArguments.toTypedArray()), fixtures, targets, inputs)
        require(Files.exists(destination).not()) { "CPU admission already exists" }
        Files.createDirectories(checkNotNull(destination.toAbsolutePath().parent))
        Files.createDirectory(destination)
        val sources = sources(identity, destination)
        val inputSources = JsonObject()
        inputs.toSortedMap().entries.forEachIndexed { index, (label, path) -> inputSources.add(label, preserve(path, destination.resolve("input-$index.bin"))) }
        val runtimeSources = runtimeSources(identity, destination)
        val classes = identity.objectField("fixtures").keySet().map { Class.forName(it, false, ClassLoader.getSystemClassLoader()) }
        check(Gson().toJsonTree(ArtifactIdentity.applicationTrees(classes)) == identity.get("fixtures")) { "CPU fixture changed during admission" }
        check(LoadedArtifactMetadata.capture(ClassLoader.getSystemClassLoader(), targets, targets.keys) == identity.get("runtime")) { "CPU runtime changed during admission" }
        check(PerformanceJson.collectorIdentity() == identity.get("collector") && ArtifactIdentity.fullCodeSource(Runner::class.java) == identity.textField("harness")) { "CPU collector or harness changed during admission" }
        inputs.forEach { (label, path) -> check(ArtifactIdentity.file(path) == inputSources.objectField(label).textField("sha256")) { "CPU input changed during admission" } }
        PerformanceJson.writeNew(
            destination.resolve("admission.json"),
            JsonObject().apply {
                addProperty("contract", "strata-jmh-cpu-admission-v1")
                addProperty("status", "passed")
                add("registered_workloads", Gson().toJsonTree(workloads.sorted()))
                add("arguments", Gson().toJsonTree(arguments))
                add("identity", identity)
                add("sources", sources)
                add("inputs", inputSources)
                add("targets", runtimeSources)
            },
        )
    }

    private fun sources(
        identity: JsonObject,
        destination: Path,
    ): JsonObject {
        val sources = JsonObject()
        val preserved = mutableMapOf<Path, JsonObject>()
        val classes =
            identity
                .objectField("fixtures")
                .keySet()
                .sorted()
                .map { Class.forName(it, false, ClassLoader.getSystemClassLoader()) }
        classes.forEachIndexed { index, type ->
            val path = Path.of(checkNotNull(type.protectionDomain.codeSource).location.toURI())
            sources.add(type.name, preserved.getOrPut(path) { preserve(path, destination.resolve("fixture-$index.zip")) })
        }
        // The generated registry is a separate classpath resource, not necessarily a class code source.
        val registry = checkNotNull(ClassLoader.getSystemClassLoader().getResource("META-INF/BenchmarkList"))
        val registryRoot =
            when (LocalResourceProtocol.decode(registry.protocol)) {
                LocalResourceProtocol.File -> checkNotNull(checkNotNull(Path.of(registry.toURI()).parent).parent)
                LocalResourceProtocol.Jar -> Path.of((registry.openConnection() as JarURLConnection).jarFileURL.toURI())
                LocalResourceProtocol.Other -> error("Non-local generated JMH registry")
            }
        sources.add("generated-registry", preserve(registryRoot, destination.resolve("registry.zip")))
        val collector = JvmPerformanceMeter::class.java
        sources.add("collector", preserve(Path.of(checkNotNull(collector.protectionDomain.codeSource).location.toURI()), destination.resolve("collector.jar")))
        val harness = Runner::class.java
        sources.add("harness", preserve(Path.of(checkNotNull(harness.protectionDomain.codeSource).location.toURI()), destination.resolve("harness.jar")))
        return sources
    }

    private fun runtimeSources(
        identity: JsonObject,
        destination: Path,
    ): JsonObject {
        val runtimeSources = JsonObject()
        identity.objectField("runtime").arrayField("modules").forEachIndexed { index, value ->
            val module = value.asJsonObject
            runtimeSources.add(module.textField("module"), preserve(Path.of(URI(module.objectField("codeSource").textField("url"))), destination.resolve("target-$index.jar")))
        }
        return runtimeSources
    }

    private fun preserve(
        source: Path,
        destination: Path,
    ): JsonObject {
        require(Files.isSymbolicLink(source).not()) { "CPU plan artifacts cannot be symbolic links" }
        val directory = Files.isDirectory(source)
        val hash = if (directory) ArtifactIdentity.tree(source) else ArtifactIdentity.file(source)
        if (directory) {
            var total = 0L
            ZipOutputStream(Files.newOutputStream(destination)).use { archive ->
                Files.walk(source).use { paths ->
                    paths.filter { Files.isRegularFile(it) }.sorted().forEach { path ->
                        require(Files.isSymbolicLink(path).not())
                        total = Math.addExact(total, Files.size(path))
                        require(total <= 64L * 1024 * 1024) { "Oversized CPU fixture source" }
                        archive.putNextEntry(ZipEntry(source.relativize(path).toString().replace('\\', '/')))
                        Files.copy(path, archive)
                        archive.closeEntry()
                    }
                }
            }
            check(ArtifactIdentity.tree(source) == hash) { "CPU source changed during preservation" }
        } else {
            require(Files.size(source) <= 64L * 1024 * 1024)
            Files.copy(source, destination)
            check(ArtifactIdentity.file(destination) == hash) { "CPU source changed during preservation" }
        }
        return JsonObject().apply {
            addProperty("kind", if (directory) "tree" else "file")
            addProperty("sha256", hash)
            addProperty("archive", destination.fileName.toString())
            addProperty("archive_sha256", ArtifactIdentity.file(destination))
        }
    }
}
