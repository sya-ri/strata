package dev.s7a.strata.gradle.release

import dev.s7a.strata.gradle.release.MockModrinthServer.WriteEvent
import dev.s7a.strata.gradle.release.ModrinthApiClient.ProjectStatus
import dev.s7a.strata.gradle.release.ModrinthApiClient.ProjectType
import dev.s7a.strata.gradle.release.ModrinthApiClient.WriteRejectedException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Verifies bootstrap, schema validation and exact staging against a local Modrinth-compatible server.
 */
internal class ModrinthReleaseCoordinatorTest : ModrinthReleaseTest() {
    @Test
    fun `stage is exact idempotent and final verification hashes CDN bytes`() {
        val fixture = fixture()
        val server =
            server(fixture).also { mock ->
                mock.ambiguousFirstCreate = true
                mock.remainingStaleProjectReadsAfterSubmit = 2
            }
        val coordinator = fixture.coordinator(server)

        assertEquals(fixture.manifest.artifacts.size, coordinator.preflight().absent.size)
        assertEquals(fixture.manifest.artifacts.size, coordinator.stage().listed.size)
        assertEquals(fixture.manifest.artifacts.size, server.createRequests)
        assertEquals(fixture.manifest.artifacts.size, coordinator.stage().listed.size)
        assertEquals(fixture.manifest.artifacts.size, server.createRequests)

        assertEquals(ProjectStatus.PROCESSING.wireValue, coordinator.submit().projectStatus)
        assertEquals(1, server.submitRequests)
        assertEquals(ProjectStatus.PROCESSING.wireValue, coordinator.submit().projectStatus)
        assertEquals(1, server.submitRequests)

        server.projectStatus = ProjectStatus.APPROVED
        assertEquals(fixture.manifest.artifacts.size, coordinator.verify().listed.size)
        assertTrue(server.downloadRequests.containsAll(fixture.manifest.artifacts.map { artifact -> artifact.fileName }))
        assertFalse(server.cdnReceivedAuthorization)
    }

    @Test
    fun `version publication is resumable independently of project review and page edits`() {
        val fixture = fixture()
        val server = server(fixture).also { it.projectStatus = ProjectStatus.WITHHELD }
        val coordinator = ModrinthReleaseCoordinator(fixture.manifest, fixture.bundle, fixture.client(server), ModrinthReleaseCoordinator.ProjectPolicy.VERSIONS_ONLY)

        assertEquals(fixture.manifest.artifacts.size, coordinator.stage().listed.size)
        assertEquals(fixture.manifest.artifacts.size, coordinator.stage().listed.size)
        assertEquals(fixture.manifest.artifacts.size, coordinator.verify().listed.size)
        assertEquals(fixture.manifest.artifacts.size, server.createRequests)
        assertTrue(server.writeEvents.all { it == WriteEvent.CREATE_VERSION })
        assertEquals(ProjectStatus.WITHHELD, server.projectStatus)
        assertEquals(0, server.submitRequests)
        assertFalse(server.cdnReceivedAuthorization)
    }

    @Test
    fun `manifest and reconciliation derive the configured target count`() {
        val fixture = fixture(listOf("1.20", "26.2"))
        val server = server(fixture)
        val coordinator = fixture.coordinator(server)

        fixture.manifest.validate()
        assertEquals(2, coordinator.preflight().absent.size)
        assertEquals(2, coordinator.stage().listed.size)
        assertEquals(2, server.createRequests)
    }

    @Test
    fun `unclassified empty draft becomes a mod after its first Fabric version`() {
        val fixture = fixture()
        val server =
            server(fixture).also { mock ->
                mock.makeBootstrapStateEmpty()
                mock.projectType = ProjectType.UNCLASSIFIED
                mock.projectTypeAfterFirstVersion = ProjectType.MOD
            }
        val coordinator = fixture.coordinator(server)

        val preflight = coordinator.preflight()

        assertEquals(fixture.manifest.artifacts.size, preflight.absent.size)
        assertEquals(0, server.createRequests)
        assertEquals(0, server.projectBodyUpdateRequests)
        assertEquals(0, server.projectBootstrapRequests)
        assertEquals(0, server.disclosureBootstrapRequests)
        assertTrue(server.writeEvents.isEmpty())

        val staged = coordinator.stage()

        assertEquals(fixture.manifest.artifacts.size, staged.listed.size)
        assertEquals(fixture.manifest.artifacts.size, server.createRequests)
        assertEquals(1, server.projectBootstrapRequests)
        assertEquals(1, server.disclosureBootstrapRequests)
        assertEquals(
            List(fixture.manifest.artifacts.size) { WriteEvent.CREATE_VERSION } +
                listOf(WriteEvent.PATCH_PROJECT, WriteEvent.PATCH_DISCLOSURES),
            server.writeEvents,
        )
        assertTrue(server.projectBootstrapIsExact)
        assertTrue(server.disclosureBootstrapIsExact)

        assertEquals(fixture.manifest.artifacts.size, coordinator.stage().listed.size)
        assertEquals(fixture.manifest.artifacts.size, server.createRequests)
        assertEquals(1, server.projectBootstrapRequests)
        assertEquals(1, server.disclosureBootstrapRequests)
        assertEquals(ProjectType.MOD, server.projectType)
    }

