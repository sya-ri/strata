package dev.s7a.strata.gradle.release

import dev.s7a.strata.gradle.release.MockModrinthServer.WriteEvent
import dev.s7a.strata.gradle.release.ModrinthApiClient.ProjectStatus
import dev.s7a.strata.gradle.release.ModrinthApiClient.WriteRejectedException
import dev.s7a.strata.gradle.release.ModrinthReleaseFixture.Companion.PREVIOUS_PROJECT_BODY
import dev.s7a.strata.gradle.release.ModrinthReleaseFixture.Companion.PROJECT_ID
import groovy.json.JsonOutput
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies bounded retries, ambiguous-write recovery, finalization and CDN trust boundaries.
 */
internal class ModrinthReleaseRecoveryTest : ModrinthReleaseTest() {
    @Test
    fun `processing and approved projects accept an append only compatibility release`() {
        listOf(ProjectStatus.PROCESSING, ProjectStatus.APPROVED).forEach { status ->
            val fixture = fixture()
            val server =
                server(fixture).also { mock ->
                    mock.projectStatus = status
                    mock.remoteProjectBody = PREVIOUS_PROJECT_BODY
                    mock.seedHistoricalVersions(fixture.manifest.artifacts.lastIndex)
                }
            val coordinator = fixture.coordinator(server)

            assertEquals(fixture.manifest.artifacts.size, coordinator.preflight().absent.size)
            val receipt = coordinator.stage()

            assertEquals(fixture.manifest.artifacts.size, receipt.listed.size)
            assertEquals(fixture.manifest.artifacts.size, server.createRequests)
            assertEquals(0, server.projectBodyUpdateRequests)
            assertEquals(fixture.manifest.artifacts.size, coordinator.stage().listed.size)
            assertEquals(fixture.manifest.artifacts.size, server.createRequests)
            assertEquals(0, server.projectBodyUpdateRequests)
            if (status == ProjectStatus.APPROVED) {
                assertEquals(fixture.manifest.artifacts.size, coordinator.finalizeProject().listed.size)
                assertEquals(1, server.projectBodyUpdateRequests)
                assertEquals(fixture.manifest.artifacts.size, coordinator.finalizeProject().listed.size)
                assertEquals(1, server.projectBodyUpdateRequests)
            } else {
                assertThrows(IllegalStateException::class.java, coordinator::finalizeProject)
                assertEquals(0, server.projectBodyUpdateRequests)
            }
        }
    }

    @Test
    fun `tracked predecessor body transition recovers an ambiguous committed write`() {
        val fixture = fixture()
        val server =
            server(fixture).also { mock ->
                mock.projectStatus = ProjectStatus.APPROVED
                mock.remoteProjectBody = PREVIOUS_PROJECT_BODY
                mock.ambiguousFirstBodyUpdateAfterCommit = true
                mock.remainingStaleProjectReadsAfterBodyUpdate = 1
                mock.seedHistoricalVersions(fixture.manifest.artifacts.lastIndex)
            }

        val coordinator = fixture.coordinator(server)
        assertEquals(fixture.manifest.artifacts.size, coordinator.stage().listed.size)
        val receipt = coordinator.finalizeProject()

        assertEquals(fixture.manifest.artifacts.size, receipt.listed.size)
        assertEquals(1, server.projectBodyUpdateRequests)
        assertEquals(WriteEvent.PATCH_PROJECT_BODY, server.writeEvents.last())
    }

    @Test
    fun `body transition stops when approval is lost after reconciliation`() {
        val fixture = fixture()
        val server =
            server(fixture).also { mock ->
                mock.projectStatus = ProjectStatus.APPROVED
                mock.projectStatusAfterFirstRead = ProjectStatus.PROCESSING
                mock.remoteProjectBody = PREVIOUS_PROJECT_BODY
                mock.seedHistoricalVersions(fixture.manifest.artifacts.lastIndex)
                mock.seedVersions(fixture.manifest.artifacts.size)
            }

        val failure = assertThrows(IllegalStateException::class.java, fixture.coordinator(server)::finalizeProject)

        assertTrue(failure.message.orEmpty().contains("remain approved"))
        assertEquals(0, server.projectBodyUpdateRequests)
    }

    @Test
    fun `untracked project body drift blocks every release write`() {
        val fixture = fixture()
        val server =
            server(fixture).also { mock ->
                mock.projectStatus = ProjectStatus.APPROVED
                mock.remoteProjectBody = "# Unreviewed manual body\n"
                mock.seedHistoricalVersions(fixture.manifest.artifacts.lastIndex)
            }

        val failure = assertStageFailsBeforeEveryWrite(fixture, server)

        assertTrue(failure.message.orEmpty().contains("tracked predecessor"))
        assertEquals(0, server.projectBodyUpdateRequests)
    }

