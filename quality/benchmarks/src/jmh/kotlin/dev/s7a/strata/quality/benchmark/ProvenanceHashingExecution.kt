package dev.s7a.strata.quality.benchmark

import com.google.gson.JsonArray
import com.google.gson.JsonParser
import dev.s7a.strata.performance.ArtifactIdentity
import dev.s7a.strata.performance.JvmPerformanceInputs
import dev.s7a.strata.performance.PerformanceJson
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path

/**
 * Delegates the original provenance CLI through the shared bounded JavaExec request.
 * The prepared fixture and outer collector stay independent from both measured library archives.
 * Archive preparation and corpus construction finish before the original collection intervals.
 */
internal object ProvenanceHashingExecution {
    /**
     * Retains the original six/nine arguments and accepts the existing controller's two/eight arguments.
     * Immutable fixture inputs bind every selected archive and the original corpus/control manifests.
     */
    @JvmStatic
    public fun main(args: Array<String>) {
        when (args.size) {
            2 -> collectOwner(args)
            8 -> compareOwner(args)
            else -> execute(args, JvmPerformanceInputs.read(inputManifest()))
        }
    }

    /**
     * Prepares the existing complete corpus once before any original collection interval.
     * Each complete raw report is retained before the owner envelope is written.
     */
    private fun collectOwner(args: Array<String>) {
        val inputs = ownerInputs()
        val libraries = inputs.getValue(Input.Controls.label)
        val corpus = checkNotNull(inputs.getValue(Input.Corpus.label).parent)
        if (Files.exists(corpus).not()) {
            require(args[1].toInt() == 0)
            ProvenanceHashingCorpus.main(arrayOf(corpus.toString()))
        }
        val original = Path.of(args[0]).resolveSibling("${Path.of(args[0]).fileName}.original.json")
        val declared = JvmPerformanceInputs.read(inputManifest())
        val target = declared.getValue("owner-target-identity")
        execute(arrayOf("collect", target.toString(), libraries.toString(), inputs.getValue(Input.Corpus.label).toString(), original.toString(), args[1]), inputs)
        val report = Files.newBufferedReader(original).use { JsonParser.parseReader(it).asJsonObject }
        val actual = report.getAsJsonObject("target_before")
        val module =
            actual
                .getAsJsonArray("modules")
                .map { it.asJsonObject }
                .single { it.get("representativeClass").asString.contentEquals(ArtifactIdentity::class.java.name) }
        val runtime = actual.deepCopy().apply { add("modules", JsonArray().apply { add(module.deepCopy()) }) }
        report.add("runtime_metadata", runtime)
        report.addProperty("original_report_path", original.toString())
        report.addProperty("original_report_sha256", ArtifactIdentity.file(original))
        PerformanceJson.writeNew(Path.of(args[0]), report)
    }

    /**
     * Delegates the existing three-plus-three comparison with its original argument order.
     */
    private fun compareOwner(args: Array<String>) {
        val inputs = ownerInputs()
        require(Files.isSameFile(Path.of(args[6]), inputs.getValue(Input.Collector.label)))
        execute(arrayOf("compare", args[6], args[7], args[0], args[1], args[2], args[3], args[4], args[5]), inputs)
    }

    /**
     * Resolves actual archives from the generic role manifest without replacing the outer testkit.
     */
    private fun ownerInputs(): Map<String, Path> {
        val declared = JvmPerformanceInputs.read(inputManifest())
        val expected = setOf("owner-fixture", "owner-collector", "owner-baseline-identity", "owner-candidate-identity", "owner-target-identity")
        require(declared.keys.filter { it.startsWith("owner-") }.toSet() == expected)
        val libraries = Path.of(checkNotNull(System.getProperty("strata.performance.inputs")))
        return mapOf(
            Input.Fixture.label to declared.getValue("owner-fixture"),
            Input.Collector.label to declared.getValue("owner-collector"),
            Input.Controls.label to libraries,
            Input.Corpus.label to libraries.resolveSibling("${libraries.fileName}.corpus").resolve("manifest.json"),
            Input.Baseline.label to declared.getValue("owner-baseline-identity"),
            Input.Candidate.label to declared.getValue("owner-candidate-identity"),
        )
    }

