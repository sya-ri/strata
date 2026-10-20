package dev.s7a.strata.gradle.release

import dev.s7a.strata.gradle.release.ModrinthApiClient.DependencyType
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies dependency identity independently of the API response order.
 */
internal class ModrinthRemoteContractTest : ModrinthReleaseTest() {
    @Test
    fun `reordered dependencies retain exact version identity`() {
        val fixture = fixture()
        val server = server(fixture).also { it.seedVersions(1) }
        val client = fixture.client(server)
        val remote = client.getProjectVersions(fixture.manifest.projectId).single()

        ModrinthRemoteContract(fixture.manifest, client).assertVersion(
            remote.copy(dependencies = remote.dependencies.reversed()),
            fixture.manifest.artifacts.first(),
        )
    }

    @Test
    fun `missing duplicate or changed dependencies still reject the version`() {
        val fixture = fixture()
        val server = server(fixture).also { it.seedVersions(1) }
        val client = fixture.client(server)
        val remote = client.getProjectVersions(fixture.manifest.projectId).single()
        val contract = ModrinthRemoteContract(fixture.manifest, client)
        val dependencies = remote.dependencies
        val conflicts =
            listOf(
                dependencies.dropLast(1),
                dependencies + dependencies.first(),
                dependencies.map { it.copy(projectId = "other-project") },
                dependencies.map { it.copy(versionId = "pinned-version") },
                dependencies.map { it.copy(fileName = "other.jar") },
                dependencies.map { it.copy(type = DependencyType.OPTIONAL) },
            )

        conflicts.forEach { conflicting ->
            val failure =
                assertThrows(IllegalStateException::class.java) {
                    contract.assertVersion(remote.copy(dependencies = conflicting.reversed()), fixture.manifest.artifacts.first())
                }
            assertTrue(failure.message.orEmpty().contains("dependencies"))
        }
    }
}