    @Test
    fun `read retries rate limits and server failures`() {
        val fixture = fixture()
        val server =
            server(fixture).also { mock ->
                mock.transientProjectStatuses += 408
                mock.transientProjectStatuses += 429
                mock.transientProjectStatuses += 503
            }

        fixture.coordinator(server).preflight()

        assertEquals(4, server.projectReadRequests)
    }

    @Test
    fun `read timeout exhausts a bounded retry budget`() {
        val fixture = fixture()
        val server = server(fixture).also { mock -> mock.projectResponseDelayMillis = 100L }
        val client = fixture.client(server, requestTimeoutMillis = 10L, retryBaseMillis = 1L)

        val failure = assertThrows(IllegalStateException::class.java) { client.getProject(PROJECT_ID) }

        assertTrue(failure.message.orEmpty().contains("after 4 attempts"))
    }

    @Test
    fun `ambiguous create timeout after commit is recovered by requery`() {
        val fixture = fixture()
        val server = server(fixture).also { mock -> mock.timeoutFirstCreateAfterCommit = true }
        val client = fixture.client(server, requestTimeoutMillis = 20L, retryBaseMillis = 1L)

        val receipt = fixture.coordinator(server, client).stage()

        assertEquals(fixture.manifest.artifacts.size, receipt.listed.size)
        assertEquals(fixture.manifest.artifacts.size, server.createRequests)
    }

    @Test
    fun `one stale read after ambiguous create does not repeat the write`() {
        val fixture = fixture()
        val server =
            server(fixture).also { mock ->
                mock.timeoutFirstCreateAfterCommit = true
                mock.remainingStaleVersionReadsAfterFirstCreate = 1
            }
        val client = fixture.client(server, requestTimeoutMillis = 20L, retryBaseMillis = 1L)

        val receipt = fixture.coordinator(server, client).stage()

        assertEquals(fixture.manifest.artifacts.size, receipt.listed.size)
        assertEquals(fixture.manifest.artifacts.size, server.createRequests)
    }

    @Test
    fun `bounded stale absence permits one retry and recovers its deterministic rejection`() {
        val fixture = fixture()
        val server =
            server(fixture).also { mock ->
                mock.ambiguousFirstCreate = true
                mock.remainingStaleVersionReadsAfterFirstCreate = 5
            }

        val receipt = fixture.coordinator(server).stage()

        assertEquals(fixture.manifest.artifacts.size, receipt.listed.size)
        assertEquals(fixture.manifest.artifacts.size + 1, server.createRequests)
    }

    @Test
    fun `documented create rejection after stale requery fails closed on committed mismatch`() {
        val fixture = fixture()
        val server =
            server(fixture).also { mock ->
                mock.timeoutFirstCreateAfterCommit = true
                mock.remainingStaleVersionReadsAfterFirstCreate = 1
                mock.responses.corruptFirstVersionName = true
            }
        val client = fixture.client(server, requestTimeoutMillis = 20L, retryBaseMillis = 1L)

        val failure =
            assertThrows(IllegalStateException::class.java) {
                fixture.coordinator(server, client).stage()
            }

        assertTrue(failure.message.orEmpty().contains("differs"))
        assertEquals(1, server.createRequests)
    }

    @Test
    fun `initial deterministic create rejection fails without recovery polling`() {
        val fixture = fixture()
        val server = server(fixture).also { mock -> mock.initialCreateRejectionStatus = 400 }

        val failure =
            assertThrows(WriteRejectedException::class.java) {
                fixture.coordinator(server).stage()
            }

        assertEquals(400, failure.statusCode)
        assertFalse(failure.message.orEmpty().contains("duplicate_version"))
        assertEquals(1, server.createRequests)
        assertEquals(1, server.versionReadRequests)
    }

    @Test
    fun `rate limited create requeries before bounded retry`() {
        val fixture = fixture()
        val server = server(fixture).also { mock -> mock.rateLimitFirstCreateBeforeCommit = true }

        val receipt = fixture.coordinator(server).stage()

        assertEquals(fixture.manifest.artifacts.size, receipt.listed.size)
        assertEquals(fixture.manifest.artifacts.size + 1, server.createRequests)
    }

    @Test
    fun `HTTP request timeout create requeries before bounded retry`() {
        val fixture = fixture()
        val server = server(fixture).also { mock -> mock.requestTimeoutFirstCreateBeforeCommit = true }

        val receipt = fixture.coordinator(server).stage()

        assertEquals(fixture.manifest.artifacts.size, receipt.listed.size)
        assertEquals(fixture.manifest.artifacts.size + 1, server.createRequests)
    }

