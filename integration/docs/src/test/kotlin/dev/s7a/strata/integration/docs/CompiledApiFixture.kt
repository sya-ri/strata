package dev.s7a.strata.integration.docs

import java.nio.file.Files
import java.nio.file.Path

/**
 * Resolves the current API compiler output supplied by Gradle, rejecting absent or stale conventional paths.
 */
internal object CompiledApiFixture {
    /**
     * Current compiled Kotlin API directory; the test task owns its build dependency.
     */
    val classes: Path
        get() {
            val path = Path.of(checkNotNull(System.getProperty("strata.test.apiClasses")))
            check(Files.isDirectory(path)) { "The current compiled API directory is missing: $path" }
            return path
        }
}
