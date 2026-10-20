package dev.s7a.strata.gradle.release

import dev.s7a.strata.gradle.release.ModrinthReleaseFixture.Companion.GAME_VERSIONS
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Owns the fixture files and HTTP servers for each independent release test.
 */
internal abstract class ModrinthReleaseTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    private val servers = mutableListOf<MockModrinthServer>()

    /**
     * Releases every local server created by the completed test, including failed cases.
     */
    @AfterEach
    fun closeServers() {
        servers.forEach(MockModrinthServer::close)
    }

    /**
     * Creates independent manifest and artifact bytes for the requested compatibility targets.
     */
    protected fun fixture(gameVersions: List<String> = GAME_VERSIONS): ModrinthReleaseFixture = ModrinthReleaseFixture.create(temporaryDirectory, gameVersions)

    /**
     * Starts a server whose lifecycle remains owned by this test.
     */
    protected fun server(fixture: ModrinthReleaseFixture): MockModrinthServer =
        MockModrinthServer(fixture).also { server ->
            servers += server
            server.start()
        }

    /**
     * Requires malformed or conflicting remote state to fail before any release write.
     */
    protected fun assertStageFailsBeforeEveryWrite(
        fixture: ModrinthReleaseFixture,
        server: MockModrinthServer,
    ): IllegalStateException {
        val failure =
            assertThrows(IllegalStateException::class.java) {
                fixture.coordinator(server).stage()
            }
        assertEquals(0, server.createRequests)
        assertEquals(0, server.projectBootstrapRequests)
        assertEquals(0, server.disclosureBootstrapRequests)
        assertEquals(0, server.submitRequests)
        assertTrue(server.writeEvents.isEmpty())
        return failure
    }
}