    /**
     * Retains strict archive/hash/origin admission for the original six/nine-argument CLI.
     */
    private fun execute(
        args: Array<String>,
        inputs: Map<String, Path>,
    ) {
        require(args.isNotEmpty())
        val command = Command.entries.single { it.name.equals(args.first(), ignoreCase = true) }
        require(args.size == if (command == Command.Collect) 6 else 9)
        val inputManifest = inputManifest()
        require(inputs.keys == Input.entries.map { it.label }.toSet())
        val before = inputs.mapValues { (_, path) -> ArtifactIdentity.file(path) }
        val declaredBefore = JvmPerformanceInputs.read(inputManifest).mapValues { (_, path) -> ArtifactIdentity.file(path) }
        val manifestHash = ArtifactIdentity.file(inputManifest)
        val fixture = inputs.getValue(Input.Fixture.label)
        val collector = inputs.getValue(Input.Collector.label)
        require(Files.isSameFile(fixture, collector).not())
        require(Files.isSameFile(origin(PerformanceJson::class.java), collector))
        when (command) {
            Command.Collect -> {
                val target = Path.of(args[1])
                require(listOf(Input.Baseline, Input.Candidate).count { Files.isSameFile(target, inputs.getValue(it.label)) } == 1)
                require(Files.isSameFile(target, collector).not() && Files.isSameFile(target, fixture).not())
                require(Files.isSameFile(Path.of(args[2]), inputs.getValue(Input.Controls.label)))
                require(Files.isSameFile(Path.of(args[3]), inputs.getValue(Input.Corpus.label)))
            }

            Command.Compare -> {
                require(Files.isSameFile(Path.of(args[1]), collector))
            }
        }
        val controls = JvmPerformanceInputs.read(inputs.getValue(Input.Controls.label)).values
        require(controls.none { control -> listOf(Input.Fixture, Input.Collector, Input.Baseline, Input.Candidate).any { Files.isSameFile(control, inputs.getValue(it.label)) } })
        val archives = (listOf(fixture, collector) + controls).map { it.toAbsolutePath().normalize() }
        require(archives.distinct().size == archives.size)
        val controlHashes = controls.associateWith(ArtifactIdentity::file)
        URLClassLoader("provenance-evidence", archives.map { it.toUri().toURL() }.toTypedArray(), ClassLoader.getPlatformClassLoader()).use { loader ->
            val delegate = Class.forName(ProvenanceHashingEvidence::class.java.name, false, loader)
            val kit = Class.forName(PerformanceJson::class.java.name, false, loader)
            require(delegate.classLoader === loader && Files.isSameFile(origin(delegate), fixture))
            require(kit.classLoader === loader && Files.isSameFile(origin(kit), collector))
            try {
                delegate.getMethod("main", Array<String>::class.java).invoke(null, args as Any)
            } catch (failure: InvocationTargetException) {
                throw failure.cause ?: failure
            }
        }
        require(ArtifactIdentity.file(inputManifest) == manifestHash)
        require(inputs.mapValues { (_, path) -> ArtifactIdentity.file(path) } == before)
        require(JvmPerformanceInputs.read(inputManifest).mapValues { (_, path) -> ArtifactIdentity.file(path) } == declaredBefore)
        require(controls.associateWith(ArtifactIdentity::file) == controlHashes)
    }

    /**
     * Original external command decoded once at the CLI boundary.
     */
    private enum class Command {
        Collect,
        Compare,
    }

    /**
     * Explicit immutable input roles; measured archives are never outer-classpath entries.
     */
    private enum class Input(
        val label: String,
    ) {
        Fixture("provenance-fixture"),
        Collector("outer-collector"),
        Controls("control-libraries"),
        Corpus("provenance-corpus"),
        Baseline("baseline-library"),
        Candidate("candidate-library"),
    }

    private fun origin(type: Class<*>): Path = Path.of(checkNotNull(type.protectionDomain.codeSource).location.toURI())

    private fun inputManifest(): Path = Path.of(checkNotNull(System.getProperty("strata.performance.fixtureInputs")))
}
