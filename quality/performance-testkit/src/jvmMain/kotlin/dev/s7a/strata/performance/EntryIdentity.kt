package dev.s7a.strata.performance

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Canonical entry hashing shared by directory and archive provenance.
 */
internal object EntryIdentity {
    /**
     * Hashes or selects canonical sorted archive entries for loaded-code provenance.
     */
    internal fun directorySha256(
        root: Path,
        include: (String) -> Boolean,
    ): String {
        val entries =
            Files.walk(root).use { paths ->
                paths
                    .filter { Files.isRegularFile(it) }
                    .map(root::relativize)
                    .map { it.toString().replace('\\', '/') }
                    .filter(include)
                    .sorted()
                    .toList()
            }
        require(entries.isNotEmpty()) { "No benchmark identity entries in $root" }
        return hashEntries(entries) { entry -> Files.newInputStream(root.resolve(entry.replace('/', File.separatorChar))) }
    }

    /**
     * Hashes or selects canonical sorted archive entries for loaded-code provenance.
     */
    internal fun zipSha256(
        path: Path,
        include: (String) -> Boolean,
    ): String =
        ZipFile(path.toFile()).use { archive ->
            val entries =
                archive
                    .entries()
                    .asSequence()
                    .filterNot(ZipEntry::isDirectory)
                    .map(ZipEntry::getName)
                    .filter(include)
                    .sorted()
                    .toList()
            require(entries.isNotEmpty()) { "No benchmark identity entries in $path" }
            hashEntries(entries) { entry -> archive.getInputStream(checkNotNull(archive.getEntry(entry))) }
        }

    private fun hashEntries(
        entries: List<String>,
        open: (String) -> InputStream,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        entries.forEach { entry ->
            val name = entry.toByteArray(Charsets.UTF_8)
            digest.update(name.size.toString().toByteArray(Charsets.US_ASCII))
            digest.update(':'.code.toByte())
            digest.update(name)
            digest.update(0.toByte())
            open(entry).use { input -> update(digest, input) }
            digest.update(0xff.toByte())
        }
        return HexFormat.of().formatHex(digest.digest())
    }

    /**
     * Hashes or selects canonical sorted archive entries for loaded-code provenance.
     */
    internal fun selectedEntry(name: String): Boolean =
        (name.endsWith(".class")) ||
            selectedResourceEntry(name)

    /**
     * Hashes or selects canonical sorted archive entries for loaded-code provenance.
     */
    internal fun selectedResourceEntry(name: String): Boolean = name.startsWith("assets/") || name.startsWith("data/")

    /**
     * Hashes or selects canonical sorted archive entries for loaded-code provenance.
     */
    internal fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        update(digest, input)
        return HexFormat.of().formatHex(digest.digest())
    }

    private fun update(
        digest: MessageDigest,
        input: InputStream,
    ) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val size = input.read(buffer)
            if (size < 0) return
            digest.update(buffer, 0, size)
        }
    }
}
