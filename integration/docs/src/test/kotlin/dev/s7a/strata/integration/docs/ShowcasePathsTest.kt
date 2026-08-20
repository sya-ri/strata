package dev.s7a.strata.integration.docs

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Verifies contained path and link/reparse rejection without touching repository files.
 */
internal class ShowcasePathsTest {
    @TempDir
    lateinit var temporaryRoot: Path

    @Test
    fun rejectsRootItselfAndEscapes() {
        val child = temporaryRoot.resolve("child")
        Files.createDirectories(child)

        assertThrows(IllegalArgumentException::class.java) {
            ShowcasePaths.contained(temporaryRoot, temporaryRoot, "root")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ShowcasePaths.contained(temporaryRoot, temporaryRoot.resolve("../outside"), "child")
        }
    }

    @Test
    fun rejectsNonDirectoryIntermediate() {
        val file = temporaryRoot.resolve("file")
        Files.writeString(file, "value")

        assertThrows(IllegalArgumentException::class.java) {
            ShowcasePaths.requireDirectory(file.resolve("nested"), "nested")
        }
    }

    @Test
    fun rejectsSymbolicAncestryWhenSupported() {
        val target = temporaryRoot.resolve("target")
        val link = temporaryRoot.resolve("link")
        Files.createDirectories(target)
        val created =
            try {
                Files.createSymbolicLink(link, target)
                true
            } catch (_: Exception) {
                false
            }
        if (created) {
            assertThrows(IllegalArgumentException::class.java) {
                ShowcasePaths.requireSafeSegments(link.resolve("child"), "linked path")
            }
        }
    }
}
