package dev.s7a.strata.integration.docs

import java.nio.file.Files
import java.nio.file.Path

/**
 * Creates explicit read-input and output paths inside caller-owned temporary test directories.
 */
internal object ShowcaseLaunchFixture {
    /**
     * Builds valid path-only launcher arguments without a loaded-game directory or actual rendering assets.
     *
     * @param root temporary repository root owned by the test.
     * @param kind typed staging directory selection.
     * @param inputs temporary read-input directory, optionally outside [root].
     * @return the exact ten-argument minimum launcher shape with existing regular input files.
     */
    internal fun arguments(
        root: Path,
        kind: ShowcaseStagingKind,
        inputs: Path = root.resolve("inputs"),
    ): Array<String> {
        val build = root.resolve("integration/docs/build")
        val staging = build.resolve("component-showcase").resolve(kind.directoryName)
        val classes = root.resolve("api/classes")
        Files.createDirectories(staging)
        Files.createDirectories(classes)
        Files.createDirectories(inputs.resolve("objects"))
        listOf("client.jar", "asset-index.json", "version.json", "inventory.png", "inventory.properties").forEach { name ->
            Files.writeString(inputs.resolve(name), name)
        }
        return arrayOf(
            root.toString(),
            build.toString(),
            staging.toString(),
            inputs.resolve("client.jar").toString(),
            inputs.resolve("asset-index.json").toString(),
            inputs.resolve("objects").toString(),
            inputs.resolve("version.json").toString(),
            inputs.resolve("inventory.png").toString(),
            inputs.resolve("inventory.properties").toString(),
            classes.toString(),
        )
    }
}