    @Test
    fun `unclassified project with an existing version fails before every write`() {
        val fixture = fixture()
        val server =
            server(fixture).also { mock ->
                mock.projectType = ProjectType.UNCLASSIFIED
                mock.seedVersions(1)
            }

        val failure = assertStageFailsBeforeEveryWrite(fixture, server)

        assertTrue(failure.message.orEmpty().contains("unclassified"))
    }

    @Test
    fun `unclassified project stops after one version when Modrinth does not infer mod`() {
        val fixture = fixture()
        val server =
            server(fixture).also { mock ->
                mock.makeBootstrapStateEmpty()
                mock.projectType = ProjectType.UNCLASSIFIED
            }

        val failure =
            assertThrows(IllegalStateException::class.java) {
                fixture.coordinator(server).stage()
            }

        assertTrue(failure.message.orEmpty().contains("did not classify"))
        assertEquals(1, server.createRequests)
        assertEquals(0, server.projectBootstrapRequests)
        assertEquals(0, server.disclosureBootstrapRequests)
        assertEquals(listOf(WriteEvent.CREATE_VERSION), server.writeEvents)
    }

    @Test
    fun `unclassified project outside draft state fails before every write`() {
        val fixture = fixture()
        val statuses = listOf(ProjectStatus.UNLISTED, ProjectStatus.PROCESSING, ProjectStatus.APPROVED)

        statuses.forEach { status ->
            val server =
                server(fixture).also { mock ->
                    mock.projectType = ProjectType.UNCLASSIFIED
                    mock.projectStatus = status
                }

            val failure = assertStageFailsBeforeEveryWrite(fixture, server)

            assertTrue(failure.message.orEmpty().contains("unclassified"))
        }
    }

    @Test
    fun `unknown project type remains rejected before every write`() {
        val fixture = fixture()
        val server =
            server(fixture).also { mock ->
                mock.projectResponseMutation = { response -> response["project_type"] = "future-project-type" }
            }

        val failure = assertStageFailsBeforeEveryWrite(fixture, server)

        assertTrue(failure.message.orEmpty().contains("Unknown Modrinth project type"))
    }

    @Test
    fun `draft bootstrap resumes after every version was created before project patch`() {
        val fixture = fixture()
        val server =
            server(fixture).also { mock ->
                mock.makeBootstrapStateEmpty()
                mock.seedVersions(fixture.manifest.artifacts.size)
            }

        val receipt = fixture.coordinator(server).stage()

        assertEquals(fixture.manifest.artifacts.size, receipt.listed.size)
        assertEquals(0, server.createRequests)
        assertEquals(1, server.projectBootstrapRequests)
        assertEquals(1, server.disclosureBootstrapRequests)
        assertEquals(listOf(WriteEvent.PATCH_PROJECT, WriteEvent.PATCH_DISCLOSURES), server.writeEvents)
        assertTrue(server.projectBootstrapIsExact)
        assertTrue(server.disclosureBootstrapIsExact)
    }

    @Test
    fun `deleted disclosure is restored instead of satisfying the active contract`() {
        val fixture = fixture()
        val server =
            server(fixture).also { mock ->
                mock.makeDisclosureBootstrapStateDeleted()
                mock.seedVersions(fixture.manifest.artifacts.size)
            }

        val receipt = fixture.coordinator(server).stage()

        assertEquals(fixture.manifest.artifacts.size, receipt.listed.size)
        assertEquals(0, server.createRequests)
        assertEquals(0, server.projectBootstrapRequests)
        assertEquals(1, server.disclosureBootstrapRequests)
        assertEquals(listOf(WriteEvent.PATCH_DISCLOSURES), server.writeEvents)
        assertTrue(server.disclosureBootstrapIsExact)
    }

    @Test
    fun `draft bootstrap patch omits classification fields that are already exact`() {
        val fixture = fixture()
        val server = server(fixture).also(MockModrinthServer::makeProjectBootstrapStatePartial)

        val receipt = fixture.coordinator(server).stage()

        assertEquals(fixture.manifest.artifacts.size, receipt.listed.size)
        assertEquals(setOf("additional_categories", "environment"), server.projectBootstrapPayloadKeys)
        assertEquals(1, server.projectBootstrapRequests)
        assertEquals(0, server.disclosureBootstrapRequests)
        assertTrue(server.projectBootstrapIsExact)
    }

