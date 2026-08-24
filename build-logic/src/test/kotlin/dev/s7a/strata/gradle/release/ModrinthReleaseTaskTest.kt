package dev.s7a.strata.gradle.release

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** Verifies that authenticated release tasks cannot forward a PAT to an overridden endpoint. */
internal class ModrinthReleaseTaskTest {
    @Test
    fun `authenticated endpoint is fixed to official Modrinth v2`() {
        assertEquals(
            "https://api.modrinth.com/v2",
            ModrinthReleaseTask.validateAuthenticatedApiBaseUrl("https://api.modrinth.com/v2/"),
        )
        listOf(
            "http://api.modrinth.com/v2",
            "https://example.com/v2",
            "https://api.modrinth.com:443/v2",
            "https://api.modrinth.com/v2/project",
            "https://api.modrinth.com/v2?token=echo",
        ).forEach { endpoint ->
            assertThrows(IllegalStateException::class.java) {
                ModrinthReleaseTask.validateAuthenticatedApiBaseUrl(endpoint)
            }
        }
    }
}
