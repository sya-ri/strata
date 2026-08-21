package dev.s7a.strata.integration.docs

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Comparator

/**
 * Serializes and verifies showcase output in a contained build staging directory.
 *
 * Source documentation is never modified by checking.
 */
internal object ShowcaseStorage {
    /**
     * Writes all generated files under the result staging directory after preflight is complete.
     *
     * @param result fully rendered output with a build-contained staging root.
     * @throws IllegalArgumentException when staging or generated output violates its path or encoding contract.
     * @throws IOException when a filesystem operation fails.
     */
    internal fun writeStaging(result: ShowcaseOutput) {
        val staging = result.stagingRoot
        ShowcasePaths.requireSafeSegments(staging, "staging")
        ShowcasePaths.contained(staging, staging.resolve("components"), "component staging")
        clearDirectory(staging)
        val components = staging.resolve("components")
        Files.createDirectories(components)
        writeText(staging.resolve("components.md"), result.componentsMarkdown)
        result.sections.forEach { section ->
            writeBytes(components.resolve("${section.slug}.png"), section.png())
        }
        result.screens.forEach { screen ->
            writeBytes(components.resolve("${screen.slug}.png"), screen.png())
        }
        writeBytes(components.resolve("overview.png"), result.overview.png())
        writeBytes(components.resolve("minecraft-26.2-parity.properties"), result.receipt())
        writeText(staging.resolve("root-readme-region.md"), result.rootReadmeRegion)
    }

    /**
     * Checks generated files and the root README region without changing source files.
     *
     * @param projectRoot repository root containing checked documentation.
     * @param generated freshly rendered expected output.
     * @throws IllegalArgumentException when files are missing, stale, unexpected, unsafe, or non-regular.
     * @throws IOException when a filesystem read fails.
     */
    internal fun checkSource(
        projectRoot: Path,
        generated: ShowcaseOutput,
    ) {
        val docs = projectRoot.resolve("docs").toAbsolutePath().normalize()
        ShowcasePaths.requireSafeSegments(docs, "Showcase documentation root")
        val components = projectRoot.resolve("docs/components").toAbsolutePath().normalize()
        ShowcasePaths.requireSafeSegments(components, "Showcase component root")
        val componentsMarkdown = projectRoot.resolve("docs/components.md").toAbsolutePath().normalize()
        ShowcasePaths.requireSafeSegments(componentsMarkdown, "Showcase component Markdown")
        require(Files.exists(components).not() || Files.isDirectory(components, LinkOption.NOFOLLOW_LINKS)) {
            "Showcase component root is not a directory: $components"
        }
        val expected =
            generated.sections.map { section -> "${section.slug}.png" } +
                generated.screens.map { screen -> "${screen.slug}.png" } +
                listOf("overview.png", "minecraft-26.2-parity.properties")
        val actual =
            if (Files.exists(components)) {
                Files.walk(components).use { stream ->
                    val paths = stream.toList()
                    paths.forEach { path ->
                        ShowcasePaths.requireSafeSegments(path, "Showcase entry")
                        require(Files.isSymbolicLink(path).not()) { "Showcase entry is symbolic: $path" }
                        require(path == components || Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                            "Showcase entry is not a directory or regular file: $path"
                        }
                    }
                    paths
                        .filter { path -> path != components && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) }
                        .map { path -> components.relativize(path).toString().replace('\\', '/') }
                        .sorted()
                }
            } else {
                emptyList()
            }
        val sortedExpected = expected.sorted()
        val failures = ArrayList<String>()
        failures += fileFailures(components, sortedExpected, actual, generated)
        failures += componentsMarkdownFailures(componentsMarkdown, generated)
        failures += readmeFailures(projectRoot, generated)
        require(failures.isEmpty()) { failures.sorted().joinToString("\n") }
    }

    private fun componentsMarkdownFailures(
        componentsMarkdown: Path,
        generated: ShowcaseOutput,
    ): List<String> {
        if (Files.isRegularFile(componentsMarkdown, LinkOption.NOFOLLOW_LINKS).not()) {
            return listOf("components.md: missing or not regular")
        }
        val staged = generated.stagingRoot.resolve("components.md")
        return if (Files.readAllBytes(componentsMarkdown).contentEquals(Files.readAllBytes(staged))) {
            emptyList()
        } else {
            listOf("components.md: different")
        }
    }

    private fun fileFailures(
        components: Path,
        expected: List<String>,
        actual: List<String>,
        generated: ShowcaseOutput,
    ): List<String> {
        val failures = ArrayList<String>()
        expected.filter { relative -> relative !in actual }.forEach { relative -> failures += "missing: $relative" }
        actual.filter { relative -> relative !in expected }.forEach { relative -> failures += "unexpected: $relative" }
        expected.forEach { relative ->
            val source = components.resolve(relative)
            if (Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                val staged = generated.stagingRoot.resolve("components/$relative")
                if (Files.readAllBytes(source).contentEquals(Files.readAllBytes(staged)).not()) failures += "different: $relative"
            }
        }
        return failures
    }

    private fun readmeFailures(
        projectRoot: Path,
        generated: ShowcaseOutput,
    ): List<String> {
        val readme = projectRoot.resolve("README.md").toAbsolutePath().normalize()
        return try {
            ShowcasePaths.requireSafeSegments(readme, "README")
            if (Files.isRegularFile(readme, LinkOption.NOFOLLOW_LINKS).not()) return listOf("README: missing or not regular")
            val source = Files.readAllBytes(readme)
            val expectedRegion = "\n${generated.rootReadmeRegion}".toByteArray(StandardCharsets.UTF_8)
            if (ShowcaseReadme.interior(source).contentEquals(expectedRegion)) emptyList() else listOf("README: different showcase region")
        } catch (error: IllegalArgumentException) {
            listOf("README: ${error.message}")
        }
    }

    private fun clearDirectory(directory: Path) {
        ShowcasePaths.requireSafeSegments(directory, "Staging path")
        require(Files.isSymbolicLink(directory).not()) { "Staging path must not be symbolic: $directory" }
        if (Files.exists(directory)) {
            require(Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) { "Staging path is not a directory: $directory" }
            Files.walk(directory).use { stream ->
                val paths = stream.toList()
                paths.forEach { path ->
                    ShowcasePaths.requireSafeSegments(path, "Staging path")
                    require(Files.isSymbolicLink(path).not()) { "Staging path contains a symbolic entry: $path" }
                    require(path == directory || Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                        "Staging path contains a non-regular entry: $path"
                    }
                }
                paths.sortedWith(Comparator.reverseOrder<Path>()).forEach { path ->
                    if (path != directory) {
                        Files.deleteIfExists(path)
                    }
                }
            }
        }
        Files.createDirectories(directory)
    }

    private fun writeText(
        path: Path,
        value: String,
    ) {
        val normalized = value.replace("\r\n", "\n").replace('\r', '\n').trimEnd('\n') + "\n"
        ShowcasePaths.requireSafeSegments(path, "Generated text")
        require(normalized.startsWith('\uFEFF'.toString()).not()) { "Generated Markdown contains a BOM: $path" }
        Files.createDirectories(path.parent)
        Files.writeString(path, normalized, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
    }

    private fun writeBytes(
        path: Path,
        value: ByteArray,
    ) {
        ShowcasePaths.requireSafeSegments(path, "Generated bytes")
        Files.createDirectories(path.parent)
        Files.write(path, value, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
    }
}