    @Test
    fun `draft bootstrap rejects an unexpected remote version before every write`() {
        val fixture = fixture()
        val server =
            server(fixture).also { mock ->
                mock.makeBootstrapStateEmpty()
                mock.unexpectedVersionPresent = true
            }

        assertThrows(IllegalStateException::class.java) {
            fixture.coordinator(server).stage()
        }

        assertEquals(0, server.createRequests)
        assertEquals(0, server.projectBootstrapRequests)
        assertEquals(0, server.disclosureBootstrapRequests)
        assertTrue(server.writeEvents.isEmpty())
    }

    @Test
    fun `conflicting nonempty bootstrap state blocks every write`() {
        val fixture = fixture()
        val conflicts =
            listOf<(MockModrinthServer) -> Unit>(
                MockModrinthServer::makeProjectBootstrapStateConflict,
                MockModrinthServer::makeDisclosureBootstrapStateConflict,
            )

        conflicts.forEach { configureConflict ->
            val server = server(fixture).also(configureConflict)

            assertThrows(IllegalStateException::class.java) {
                fixture.coordinator(server).stage()
            }

            assertEquals(0, server.createRequests)
            assertEquals(0, server.projectBootstrapRequests)
            assertEquals(0, server.disclosureBootstrapRequests)
            assertTrue(server.writeEvents.isEmpty())
        }
    }

    @ParameterizedTest(name = "{0} rejects omitted, null, and wrong-type values")
    @ValueSource(strings = ["categories", "additional_categories", "environment", "client_side", "server_side"])
    fun `required project response fields fail closed before every write`(wireName: String) {
        val fixture = fixture()

        MalformedRequiredShape.entries.forEach { shape ->
            val server =
                server(fixture).also { mock ->
                    mock.projectResponseMutation = { response -> applyMalformedShape(shape, response, wireName) }
                }

            val failure = assertStageFailsBeforeEveryWrite(fixture, server)

            assertTrue(failure.message.orEmpty().contains(wireName))
        }
    }

    @ParameterizedTest(name = "overview ordering rejects {0}")
    @ValueSource(strings = ["OMITTED", "NULL", "WRONG_TYPE"])
    fun `required gallery ordering fails closed before every write`(shapeName: String) {
        val fixture = fixture()
        val shape = MalformedRequiredShape.valueOf(shapeName)
        val server =
            server(fixture).also { mock ->
                mock.overviewGalleryResponseMutation = { response -> applyMalformedShape(shape, response, "ordering") }
            }

        val failure = assertStageFailsBeforeEveryWrite(fixture, server)

        assertTrue(failure.message.orEmpty().contains("ordering"))
    }

    @ParameterizedTest(name = "{0} rejects a non-string value")
    @ValueSource(strings = ["RAW_ICON_URL", "DEPENDENCY_VERSION_ID", "DEPENDENCY_FILE_NAME", "FILE_SHA256"])
    fun `optional strings reject schema drift before every write`(locationName: String) {
        val fixture = fixture()
        val location = OptionalStringLocation.valueOf(locationName)
        val server = server(fixture)
        configureMalformedResponse(location, server)

        val failure = assertStageFailsBeforeEveryWrite(fixture, server)

        assertTrue(failure.message.orEmpty().contains(location.wireName))
    }

    @ParameterizedTest(name = "{0} rejects a fractional number")
    @ValueSource(strings = ["GALLERY_ORDERING", "VERSION_FILE_SIZE"])
    fun `integer fields reject fractional numbers before every write`(locationName: String) {
        val fixture = fixture()
        val location = FractionalNumberLocation.valueOf(locationName)
        val server = server(fixture)
        configureFractionalResponse(location, server)

        val failure = assertStageFailsBeforeEveryWrite(fixture, server)

        assertTrue(failure.message.orEmpty().contains(location.wireName))
    }

    @Test
    fun `rate limited project bootstrap requeries before one bounded retry`() {
        val fixture = fixture()
        val server =
            server(fixture).also { mock ->
                mock.makeProjectBootstrapStateEmpty()
                mock.rateLimitFirstProjectBootstrapBeforeCommit = true
            }

        val receipt = fixture.coordinator(server).stage()

        assertEquals(fixture.manifest.artifacts.size, receipt.listed.size)
        assertEquals(fixture.manifest.artifacts.size, server.createRequests)
        assertEquals(2, server.projectBootstrapRequests)
        assertEquals(0, server.disclosureBootstrapRequests)
        assertTrue(0 < server.projectBootstrapRecoveryReads)
        assertTrue(server.projectBootstrapIsExact)
    }

