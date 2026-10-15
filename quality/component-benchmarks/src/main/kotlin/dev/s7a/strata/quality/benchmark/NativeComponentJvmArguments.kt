package dev.s7a.strata.quality.benchmark

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.s7a.strata.performance.ArtifactIdentity
import java.io.File
import java.net.URI
import java.nio.file.Path
import java.util.Locale

/**
 * Compares actual native VM options while identifying the two admitted experiment mod inputs by their bound origins.
 * The caller first verifies complete native/CPU binary provenance; external mod bytes and argument order remain strict.
 */
internal object NativeComponentJvmArguments {
    private const val FABRIC_MODS_ARGUMENT = "-Dfabric.addMods="
    private const val INACTIVITY_ARGUMENT = "-Dstrata.performance.inactivity="
    private val outputArguments =
        setOf(
            "-Dstrata.performance.nativeOutput=",
            "-Dstrata.minecraftParityOutput=",
            "-Dstrata.canvas.shutdown.run=",
            "-Dstrata.canvas.shutdown.receipt=",
        )

    /**
     * Distinguishes the common fixture, measured runtime and unchanged external mod inputs.
     */
    private enum class InputKind {
        Fixture,
        Runtime,
        External,
    }

    /**
     * Retains every non-output VM argument in order, replacing only verified experiment mod paths with their roles.
     * A missing mod property preserves legacy arguments; substituted or duplicate bound inputs fail admission.
     */
    internal fun read(
        report: JsonObject,
        command: NativeEvidenceCommand = NativeEvidenceCommand.Summary,
    ): JsonObject {
        val arguments = report.getAsJsonArray("jvm_arguments").map { it.asString }
        require(arguments.count { it.startsWith(FABRIC_MODS_ARGUMENT) } <= 1) { "Duplicate native mod argument" }
        if (command == NativeEvidenceCommand.PacingComparison) {
            val policy = arguments.filter { it.startsWith(INACTIVITY_ARGUMENT) }.single()
            require(policy.removePrefix(INACTIVITY_ARGUMENT).uppercase(Locale.ROOT) == report.get("inactivity_mode").asString) { "Native policy option disagrees with its actual receipt" }
        }
        return JsonObject().apply {
            add(
                "jvm_arguments",
                JsonArray().apply {
                    arguments.forEach { argument ->
                        when {
                            outputArguments.any(argument::startsWith) -> Unit
                            command == NativeEvidenceCommand.PacingComparison && argument.startsWith(INACTIVITY_ARGUMENT) -> add(INACTIVITY_ARGUMENT)
                            argument.startsWith(FABRIC_MODS_ARGUMENT) -> add(mods(report, argument.removePrefix(FABRIC_MODS_ARGUMENT)))
                            else -> add(argument)
                        }
                    }
                },
            )
        }
    }

    private fun mods(
        report: JsonObject,
        value: String,
    ): JsonObject {
        val fixture = Path.of(URI(report.get("fixture_archive").asString)).toRealPath()
        val nativeOnly =
            report
                .getAsJsonObject("strata")
                .getAsJsonArray("modules")
                .single { it.asJsonObject.has("classTree").not() }
                .asJsonObject
        val origin = nativeOnly.getAsJsonObject("codeSource")
        val runtime = Path.of(URI(origin.get("url").asString)).toRealPath()
        require(fixture != runtime) { "Fixture and runtime mod origins overlap" }
        require(ArtifactIdentity.file(fixture) == report.get("fixture_archive_sha256").asString) { "Changed fixture mod input" }
        require(ArtifactIdentity.file(runtime) == origin.get("sha256").asString) { "Changed Fabric runtime mod input" }
        val paths = value.split(File.pathSeparatorChar).map { Path.of(it).toRealPath() }
        require(paths.count { it == fixture } == 1 && paths.count { it == runtime } == 1) { "Substituted or duplicate native experiment mod input" }
        return JsonObject().apply {
            add(
                "fabric_mods",
                JsonArray().apply {
                    paths.forEach { path ->
                        val kind =
                            when (path) {
                                fixture -> InputKind.Fixture
                                runtime -> InputKind.Runtime
                                else -> InputKind.External
                            }
                        add(
                            JsonObject().apply {
                                addProperty("kind", kind.name)
                                if (kind != InputKind.Runtime) addProperty("sha256", ArtifactIdentity.file(path))
                            },
                        )
                    }
                },
            )
        }
    }
}
