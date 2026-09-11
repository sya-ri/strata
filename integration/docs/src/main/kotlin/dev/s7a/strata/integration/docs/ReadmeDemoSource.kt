package dev.s7a.strata.integration.docs

import java.nio.file.Files
import java.nio.file.Path

/**
 * Immutable source excerpt extracted from the file compiled for one storyboard factory.
 *
 * @property full normalized, complete Kotlin source for the downloadable example.
 * @property lines exact visible source lines, with only common indentation removed.
 */
internal data class ReadmeDemoSource(
    val full: String,
    val lines: List<String>,
) {
    /**
     * Loads one known example below the repository root without modifying any file.
     * Missing, duplicate, reversed, or empty source markers fail before rendering.
     */
    companion object {
        /**
         * Repository-relative directory compiled by both the API-only and generator source sets.
         */
        const val DIRECTORY: String = "integration/docs/src/readmeExamples/kotlin/dev/s7a/strata/integration/docs/example"

        /**
         * Reads the source paired with [stage]; the caller keeps the repository stable during generation.
         */
        fun read(
            root: Path,
            stage: ReadmeDemoStage,
        ): ReadmeDemoSource {
            val full = readText(root.resolve("$DIRECTORY/${stage.sourceName}.kt"))
            val start = "// readme-demo:start"
            val end = "// readme-demo:end"
            require(full.split(start).size == 2 && full.split(end).size == 2) { "README demo source markers must be unique." }
            require(full.indexOf(start) < full.indexOf(end)) { "README demo source markers are reversed." }
            val body = full.substringAfter(start).substringBefore(end).trimIndent()
            require(body.isNotBlank()) { "README demo source is empty." }
            return ReadmeDemoSource(full, body.lines())
        }

        /**
         * Reads caller-owned UTF-8 source with CRLF and CR normalized to LF for excerpts and receipt hashes.
         * Preserves every other character, never writes the file, and propagates read failures on the calling thread.
         */
        fun readText(path: Path): String = Files.readString(path).replace("\r\n", "\n").replace('\r', '\n')
    }
}