    @Test
    fun `disclosure bootstrap failure resumes without repeating completed writes`() {
        val fixture = fixture()
        val server =
            server(fixture).also { mock ->
                mock.makeBootstrapStateEmpty()
                mock.initialDisclosureBootstrapRejectionStatus = 400
            }
        val coordinator = fixture.coordinator(server)

        val failure = assertThrows(WriteRejectedException::class.java, coordinator::stage)

        assertEquals(400, failure.statusCode)
        assertEquals(fixture.manifest.artifacts.size, server.createRequests)
        assertEquals(1, server.projectBootstrapRequests)
        assertEquals(1, server.disclosureBootstrapRequests)
        assertTrue(server.projectBootstrapIsExact)
        assertFalse(server.disclosureBootstrapIsExact)

        assertEquals(fixture.manifest.artifacts.size, coordinator.stage().listed.size)
        assertEquals(fixture.manifest.artifacts.size, server.createRequests)
        assertEquals(1, server.projectBootstrapRequests)
        assertEquals(2, server.disclosureBootstrapRequests)
        assertTrue(server.disclosureBootstrapIsExact)
    }

    @Test
    fun `project verification hashes raw gallery bytes instead of optimized variants`() {
        val fixture = fixture()
        val server = server(fixture)

        fixture.coordinator(server).preflight()

        assertEquals(0, server.optimizedGalleryDownloadRequests)
    }

    @Test
    fun `existing mismatch fails before another write`() {
        val fixture = fixture()
        val server = server(fixture)
        val coordinator = fixture.coordinator(server)
        coordinator.stage()
        val writes = server.createRequests
        server.responses.corruptFirstVersionName = true

        val failure = assertThrows(IllegalStateException::class.java, coordinator::preflight)

        assertTrue(failure.message.orEmpty().contains("differs"))
        assertEquals(writes, server.createRequests)
    }

    @Test
    fun `partial stage creates only missing targets`() {
        val fixture = fixture()
        val existingTargetCount = 1
        val server = server(fixture).also { mock -> mock.seedVersions(existingTargetCount) }

        val receipt = fixture.coordinator(server).stage()

        assertEquals(fixture.manifest.artifacts.size - existingTargetCount, server.createRequests)
        assertEquals(fixture.manifest.artifacts.size, receipt.listed.size)
    }

    private fun applyMalformedShape(
        shape: MalformedRequiredShape,
        response: MutableMap<String, Any?>,
        wireName: String,
    ) {
        when (shape) {
            MalformedRequiredShape.OMITTED -> response.remove(wireName)
            MalformedRequiredShape.NULL -> response[wireName] = null
            MalformedRequiredShape.WRONG_TYPE -> response[wireName] = mapOf("unexpected" to true)
        }
    }

    private fun configureMalformedResponse(
        location: OptionalStringLocation,
        server: MockModrinthServer,
    ) {
        when (location) {
            OptionalStringLocation.RAW_ICON_URL -> {
                server.projectResponseMutation = { response -> response[location.wireName] = emptyList<String>() }
            }

            OptionalStringLocation.DEPENDENCY_VERSION_ID,
            OptionalStringLocation.DEPENDENCY_FILE_NAME,
            -> {
                server.seedVersions(1)
                server.responses.dependencyResponseMutation = { response -> response[location.wireName] = emptyList<String>() }
            }

            OptionalStringLocation.FILE_SHA256 -> {
                server.seedVersions(1)
                server.responses.fileHashesResponseMutation = { response -> response[location.wireName] = emptyList<String>() }
            }
        }
    }

    private fun configureFractionalResponse(
        location: FractionalNumberLocation,
        server: MockModrinthServer,
    ) {
        when (location) {
            FractionalNumberLocation.GALLERY_ORDERING -> {
                server.overviewGalleryResponseMutation = { response -> response[location.wireName] = 0.5 }
            }

            FractionalNumberLocation.VERSION_FILE_SIZE -> {
                server.seedVersions(1)
                server.responses.fileResponseMutation = { response -> response[location.wireName] = 0.5 }
            }
        }
    }

    private enum class MalformedRequiredShape {
        OMITTED,
        NULL,
        WRONG_TYPE,
    }

    private enum class OptionalStringLocation(
        val wireName: String,
    ) {
        RAW_ICON_URL("raw_icon_url"),
        DEPENDENCY_VERSION_ID("version_id"),
        DEPENDENCY_FILE_NAME("file_name"),
        FILE_SHA256("sha256"),
    }

    private enum class FractionalNumberLocation(
        val wireName: String,
    ) {
        GALLERY_ORDERING("ordering"),
        VERSION_FILE_SIZE("size"),
    }
}
