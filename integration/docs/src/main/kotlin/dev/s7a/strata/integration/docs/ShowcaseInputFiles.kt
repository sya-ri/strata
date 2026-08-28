package dev.s7a.strata.integration.docs

import java.nio.file.Path

/**
 * Immutable explicit read-only game assets and independently captured inventory evidence.
 *
 * Inputs may be outside the repository and are validated without opening or modifying them.
 * The caller supplies normalized absolute paths and retains ownership of every file.
 *
 * @property clientJar official client archive used for resource lookup.
 * @property assetIndex official index identifying external resource objects.
 * @property assetObjects directory containing the indexed objects.
 * @property versionManifest official version metadata identifying the client and asset index.
 * @property nativeInventoryPng previously captured loaded-server inventory frame.
 * @property nativeInventoryReceipt explicit source and image hashes for that inventory frame.
 * @throws IllegalArgumentException when an input is absent, symbolic, reparse-backed, or has the wrong file type.
 */
internal class ShowcaseInputFiles(
    internal val clientJar: Path,
    internal val assetIndex: Path,
    internal val assetObjects: Path,
    internal val versionManifest: Path,
    internal val nativeInventoryPng: Path,
    internal val nativeInventoryReceipt: Path,
) {
    init {
        mapOf(
            clientJar to "Minecraft client archive",
            assetIndex to "Minecraft asset index",
            versionManifest to "Minecraft version manifest",
            nativeInventoryPng to "native inventory image",
            nativeInventoryReceipt to "native inventory receipt",
        ).forEach { (path, label) -> ShowcasePaths.requireRegularFile(path, label) }
        ShowcasePaths.requireDirectory(assetObjects, "Minecraft asset objects")
    }

    /**
     * Prevents generation from replacing or deleting one of its declared inputs.
     *
     * @param destinations exact files or directory roots owned by generation.
     * @throws IllegalArgumentException when an input is a destination or lies below one.
     */
    internal fun requireOutside(destinations: List<Path>) {
        val inputs = listOf(clientJar, assetIndex, assetObjects, versionManifest, nativeInventoryPng, nativeInventoryReceipt)
        require(inputs.none { input -> destinations.any { destination -> input.startsWith(destination) } }) {
            "Read-only showcase inputs must be outside generated output destinations."
        }
    }
}
