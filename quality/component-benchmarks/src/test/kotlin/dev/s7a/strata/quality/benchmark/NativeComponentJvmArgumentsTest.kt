package dev.s7a.strata.quality.benchmark

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import dev.s7a.strata.performance.ArtifactIdentity
import dev.s7a.strata.performance.JvmPerformanceEvidence
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertFails

/**
 * Real temporary file identities exercise cross-checkout argument admission without native or timing claims.
 */
internal class NativeComponentJvmArgumentsTest {
    @field:TempDir
    internal lateinit var directory: Path

    @Test
    internal fun equivalentExperimentPathsKeepBoundRuntimeVariationAndExternalBytes() {
        compare(report("before"), report("after"))
    }

    @Test
    internal fun substitutedFixtureInputIsRejected() {
        val report = report("fixture-substitution")
        val fixture = Path.of(URI(report.get("fixture_archive").asString))
        replaceInput(report, fixture, Files.writeString(directory.resolve("replacement-fixture.jar"), "same fixture"))
        assertFails { NativeComponentJvmArguments.read(report) }
    }

    @Test
    internal fun substitutedFabricInputIsRejected() {
        val report = report("runtime-substitution")
        val origin =
            report
                .getAsJsonObject("strata")
                .getAsJsonArray("modules")[0]
                .asJsonObject
                .getAsJsonObject("codeSource")
        replaceInput(report, Path.of(URI(origin.get("url").asString)), Files.writeString(directory.resolve("replacement-runtime.jar"), "runtime-runtime-substitution"))
        assertFails { NativeComponentJvmArguments.read(report) }
    }

    @Test
    internal fun changedFixtureBytesCannotBorrowTheirOldBinding() {
        val report = report("changed-fixture")
        Files.writeString(Path.of(URI(report.get("fixture_archive").asString)), "changed fixture")
        assertFails { NativeComponentJvmArguments.read(report) }
    }

    @Test
    internal fun changedFabricBytesCannotBorrowTheirOldBinding() {
        val report = report("changed-runtime")
        val origin =
            report
                .getAsJsonObject("strata")
                .getAsJsonArray("modules")[0]
                .asJsonObject
                .getAsJsonObject("codeSource")
        Files.writeString(Path.of(URI(origin.get("url").asString)), "changed runtime")
        assertFails { NativeComponentJvmArguments.read(report) }
    }

    @Test
    internal fun alteredExternalModBytesFailTheSharedComparison() {
        val before = report("external-before")
        val after = report("external-after")
        Files.writeString(directory.resolve("external-after/dependency.jar"), "changed dependency")
        assertFails { compare(before, after) }
    }

    @Test
    internal fun reorderedModsFailTheSharedComparison() {
        val before = report("mod-order-before")
        val after = report("mod-order-after")
        val arguments = after.getAsJsonArray("jvm_arguments")
        val mods =
            arguments[1]
                .asString
                .removePrefix("-Dfabric.addMods=")
                .split(File.pathSeparatorChar)
                .reversed()
        arguments.set(1, JsonPrimitive("-Dfabric.addMods=${mods.joinToString(File.pathSeparator)}"))
        assertFails { compare(before, after) }
    }

    @Test
    internal fun reorderedVmFlagsFailTheSharedComparison() {
        val before = report("flag-order-before")
        val after = report("flag-order-after")
        val arguments = after.getAsJsonArray("jvm_arguments")
        val first = arguments[0]
        arguments.set(0, arguments[2])
        arguments.set(2, first)
        assertFails { compare(before, after) }
    }

    @Test
    internal fun alteredHeapFlagsFailTheSharedComparison() {
        val before = report("heap-before")
        val after = report("heap-after")
        after.getAsJsonArray("jvm_arguments").add("-Xmx1g")
        assertFails { compare(before, after) }
    }

    private fun compare(
        before: JsonObject,
        after: JsonObject,
    ) {
        JvmPerformanceEvidence.verifyEqual(listOf(NativeComponentJvmArguments.read(before), NativeComponentJvmArguments.read(after)), setOf("jvm_arguments"))
    }

    private fun replaceInput(
        report: JsonObject,
        old: Path,
        replacement: Path,
    ) {
        val arguments = report.getAsJsonArray("jvm_arguments")
        arguments.set(1, JsonPrimitive(arguments[1].asString.replace(old.toString(), replacement.toString())))
    }

    private fun report(name: String): JsonObject {
        val root = Files.createDirectory(directory.resolve(name))
        val fixture = Files.writeString(root.resolve("fixture.jar"), "same fixture")
        val runtime = Files.writeString(root.resolve("runtime.jar"), "runtime-$name")
        val dependency = Files.writeString(root.resolve("dependency.jar"), "same dependency")
        return JsonObject().apply {
            addProperty("fixture_archive", fixture.toUri().toString())
            addProperty("fixture_archive_sha256", ArtifactIdentity.file(fixture))
            add(
                "strata",
                JsonObject().apply {
                    add(
                        "modules",
                        JsonArray().apply {
                            add(
                                JsonObject().apply {
                                    addProperty("module", "fabric")
                                    add(
                                        "codeSource",
                                        JsonObject().apply {
                                            addProperty("url", runtime.toUri().toString())
                                            addProperty("sha256", ArtifactIdentity.file(runtime))
                                        },
                                    )
                                },
                            )
                        },
                    )
                },
            )
            add(
                "jvm_arguments",
                JsonArray().apply {
                    add("-Dtest.first=1")
                    add("-Dfabric.addMods=${listOf(fixture, dependency, runtime).joinToString(File.pathSeparator)}")
                    add("-Dtest.second=2")
                    add("-Dstrata.minecraftParityOutput=${root.resolve("parity")}")
                },
            )
        }
    }
}
