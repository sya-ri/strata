package dev.s7a.strata.integration.docs

import com.google.gson.JsonParser
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Runs compiled demo factories in Chromium and writes their detached initial documents to build staging.
 * The Gradle owner clears the destination first; failed generation never produces a deployable artifact.
 */
internal object WebDemoGenerator {
    /**
     * Accepts the production distribution, empty staging directory, and documentation source revision.
     */
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 3) { "Web demo generation requires distribution, output, and revision." }
        val distribution = Path.of(args[0]).toAbsolutePath().normalize()
        val output = Path.of(args[1]).toAbsolutePath().normalize()
        ShowcasePaths.requireDirectory(distribution, "web distribution")
        Files.createDirectories(output)
        Files.walk(distribution).use { files ->
            files.filter(Files::isRegularFile).forEach { file ->
                ShowcasePaths.requireRegularFile(file, "web distribution file")
                val target = output.resolve(distribution.relativize(file))
                Files.createDirectories(target.parent)
                Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING)
            }
        }
        Playwright.create().use { playwright ->
            playwright.chromium().launch().use { browser ->
                browser.newPage().use { page ->
                    val failures = ArrayList<String>()
                    page.onPageError { value -> failures.add(value) }
                    page.navigate("about:blank")
                    page.addScriptTag(Page.AddScriptTagOptions().setPath(distribution.resolve("app.js")))
                    val json = page.evaluate("revision => window.strataDemoDocuments(revision)", args[2]) as String
                    val documents = JsonParser.parseString(json).asJsonArray
                    val paths =
                        documents.map { value ->
                            val record = value.asJsonObject
                            val relative = record.get("path").asString
                            val target = output.resolve(relative).normalize()
                            require(target.startsWith(output) && target != output) { "Demo document escapes staging: $relative" }
                            ShowcasePaths.requireSafeSegments(target, "web demo document")
                            Files.createDirectories(target.parent)
                            Files.writeString(target, record.get("html").asString, StandardCharsets.UTF_8)
                            relative
                        }
                    require(paths.isNotEmpty() && paths.distinct().size == paths.size) { "Demo catalog is empty or has duplicate paths." }
                    check(failures.isEmpty()) { "Web demo generation failed: $failures" }
                    Files.writeString(output.resolve("pages.txt"), paths.joinToString("\n", postfix = "\n"), StandardCharsets.UTF_8)
                }
            }
        }
    }
}
