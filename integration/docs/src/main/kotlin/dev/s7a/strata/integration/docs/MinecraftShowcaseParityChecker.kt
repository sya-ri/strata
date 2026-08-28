package dev.s7a.strata.integration.docs

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Compares a fresh no-game showcase with independently produced Fabric/native acceptance evidence.
 * This launcher never renders or loads Minecraft classes; Gradle owns the separate loaded-game dependency.
 * Check mode is read-only. Generate mode updates only explicitly native evidence, never primitive showcase images.
 */
internal object MinecraftShowcaseParityChecker {
    /**
     * Checks generated headless frames or records new native evidence after the loaded acceptance task succeeds.
     *
     * @param args repository root, fresh native evidence root, headless staging root, and `check` or `generate`.
     * @throws IllegalArgumentException when paths, receipts, source identity, or pixels differ.
     * @throws java.io.IOException when evidence cannot be read or recorded.
     */
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 4) { "Native showcase verification requires repository, native evidence, headless staging, and mode." }
        val project = Path.of(args[0]).toAbsolutePath().normalize()
        val nativeRoot = Path.of(args[1]).toAbsolutePath().normalize()
        val staging = Path.of(args[2]).toAbsolutePath().normalize()
        val mode = Mode.parse(args[3])
        ShowcasePaths.requireDirectory(project, "repository root")
        ShowcasePaths.requireSafeSegments(project, "repository root")
        require(nativeRoot == project.resolve("integration/minecraft-fabric-26.2/build/minecraft-parity")) {
            "Native showcase evidence must be the independent Minecraft 26.2 acceptance output."
        }
        val evidence = ShowcaseParityEvidence.load(nativeRoot)
        val inventorySource = inventorySource(project)
        val inventory = evidence.screenPng(DocumentedScreen.SynchronizedInventory)
        val nativeOutputs =
            linkedMapOf(
                "minecraft-26.2-parity.properties" to evidence.receipt(),
                "minecraft-26.2-inventory.png" to inventory,
                "minecraft-26.2-inventory.properties" to inventoryReceipt(inventory, inventorySource),
            )
        val evidenceRoot = project.resolve("docs/evidence")
        ShowcasePaths.requireSafeSegments(evidenceRoot, "native documentation evidence")
        when (mode) {
            Mode.Check -> {
                require(staging == project.resolve("integration/docs/build/component-showcase/check")) {
                    "Native comparison requires the independently regenerated headless check output."
                }
                verifyFrames(staging.resolve("components"), evidence)
                nativeOutputs.forEach { (name, expected) -> requireBytes(evidenceRoot.resolve(name), expected) }
            }

            Mode.Generate -> {
                Files.createDirectories(evidenceRoot)
                nativeOutputs.forEach { (name, bytes) -> writeAtomically(evidenceRoot.resolve(name), bytes) }
            }
        }
    }

    /**
     * Requires exact complete-frame bytes and catalog density after both independent renderers have finished.
     * PNG encoding is deterministic in both headless paths, so byte equality also binds every ARGB value and viewport.
     */
    internal fun verifyFrames(
        components: Path,
        evidence: ShowcaseParityEvidence,
    ) {
        requireBytes(components.resolve("overview.png"), evidence.overviewPng())
        ShowcaseScenarioCatalog.components.forEach { scenario ->
            ShowcasePipeline.verifyComponentViewport(scenario, evidence)
            requireBytes(components.resolve("${scenario.component.slug}.png"), evidence.componentPng(scenario.component))
        }
        DocumentedScreen.entries.forEach { screen ->
            requireBytes(components.resolve("screen-${screen.slug}.png"), evidence.screenPng(screen))
        }
    }

    /**
     * Binds the one native-only inventory image to the exact compiled example without machine paths or time stamps.
     * Inputs are borrowed synchronously and the returned UTF-8 bytes own no caller array or runtime state.
     */
    internal fun inventoryReceipt(
        png: ByteArray,
        source: String,
    ): ByteArray =
        (
            "minecraft.version=26.2\n" +
                "png.sha256=${sha256(png)}\n" +
                "source.sha256=${sha256(source.replace("\r\n", "\n").replace('\r', '\n').toByteArray(StandardCharsets.UTF_8))}\n"
        ).toByteArray(StandardCharsets.UTF_8)

    private fun inventorySource(project: Path): String {
        val scenario = ShowcaseScenarioCatalog.screens.single { it.screen == DocumentedScreen.SynchronizedInventory }
        return ShowcaseSources.extract(scenario.source, project).source
    }

    private fun requireBytes(
        path: Path,
        expected: ByteArray,
    ) {
        ShowcasePaths.requireSafeSegments(path, "showcase comparison input")
        require(Files.isRegularFile(path)) { "Showcase comparison input is missing: $path" }
        require(Files.readAllBytes(path).contentEquals(expected)) { "Independent showcase evidence differs: $path" }
    }

    private fun writeAtomically(
        path: Path,
        bytes: ByteArray,
    ) {
        val temporary = Files.createTempFile(path.parent, ".strata-native-evidence-", ".tmp")
        try {
            Files.write(temporary, bytes)
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun sha256(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private enum class Mode(
        val argument: String,
    ) {
        Check("check"),
        Generate("generate"),
        ;

        companion object {
            fun parse(value: String): Mode = requireNotNull(entries.singleOrNull { it.argument == value }) { "Unknown native showcase mode." }
        }
    }
}
