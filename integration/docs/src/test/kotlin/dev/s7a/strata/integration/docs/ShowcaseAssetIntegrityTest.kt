package dev.s7a.strata.integration.docs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Keeps Mojang compatibility checksums and showcase identities distinct across byte-array and streamed inputs.
 */
internal class ShowcaseAssetIntegrityTest {
    @field:TempDir
    lateinit var directory: Path

    @Test
    fun byteArraysAndFilesMatchKnownHashesAcrossMultipleReadBuffers() {
        val bytes = "a".repeat(10_000).toByteArray()
        val sha1 = "a080cbda64850abb7b7f67ee875ba068074ff6fe"
        val sha256 = "27dd1f61b867b6a0f6e9d8a41c43231de52107e53ae424de8f847b821db4b711"
        val path = Files.write(directory.resolve("asset"), bytes)

        assertEquals(sha1, ShowcaseAssetIntegrity.sha1(bytes))
        assertEquals(sha256, ShowcaseAssetIntegrity.sha256(bytes))
        assertEquals(ShowcaseAssetIntegrity.Hashes(sha1, sha256), ShowcaseAssetIntegrity.hashes(path, bytes.size.toLong()))
    }
}
