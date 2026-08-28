package dev.s7a.strata.gradle.fabric

import java.io.IOException
import java.util.jar.JarFile
import java.util.jar.Manifest
import java.util.zip.ZipFile

/**
 * Verifies the declared Fabric toolchain identity of a built archive without rewriting it.
 * Expected values are immutable and unrelated manifest attributes remain valid.
 * Instances retain no archive or stream and can be shared between threads when each caller keeps its archive open and stable during verification.
 *
 * @param loaderVersion exact expected Fabric Loader version in the manifest main attributes.
 * @param mixinVersion exact expected Fabric Mixin version in the manifest main attributes.
 * @param mixinGroup exact expected Fabric Mixin group in the manifest main attributes.
 * @throws IllegalArgumentException when an expected identity is blank.
 */
public class FabricToolchainManifest(
    loaderVersion: String,
    mixinVersion: String,
    mixinGroup: String,
) {
    private val expectedAttributes: Map<String, String> =
        mapOf(
            "Fabric-Loader-Version" to loaderVersion,
            "Fabric-Mixin-Version" to mixinVersion,
            "Fabric-Mixin-Group" to mixinGroup,
        )

    init {
        require(expectedAttributes.values.all { it.isNotBlank() }) { "Expected Fabric toolchain identities must not be blank." }
    }

    /**
     * Requires exactly one manifest entry and exact expected toolchain identities in its main attributes.
     * Named sections cannot supply the identities; unrelated main attributes and sections are ignored.
     * Verification is synchronous and read-only, closes the opened entry stream even after failure, and never closes the caller-owned archive.
     *
     * @param archive open, stable archive borrowed only for this invocation.
     * @throws IllegalStateException when the archive is closed, its manifest is absent or duplicated, or any expected main attribute is missing or different.
     * @throws IOException when reading or parsing the manifest fails.
     */
    public fun verify(archive: ZipFile) {
        val entry =
            checkNotNull(
                archive
                    .entries()
                    .asSequence()
                    .filter { it.name == JarFile.MANIFEST_NAME }
                    .singleOrNull(),
            ) {
                "Expected exactly one ${JarFile.MANIFEST_NAME} entry."
            }
        val manifest = archive.getInputStream(entry).use { input -> Manifest(input) }
        expectedAttributes.forEach { (name, expected) ->
            val actual = manifest.mainAttributes.getValue(name)
            check(actual == expected) { "Expected $name=$expected in the manifest main attributes, but found $actual." }
        }
    }
}
