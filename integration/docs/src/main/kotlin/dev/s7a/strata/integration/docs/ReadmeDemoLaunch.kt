package dev.s7a.strata.integration.docs

import java.nio.file.Files
import java.nio.file.Path

/**
 * Shared launcher for independent README generation and read-only acceptance checking.
 * All work runs synchronously on one host-owner thread; failures propagate before checked files are synchronized.
 */
internal object ReadmeDemoLaunch {
    /**
     * Consumes repository, staging, four original asset locations, and catalog font-version arguments.
     * Staging must stay below this module's ignored build directory; only [synchronize] writes checked outputs.
     */
    fun run(
        arguments: Array<String>,
        synchronize: Boolean,
    ) {
        require(arguments.size == 7) { "README demo requires repository, staging, four asset paths, and font version." }
        val root = Path.of(arguments[0]).toRealPath()
        val staging = Path.of(arguments[1]).toAbsolutePath().normalize()
        require(staging.startsWith(root.resolve("integration/docs/build/readme-demo"))) { "README demo staging must be below its build directory." }
        val inputs =
            ShowcaseMinecraftInputs(
                Path.of(arguments[2]),
                Path.of(arguments[3]),
                Path.of(arguments[4]),
                Path.of(arguments[5]),
                root.resolve("integration/minecraft-fabric-unobfuscated/src/gametest/resources"),
            )
        val output = ReadmeDemoPipeline.prepare(root, ReadmeDemoAssets(inputs), arguments[6])
        val readmePath = root.resolve("README.md")
        val readme = ReadmeDemoReadme.replace(Files.readString(readmePath))
        ReadmeDemoPipeline.write(staging, output)
        if (synchronize) {
            ReadmeDemoPipeline.write(root.resolve("docs/readme-demo"), output)
            Files.writeString(readmePath, readme)
        } else {
            ReadmeDemoPipeline.check(root, output)
        }
    }
}