    @Test
    fun `ambiguous submit recovers deterministic retry rejection without a third write`() {
        listOf(400, 409).forEach { rejectionStatus ->
            val fixture = fixture()
            val server =
                server(fixture).also { mock ->
                    mock.ambiguousFirstSubmitAfterCommit = true
                    mock.remainingStaleProjectReadsAfterSubmit = 6
                    mock.repeatSubmitRejectionStatus = rejectionStatus
                }
            val coordinator = fixture.coordinator(server)
            coordinator.stage()

            val receipt = coordinator.submit()

            assertEquals(ProjectStatus.PROCESSING.wireValue, receipt.projectStatus)
            assertEquals(2, server.submitRequests)
        }
    }

    @Test
    fun `initial deterministic submit rejection fails without recovery polling`() {
        val fixture = fixture()
        val server = server(fixture).also { mock -> mock.initialSubmitRejectionStatus = 400 }
        val coordinator = fixture.coordinator(server)
        coordinator.stage()
        val readsBeforeSubmit = server.projectReadRequests

        val failure =
            assertThrows(WriteRejectedException::class.java) {
                coordinator.submit()
            }

        assertEquals(400, failure.statusCode)
        assertFalse(failure.message.orEmpty().contains("rejected_submission"))
        assertEquals(1, server.submitRequests)
        assertEquals(readsBeforeSubmit + 1, server.projectReadRequests)
    }

    @Test
    fun `ambiguous submit rejection fails closed on unexpected project status`() {
        val fixture = fixture()
        val server =
            server(fixture).also { mock ->
                mock.ambiguousFirstSubmitAfterCommit = true
                mock.remainingStaleProjectReadsAfterSubmit = 5
                mock.repeatSubmitRejectionStatus = 400
                mock.projectStatusAfterRepeatSubmitRejection = ProjectStatus.WITHHELD
            }
        val coordinator = fixture.coordinator(server)
        coordinator.stage()

        val failure = assertThrows(IllegalStateException::class.java, coordinator::submit)

        assertTrue(failure.message.orEmpty().contains("unexpected status withheld"))
        assertEquals(2, server.submitRequests)
    }

    @Test
    fun `CDN failures after an allowed redirect terminate at retry budget`() {
        val fixture = fixture()
        val server = server(fixture).also { mock -> mock.redirectToFailingCdn = true }
        val client = fixture.client(server, retryBaseMillis = 1L)

        val failure =
            assertThrows(IllegalStateException::class.java) {
                client.hashRemoteFile("${server.baseUrl}/cdn/redirect")
            }

        assertTrue(failure.message.orEmpty().contains("after 4 attempts"))
        assertEquals(4, server.failingDownloadRequests)
    }

    @Test
    fun `manifest rejects drift in canonical project metadata`() {
        val fixture = fixture()
        val drifted = fixture.manifest.copy(project = fixture.manifest.project.copy(title = "Different"))

        val failure = assertThrows(IllegalStateException::class.java, drifted::validate)

        assertTrue(failure.message.orEmpty().contains("title"))
    }

    @Test
    fun `manifest rejects a non-string predecessor body hash`() {
        val fixture = fixture()
        val manifest = fixture.manifest.toMap().toMutableMap()
        val project = (manifest["project"] as Map<*, *>).toMutableMap()
        project["previousBodySha256"] = 1
        manifest["project"] = project
        val file = temporaryDirectory.resolve("invalid-predecessor.json").toFile()
        file.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(manifest)) + "\n")

        val failure = assertThrows(IllegalStateException::class.java) { ModrinthManifest.read(file) }

        assertTrue(failure.message.orEmpty().contains("previousBodySha256 must be a string"))
    }

    @Test
    fun `remote error redacts token from exception and cause chain`() {
        val fixture = fixture()
        val token = "sensitive-release-token"
        val server = server(fixture).also { mock -> mock.projectFailureBody = "echo:$token" }
        val client = fixture.client(server, token)

        val failure = assertThrows(IllegalStateException::class.java) { client.getProject(PROJECT_ID) }

        assertFalse(failure.stackTraceToString().contains(token))
        assertTrue(failure.message.orEmpty().contains("<redacted>"))
    }

    @Test
    fun `CDN redirect cannot escape authoritative host allowlist`() {
        val fixture = fixture()
        val server = server(fixture).also { mock -> mock.escapeCdnRedirect = true }
        val client = fixture.client(server)

        val failure =
            assertThrows(IllegalStateException::class.java) {
                client.hashRemoteFile("${server.baseUrl}/cdn/icon")
            }

        assertTrue(failure.message.orEmpty().contains("non-authoritative host"))
    }
}
