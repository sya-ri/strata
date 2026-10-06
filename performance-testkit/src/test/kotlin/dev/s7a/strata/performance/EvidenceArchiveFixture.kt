package dev.s7a.strata.performance

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Rewrites a real test archive with one additional harmless resource to test byte identity changes.
 */
internal object EvidenceArchiveFixture {
    /**
     * Preserves every original entry and changes the actual archive digest without changing class bytes.
     */
    fun append(path: Path) {
        val replacement = path.resolveSibling("${path.fileName}.replacement")
        ZipOutputStream(Files.newOutputStream(replacement)).use { output ->
            ZipFile(path.toFile()).use { archive -> copy(archive, output) }
            output.putNextEntry(ZipEntry("fixture/changed-version.txt"))
            output.write("changed bytes".toByteArray())
            output.closeEntry()
        }
        Files.move(replacement, path, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun copy(
        archive: ZipFile,
        output: ZipOutputStream,
    ) {
        archive.entries().asSequence().forEach { entry ->
            output.putNextEntry(ZipEntry(entry.name))
            if (entry.isDirectory.not()) archive.getInputStream(entry).use { it.copyTo(output) }
            output.closeEntry()
        }
    }
}
