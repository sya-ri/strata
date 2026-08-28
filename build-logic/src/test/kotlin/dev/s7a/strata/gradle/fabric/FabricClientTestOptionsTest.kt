package dev.s7a.strata.gradle.fabric

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * Exercise test-directory ownership and deterministic option preparation without starting Gradle or Minecraft.
 */
internal class FabricClientTestOptionsTest {
    @TempDir
    lateinit var directory: Path

    private val expected = "onboardAccessibility:false\nnarrator:0\nsoundCategory_master:0.0\n"

    @Test
    fun `fresh development production and published test directories receive silent options`() {
        val build = Files.createDirectory(directory.resolve("build"))
        for (name in listOf("clientGameTest", "productionClientGameTest", "publishedCoordinateClientGameTest")) {
            val run = build.resolve("run/$name")
            FabricClientTestOptions.prepare(build.toFile(), run.toFile())
            assertEquals(expected, Files.readString(run.resolve("options.txt")))
            assertOnlyOptionsRemain(run)
        }
    }

    @Test
    fun `unrelated Unicode options retain order while controlled duplicates become canonical`() {
        val build = Files.createDirectory(directory.resolve("build"))
        val run = Files.createDirectory(build.resolve("run"))
        val options = run.resolve("options.txt")
        Files.writeString(
            options,
            "\uFEFFlang:ja_jp\r\nonboardAccessibility:true\r\nnarrator:2\r\nresourcePacks:[\"日本語 한글 😀\"]\r\n" +
                "soundCategory_master:1.0\r\n# retained:comment\r\nnarrator:3\r\nonboardAccessibility:true\r\n" +
                "soundCategory_music:0.5\r\nsoundCategory_master:0.2\r\n",
            StandardCharsets.UTF_8,
        )
        FabricClientTestOptions.prepare(build.toFile(), run.toFile())
        assertEquals(
            "lang:ja_jp\nresourcePacks:[\"日本語 한글 😀\"]\n# retained:comment\nsoundCategory_music:0.5\n$expected",
            Files.readString(options, StandardCharsets.UTF_8),
        )
        val first = Files.readAllBytes(options)
        FabricClientTestOptions.prepare(build.toFile(), run.toFile())
        assertArrayEquals(first, Files.readAllBytes(options))
        assertOnlyOptionsRemain(run)
    }

    @Test
    fun `preparation recreates options after the owned cleanup removes the run directory`() {
        val build = Files.createDirectory(directory.resolve("build"))
        val run = build.resolve("run/clientGameTest")
        FabricClientTestOptions.prepare(build.toFile(), run.toFile())
        Files.delete(run.resolve("options.txt"))
        Files.delete(run)
        FabricClientTestOptions.prepare(build.toFile(), run.toFile())
        assertEquals(expected, Files.readString(run.resolve("options.txt")))
    }

    @Test
    fun `ordinary personal sibling and normalized escaping directories are rejected without writes`() {
        val build = Files.createDirectory(directory.resolve("build"))
        val personal = Files.createDirectory(directory.resolve("personal"))
        val options = personal.resolve("options.txt")
        Files.writeString(options, "narrator:2\nsoundCategory_master:0.8\n")
        val before = Files.readAllBytes(options)
        for (run in listOf(directory.resolve("run"), personal, directory.resolve("build-other/run"), build.resolve("../personal"), build)) {
            assertThrows(IllegalArgumentException::class.java) {
                FabricClientTestOptions.prepare(build.toFile(), run.toFile())
            }
        }
        assertArrayEquals(before, Files.readAllBytes(options))
        assertFalse(Files.exists(directory.resolve("run")))
        assertFalse(Files.exists(directory.resolve("build-other")))
    }

    @Test
    fun `a missing build directory is rejected rather than creating an arbitrary ownership boundary`() {
        val build = directory.resolve("missing-build")
        assertThrows(IllegalArgumentException::class.java) {
            FabricClientTestOptions.prepare(build.toFile(), build.resolve("run").toFile())
        }
        assertFalse(Files.exists(build))
    }

    @Test
    fun `an options directory is rejected without replacing its contents`() {
        val build = Files.createDirectory(directory.resolve("build"))
        val run = Files.createDirectory(build.resolve("run"))
        val options = Files.createDirectory(run.resolve("options.txt"))
        Files.writeString(options.resolve("keep.txt"), "retained")
        assertThrows(IllegalArgumentException::class.java) {
            FabricClientTestOptions.prepare(build.toFile(), run.toFile())
        }
        assertEquals("retained", Files.readString(options.resolve("keep.txt")))
    }

    @Test
    fun `a symbolic options file cannot alter a personal target`() {
        val build = Files.createDirectory(directory.resolve("build"))
        val run = Files.createDirectory(build.resolve("run"))
        val target = directory.resolve("personal-options.txt")
        Files.writeString(target, "narrator:2\n")
        if (createSymbolicLinkIfAvailable(run.resolve("options.txt"), target)) {
            assertThrows(IllegalArgumentException::class.java) {
                FabricClientTestOptions.prepare(build.toFile(), run.toFile())
            }
        }
        assertEquals("narrator:2\n", Files.readString(target))
    }

    @Test
    fun `symbolic run ancestors cannot create or change files outside the build directory`() {
        val build = Files.createDirectory(directory.resolve("build"))
        val personal = Files.createDirectory(directory.resolve("personal"))
        Files.writeString(personal.resolve("keep.txt"), "retained")
        val link = build.resolve("linked")
        if (createSymbolicLinkIfAvailable(link, personal)) {
            assertThrows(IllegalArgumentException::class.java) {
                FabricClientTestOptions.prepare(build.toFile(), link.resolve("clientGameTest").toFile())
            }
        }
        assertFalse(Files.exists(personal.resolve("clientGameTest")))
        assertEquals("retained", Files.readString(personal.resolve("keep.txt")))
    }

    @Test
    fun `a symbolic build directory cannot redefine the ownership boundary`() {
        val personal = Files.createDirectory(directory.resolve("personal"))
        Files.writeString(personal.resolve("keep.txt"), "retained")
        val build = directory.resolve("build")
        if (createSymbolicLinkIfAvailable(build, personal)) {
            assertThrows(IllegalArgumentException::class.java) {
                FabricClientTestOptions.prepare(build.toFile(), build.resolve("clientGameTest").toFile())
            }
        }
        assertFalse(Files.exists(personal.resolve("clientGameTest")))
        assertEquals("retained", Files.readString(personal.resolve("keep.txt")))
    }

    /**
     * Exercise real symbolic links where available without skipping the enclosing target-preservation assertions.
     * Report unsupported filesystem or privilege failures explicitly; any other failure still fails the test.
     */
    private fun createSymbolicLinkIfAvailable(
        link: Path,
        target: Path,
    ): Boolean {
        val failure =
            try {
                Files.createSymbolicLink(link, target)
                return true
            } catch (failure: FileSystemException) {
                failure
            } catch (failure: UnsupportedOperationException) {
                failure
            }
        assertFalse(Files.exists(link, LinkOption.NOFOLLOW_LINKS))
        println("Symbolic-link fixture ${link.fileName} unavailable (${failure.javaClass.simpleName}); helper link-rejection coverage was not exercised.")
        return false
    }

    /**
     * Confirm temporary replacement files are released after a successful preparation.
     */
    private fun assertOnlyOptionsRemain(run: Path) {
        Files.list(run).use { paths ->
            assertEquals(listOf("options.txt"), paths.map { it.fileName.toString() }.toList())
        }
    }
}
