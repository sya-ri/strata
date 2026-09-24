package dev.s7a.strata.gradle.release

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** Exercises publication-owned artifact shapes and rejects incomplete or unsafe release inventories. */
internal class MavenPublicationFilesTest {
    @Test
    fun `plugin and multiplatform file sets retain every artifact`() {
        val artifacts = listOf("dev.s7a:paper", "dev.s7a:api-js", "dev.s7a:api-multiplatform")
        val suffixes =
            listOf(
                listOf(".pom", ".module", ".jar", "-plugin.jar", "-sources.jar", "-javadoc.jar"),
                listOf(".pom", ".module", ".klib", "-sources.jar", "-javadoc.jar"),
                listOf(".pom", ".module", ".jar", "-kotlin-tooling-metadata.json", "-sources.jar", "-javadoc.jar"),
            )
        val entries = artifacts.zip(suffixes).flatMap { (artifact, files) -> files.map { "$artifact:$it" } }
        assertEquals(artifacts.zip(suffixes).toMap(), MavenPublicationFiles.resolve(entries, artifacts))
        for (invalid in listOf(entries.drop(6), entries + entries.first(), entries + "dev.s7a:paper:../escape", entries.filterNot { it.endsWith(":.pom") })) {
            assertThrows(IllegalStateException::class.java) { MavenPublicationFiles.resolve(invalid, artifacts) }
        }
        assertEquals(MavenPublicationFiles.legacySuffixes, MavenPublicationFiles.resolve(null, artifacts).getValue(artifacts.first()))
    }
}
