package dev.s7a.strata.integration.docs

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * Discovers the complete demo subtree while checking its generated page manifest for missing or stale HTML.
 * Older sites without demos keep their original inventory contract.
 */
internal object WebDemoInventory {
    /**
     * Returns sorted site-relative demo documents and assets, rejecting missing files and symbolic paths.
     */
    fun paths(site: Path): List<String> {
        val demos = site.resolve("demos")
        if (Files.exists(demos, LinkOption.NOFOLLOW_LINKS).not()) return emptyList()
        ShowcasePaths.requireDirectory(demos, "web demos")
        val manifest = demos.resolve("pages.txt")
        ShowcasePaths.requireRegularFile(manifest, "web demo page manifest")
        val declared =
            Files.readAllLines(manifest, StandardCharsets.UTF_8).map { relative ->
                require(relative.isNotBlank()) { "Web demo manifest has an empty page path." }
                val file = demos.resolve(relative).normalize()
                require(file.startsWith(demos) && file.fileName.toString().endsWith(".html")) { "Invalid web demo page: $relative" }
                ShowcasePaths.requireRegularFile(file, "declared web demo page")
                file
            }
        require(declared.distinct().size == declared.size && demos.resolve("index.html") in declared) {
            "Web demo manifest must contain its index and unique pages."
        }
        val files =
            Files.walk(demos).use { stream ->
                stream.filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS).not() }.toList()
            }
        files.forEach { ShowcasePaths.requireRegularFile(it, "web demo artifact") }
        require(files.filter { it.fileName.toString().endsWith(".html") }.toSet() == declared.toSet()) {
            "Web demo staging contains stale or unlisted HTML."
        }
        return files.map { "/${site.relativize(it).toString().replace('\\', '/')}" }.sorted()
    }
}
