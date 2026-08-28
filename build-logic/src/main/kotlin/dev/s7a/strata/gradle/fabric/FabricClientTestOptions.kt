package dev.s7a.strata.gradle.fabric

import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Prepare silent Minecraft client-test options without changing ordinary launch or personal settings.
 * This stateless helper retains no path or stream and supports concurrent calls for independent run directories.
 * The caller owns each test directory exclusively during preparation and must invoke this helper after run-directory cleanup.
 */
public object FabricClientTestOptions {
    private val values: Map<String, String> =
        linkedMapOf(
            "onboardAccessibility" to "false",
            "narrator" to "0",
            "soundCategory_master" to "0.0",
        )

    /**
     * Ensure the owned run directory exists and write exactly one canonical value for each controlled option.
     * Preserve unrelated lines and their order, normalize line endings to LF, and emit deterministic UTF-8 without a byte-order mark.
     * Require a strict descendant of the existing project build directory; symbolic directories and symbolic or non-regular options files are rejected before writing.
     * The caller must keep the directory tree stable throughout this synchronous operation.
     *
     * @param buildDirectory existing directory from the owning project's build layout, never a user configuration directory.
     * @param runDirectory test-owned directory below that build directory, borrowed only for this call.
     * @throws IllegalArgumentException when the supplied paths are outside the owned boundary or contain an unsafe filesystem entry.
     * @throws IOException when creating, reading, or replacing the test options fails.
     */
    public fun prepare(
        buildDirectory: File,
        runDirectory: File,
    ) {
        val run = ownedRunDirectory(buildDirectory.toPath(), runDirectory.toPath())
        val options = run.resolve("options.txt")
        require(Files.isSymbolicLink(options).not()) { "Client-test options must not be symbolic: $options" }
        val exists = Files.exists(options, LinkOption.NOFOLLOW_LINKS)
        require(exists.not() || Files.isRegularFile(options, LinkOption.NOFOLLOW_LINKS)) {
            "Client-test options must be a regular file: $options"
        }
        val previous = if (exists) Files.readAllLines(options, StandardCharsets.UTF_8) else emptyList()
        val retained =
            previous
                .mapIndexed { index, line -> if (index == 0) line.removePrefix("\uFEFF") else line }
                .filter { (it.substringBefore(':') in values).not() }
        val lines = retained + values.map { (name, value) -> "$name:$value" }
        replaceOptions(options, lines.joinToString("\n", postfix = "\n"))
    }

    /**
     * Resolve a strict build descendant and create missing test directories without traversing symbolic entries or escaping the real build root.
     */
    private fun ownedRunDirectory(
        buildDirectory: Path,
        runDirectory: Path,
    ): Path {
        val build = buildDirectory.toAbsolutePath().normalize()
        val run = runDirectory.toAbsolutePath().normalize()
        require(run != build && run.startsWith(build)) { "Client-test run directory must remain below its project build directory: $run" }
        require(Files.isSymbolicLink(build).not() && Files.isDirectory(build, LinkOption.NOFOLLOW_LINKS)) {
            "Client-test build directory must already exist and must not be symbolic: $build"
        }
        val realBuild = build.toRealPath()
        var current = build
        for (segment in build.relativize(run)) {
            current = current.resolve(segment)
            require(Files.isSymbolicLink(current).not()) { "Client-test run directory must not traverse symbolic entries: $current" }
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS).not()) Files.createDirectory(current)
            require(Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS) && current.toRealPath().startsWith(realBuild)) {
                "Client-test run directory escapes its real project build directory: $current"
            }
        }
        return run
    }

    /**
     * Replace only the owned options entry after closing its temporary UTF-8 output, and release the temporary file on every exit.
     */
    private fun replaceOptions(
        options: Path,
        contents: String,
    ) {
        val temporary = Files.createTempFile(options.parent, ".strata-client-options-", ".txt")
        try {
            Files.writeString(temporary, contents, StandardCharsets.UTF_8)
            try {
                Files.move(temporary, options, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, options, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
