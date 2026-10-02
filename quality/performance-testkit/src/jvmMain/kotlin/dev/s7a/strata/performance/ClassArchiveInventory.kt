package dev.s7a.strata.performance

import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Bounded canonical archive entry inventory shared by provenance and its verification.
 */
internal object ClassArchiveInventory {
    /**
     * Returns safe unique class entries in canonical unsigned UTF-8 order within declared bounds.
     */
    internal fun entries(
        archivePath: Path,
        maximumEntries: Int,
        maximumEntryBytes: Long,
    ): List<String> =
        ZipFile(archivePath.toFile()).use { archive ->
            val entries = mutableListOf<String>()
            val seen = mutableSetOf<String>()
            val archiveEntries = archive.entries()
            while (archiveEntries.hasMoreElements()) {
                val entry = archiveEntries.nextElement()
                if (entry.isDirectory || entry.name.endsWith(".class").not()) continue
                validateClassEntry(entry, maximumEntryBytes)
                require(seen.add(entry.name)) { "Duplicate class entry: ${entry.name}" }
                require(entries.size < maximumEntries) { "The class tree exceeds the metadata entry limit" }
                entries += entry.name
            }
            require(entries.isNotEmpty()) { "The Strata module contains no class entries: $archivePath" }
            entries.sortedWith(::compareUtf8)
        }

    private fun validateClassEntry(
        entry: ZipEntry,
        maximumEntryBytes: Long,
    ) {
        val name = entry.name
        require(
            name.isNotEmpty() &&
                name.startsWith('/').not() &&
                '\\' !in name &&
                '=' !in name &&
                name.none(Char::isISOControl) &&
                name.split('/').none { it.isEmpty() || it in setOf(".", "..") } &&
                Path
                    .of(name)
                    .normalize()
                    .iterator()
                    .asSequence()
                    .joinToString("/") == name,
        ) { "Unsafe class entry path: $name" }
        require(entry.size <= maximumEntryBytes || entry.size < 0) { "The class entry exceeds the metadata byte limit: $name" }
    }

    private fun compareUtf8(
        left: String,
        right: String,
    ): Int {
        val leftBytes = left.toByteArray(Charsets.UTF_8)
        val rightBytes = right.toByteArray(Charsets.UTF_8)
        val sharedLength = minOf(leftBytes.size, rightBytes.size)
        for (index in 0 until sharedLength) {
            val comparison = (leftBytes[index].toInt() and 255).compareTo(rightBytes[index].toInt() and 255)
            if (comparison != 0) return comparison
        }
        return leftBytes.size.compareTo(rightBytes.size)
    }
}
