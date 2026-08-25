package dev.s7a.strata.gradle.release

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.s7a.strata.gradle.release.ModrinthApiClient.AiUse
import dev.s7a.strata.gradle.release.ModrinthApiClient.DisclosureType
import dev.s7a.strata.gradle.release.ModrinthApiClient.ProjectStatus
import dev.s7a.strata.gradle.release.ModrinthApiClient.ProjectType
import dev.s7a.strata.gradle.release.ModrinthApiClient.RemoteDisclosure
import dev.s7a.strata.gradle.release.ModrinthApiClient.SideSupport
import dev.s7a.strata.gradle.release.ModrinthApiClient.VersionEnvironment
import dev.s7a.strata.gradle.release.ModrinthApiClient.WriteRejectedException
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.Executors

/** Verifies release reconciliation against an in-process Modrinth-compatible HTTP server. */
internal class ModrinthReleaseCoordinatorTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    private val servers = mutableListOf<MockModrinthServer>()

    @AfterEach
    fun closeServers() {
        servers.forEach(MockModrinthServer::close)
    }

    @Test
    fun `stage is exact idempotent and final verification hashes CDN bytes`() {
        val fixture = fixture()
        val server =
            server(fixture).also { mock ->
                mock.ambiguousFirstCreate = true
                mock.remainingStaleProjectReadsAfterSubmit = 2
            }
        val coordinator = fixture.coordinator(server)

        assertEquals(20, coordinator.preflight().absent.size)
        assertEquals(20, coordinator.stage().listed.size)
        assertEquals(20, server.createRequests)
        assertEquals(20, coordinator.stage().listed.size)
        assertEquals(20, server.createRequests)

        assertEquals(ProjectStatus.PROCESSING.wireValue, coordinator.submit().projectStatus)
        assertEquals(1, server.submitRequests)
        assertEquals(ProjectStatus.PROCESSING.wireValue, coordinator.submit().projectStatus)
        assertEquals(1, server.submitRequests)

        server.projectStatus = ProjectStatus.APPROVED
        assertEquals(20, coordinator.verify().listed.size)
        assertTrue(server.downloadRequests.containsAll(fixture.manifest.artifacts.map { artifact -> artifact.fileName }))
        assertFalse(server.cdnReceivedAuthorization)
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

        assertEquals(20, preflight.absent.size)
        assertEquals(0, server.createRequests)
        assertEquals(0, server.projectBootstrapRequests)
        assertEquals(0, server.disclosureBootstrapRequests)
        assertTrue(server.writeEvents.isEmpty())

        val staged = coordinator.stage()

        assertEquals(20, staged.listed.size)
        assertEquals(20, server.createRequests)
        assertEquals(1, server.projectBootstrapRequests)
        assertEquals(1, server.disclosureBootstrapRequests)
        assertEquals(
            List(20) { WriteEvent.CREATE_VERSION } +
                listOf(WriteEvent.PATCH_PROJECT, WriteEvent.PATCH_DISCLOSURES),
            server.writeEvents,
        )
        assertTrue(server.projectBootstrapIsExact)
        assertTrue(server.disclosureBootstrapIsExact)

        assertEquals(20, coordinator.stage().listed.size)
        assertEquals(20, server.createRequests)
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
                mock.seedVersions(20)
            }

        val receipt = fixture.coordinator(server).stage()

        assertEquals(20, receipt.listed.size)
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
                mock.seedVersions(20)
            }

        val receipt = fixture.coordinator(server).stage()

        assertEquals(20, receipt.listed.size)
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

        assertEquals(20, receipt.listed.size)
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

        assertEquals(20, receipt.listed.size)
        assertEquals(20, server.createRequests)
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
        assertEquals(20, server.createRequests)
        assertEquals(1, server.projectBootstrapRequests)
        assertEquals(1, server.disclosureBootstrapRequests)
        assertTrue(server.projectBootstrapIsExact)
        assertFalse(server.disclosureBootstrapIsExact)

        assertEquals(20, coordinator.stage().listed.size)
        assertEquals(20, server.createRequests)
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
        server.corruptFirstVersionName = true

        val failure = assertThrows(IllegalStateException::class.java, coordinator::preflight)

        assertTrue(failure.message.orEmpty().contains("differs"))
        assertEquals(writes, server.createRequests)
    }

    @Test
    fun `partial stage creates only missing targets`() {
        val fixture = fixture()
        val server = server(fixture).also { mock -> mock.seedVersions(5) }

        val receipt = fixture.coordinator(server).stage()

        assertEquals(15, server.createRequests)
        assertEquals(20, receipt.listed.size)
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
        assertEquals(4, server.projectReadRequests)
    }

    @Test
    fun `ambiguous create timeout after commit is recovered by requery`() {
        val fixture = fixture()
        val server = server(fixture).also { mock -> mock.timeoutFirstCreateAfterCommit = true }
        val client = fixture.client(server, requestTimeoutMillis = 20L, retryBaseMillis = 1L)

        val receipt = fixture.coordinator(server, client).stage()

        assertEquals(20, receipt.listed.size)
        assertEquals(20, server.createRequests)
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

        assertEquals(20, receipt.listed.size)
        assertEquals(20, server.createRequests)
    }

    @Test
    fun `bounded stale absence permits one retry and recovers its deterministic rejection`() {
        val fixture = fixture()
        val server =
            server(fixture).also { mock ->
                mock.timeoutFirstCreateAfterCommit = true
                mock.remainingStaleVersionReadsAfterFirstCreate = 5
            }
        val client = fixture.client(server, requestTimeoutMillis = 20L, retryBaseMillis = 1L)

        val receipt = fixture.coordinator(server, client).stage()

        assertEquals(20, receipt.listed.size)
        assertEquals(21, server.createRequests)
    }

    @Test
    fun `documented create rejection after stale requery fails closed on committed mismatch`() {
        val fixture = fixture()
        val server =
            server(fixture).also { mock ->
                mock.timeoutFirstCreateAfterCommit = true
                mock.remainingStaleVersionReadsAfterFirstCreate = 1
                mock.corruptFirstVersionName = true
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

        assertEquals(20, receipt.listed.size)
        assertEquals(21, server.createRequests)
    }

    @Test
    fun `HTTP request timeout create requeries before bounded retry`() {
        val fixture = fixture()
        val server = server(fixture).also { mock -> mock.requestTimeoutFirstCreateBeforeCommit = true }

        val receipt = fixture.coordinator(server).stage()

        assertEquals(20, receipt.listed.size)
        assertEquals(21, server.createRequests)
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

    private fun assertStageFailsBeforeEveryWrite(
        fixture: Fixture,
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
                server.dependencyResponseMutation = { response -> response[location.wireName] = emptyList<String>() }
            }

            OptionalStringLocation.FILE_SHA256 -> {
                server.seedVersions(1)
                server.fileHashesResponseMutation = { response -> response[location.wireName] = emptyList<String>() }
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
                server.fileResponseMutation = { response -> response[location.wireName] = 0.5 }
            }
        }
    }

    private fun fixture(): Fixture {
        val bundle = temporaryDirectory.resolve("bundle").toFile()
        val artifactsDirectory = bundle.resolve("artifacts").also(java.io.File::mkdirs)
        val artifacts =
            GAME_VERSIONS.mapIndexed { index, gameVersion ->
                val fileName = "strata-runtime-minecraft-fabric-$gameVersion-0.1.0.jar"
                val bytes = "artifact-$index-$gameVersion".toByteArray(StandardCharsets.UTF_8)
                artifactsDirectory.resolve(fileName).writeBytes(bytes)
                ModrinthManifest.Artifact(
                    gameVersion = gameVersion,
                    versionNumber = "0.1.0+mc$gameVersion",
                    versionName = "Strata 0.1.0 for Minecraft $gameVersion",
                    fileName = fileName,
                    relativePath = "artifacts/$fileName",
                    size = bytes.size.toLong(),
                    sha256 = bytes.hash("SHA-256"),
                    sha512 = bytes.hash("SHA-512"),
                    mavenCoordinate = "dev.s7a.strata:strata-runtime-minecraft-fabric-$gameVersion:0.1.0",
                    githubAssetName = fileName,
                )
            }
        val gallery =
            listOf("overview", "inventory", "progress").mapIndexed { index, id ->
                val bytes = "gallery-$id".toByteArray(StandardCharsets.UTF_8)
                ModrinthManifest.GalleryAsset(
                    id = id,
                    path = "docs/components/$id.png",
                    sha256 = bytes.hash("SHA-256"),
                    featured = index == 0,
                    title = id.replaceFirstChar(Char::uppercase),
                    description = "$id gallery",
                    ordering = index,
                )
            }
        val iconBytes = "icon".toByteArray(StandardCharsets.UTF_8)
        val manifest =
            ModrinthManifest(
                schemaVersion = ModrinthManifest.CURRENT_SCHEMA_VERSION,
                projectId = PROJECT_ID,
                project =
                    ModrinthManifest.ProjectMetadata(
                        slug = "strata-ui",
                        title = "Strata",
                        description = ModrinthManifest.PROJECT_DESCRIPTION,
                        body = "# Strata\n\nCompiled API-only example.\n",
                        categories = setOf("library"),
                        additionalCategories = setOf("utility"),
                        licenseId = "MIT",
                        clientSide = SideSupport.REQUIRED,
                        serverSide = SideSupport.UNSUPPORTED,
                        sourceUrl = "https://github.com/sya-ri/strata",
                        issuesUrl = "https://github.com/sya-ri/strata/issues",
                        documentationUrl = "https://gh.s7a.dev/strata/",
                        aiDisclosureNote = ModrinthManifest.AI_DISCLOSURE_NOTE,
                        aiDisclosureUses = setOf(AiUse.CODE, AiUse.TEXT),
                        icon = ModrinthManifest.ProjectAsset("icon.png", iconBytes.hash("SHA-256")),
                        gallery = gallery,
                    ),
                releaseVersion = "0.1.0",
                changelog = "Release notes\n",
                artifacts = artifacts,
            )
        return Fixture(bundle, manifest, iconBytes)
    }

    private fun server(fixture: Fixture): MockModrinthServer =
        MockModrinthServer(fixture).also { server ->
            servers += server
            server.start()
        }

    private fun ByteArray.hash(algorithm: String): String = MessageDigest.getInstance(algorithm).digest(this).joinToString("") { byte -> "%02x".format(byte) }

    private data class Fixture(
        val bundle: java.io.File,
        val manifest: ModrinthManifest,
        val iconBytes: ByteArray,
    ) {
        fun client(
            server: MockModrinthServer,
            token: String = "test-token",
            requestTimeoutMillis: Long = 60_000L,
            retryBaseMillis: Long = 1L,
        ): ModrinthApiClient =
            ModrinthApiClient(
                apiBaseUrl = "${server.baseUrl}/v2",
                token = token,
                userAgent = "strata-test",
                allowedDownloadHosts = setOf("localhost"),
                allowInsecureDownloads = true,
                requestTimeout = java.time.Duration.ofMillis(requestTimeoutMillis),
                retryBaseMillis = retryBaseMillis,
            )

        fun coordinator(
            server: MockModrinthServer,
            client: ModrinthApiClient = client(server),
        ): ModrinthReleaseCoordinator = ModrinthReleaseCoordinator(manifest, bundle, client)
    }

    private class MockModrinthServer(
        private val fixture: Fixture,
    ) : AutoCloseable {
        private val executor = Executors.newCachedThreadPool()
        private val server = HttpServer.create(InetSocketAddress("localhost", 0), 0)
        private val versions = linkedSetOf<String>()
        private var remoteCategories: Set<String> = fixture.manifest.project.categories
        private var remoteAdditionalCategories: Set<String> = fixture.manifest.project.additionalCategories
        private var remoteEnvironments: Set<VersionEnvironment> = setOf(VersionEnvironment.CLIENT_ONLY)
        private var remoteClientSide: SideSupport = fixture.manifest.project.clientSide
        private var remoteServerSide: SideSupport = fixture.manifest.project.serverSide
        private var remoteDisclosures: List<RemoteDisclosure> =
            listOf(
                RemoteDisclosure(
                    type = DisclosureType.AI_CONTENT,
                    note = fixture.manifest.project.aiDisclosureNote,
                    aiUses = fixture.manifest.project.aiDisclosureUses,
                ),
            )

        var projectStatus: ProjectStatus = ProjectStatus.DRAFT
        var projectType: ProjectType = ProjectType.MOD
        var projectTypeAfterFirstVersion: ProjectType? = null
        var createRequests: Int = 0
        var submitRequests: Int = 0
        var projectBootstrapRequests: Int = 0
        var disclosureBootstrapRequests: Int = 0
        var projectBootstrapRecoveryReads: Int = 0
        var versionReadRequests: Int = 0
        var ambiguousFirstCreate: Boolean = false
        var ambiguousFirstSubmitAfterCommit: Boolean = false
        var corruptFirstVersionName: Boolean = false
        var cdnReceivedAuthorization: Boolean = false
        var projectFailureBody: String? = null
        var escapeCdnRedirect: Boolean = false
        var redirectToFailingCdn: Boolean = false
        var failingDownloadRequests: Int = 0
        var optimizedGalleryDownloadRequests: Int = 0
        var timeoutFirstCreateAfterCommit: Boolean = false
        var rateLimitFirstCreateBeforeCommit: Boolean = false
        var requestTimeoutFirstCreateBeforeCommit: Boolean = false
        var rateLimitFirstProjectBootstrapBeforeCommit: Boolean = false
        var unexpectedVersionPresent: Boolean = false
        var initialCreateRejectionStatus: Int? = null
        var initialDisclosureBootstrapRejectionStatus: Int? = null
        var initialSubmitRejectionStatus: Int? = null
        var repeatSubmitRejectionStatus: Int = 400
        var projectStatusAfterRepeatSubmitRejection: ProjectStatus? = null
        var remainingStaleVersionReadsAfterFirstCreate: Int = 0
        var remainingStaleProjectReadsAfterSubmit: Int = 0
        var projectReadRequests: Int = 0
        var projectResponseDelayMillis: Long = 0L
        var projectResponseMutation: (MutableMap<String, Any?>) -> Unit = { _ -> }
        var overviewGalleryResponseMutation: (MutableMap<String, Any?>) -> Unit = { _ -> }
        var dependencyResponseMutation: (MutableMap<String, Any?>) -> Unit = { _ -> }
        var fileResponseMutation: (MutableMap<String, Any?>) -> Unit = { _ -> }
        var fileHashesResponseMutation: (MutableMap<String, Any?>) -> Unit = { _ -> }
        val transientProjectStatuses: ArrayDeque<Int> = ArrayDeque()
        val downloadRequests: MutableSet<String> = linkedSetOf()
        val writeEvents: MutableList<WriteEvent> = mutableListOf()
        var projectBootstrapPayloadKeys: Set<String> = emptySet()
        val baseUrl: String
            get() = "http://localhost:${server.address.port}"
        val projectBootstrapIsExact: Boolean
            get() =
                remoteCategories == fixture.manifest.project.categories &&
                    remoteAdditionalCategories == fixture.manifest.project.additionalCategories &&
                    remoteEnvironments == setOf(VersionEnvironment.CLIENT_ONLY) &&
                    remoteClientSide == fixture.manifest.project.clientSide &&
                    remoteServerSide == fixture.manifest.project.serverSide
        val disclosureBootstrapIsExact: Boolean
            get() =
                remoteDisclosures ==
                    listOf(
                        RemoteDisclosure(
                            type = DisclosureType.AI_CONTENT,
                            note = fixture.manifest.project.aiDisclosureNote,
                            aiUses = fixture.manifest.project.aiDisclosureUses,
                        ),
                    )

        fun start() {
            server.executor = executor
            server.createContext("/", ::handle)
            server.start()
        }

        fun seedVersions(count: Int) {
            fixture.manifest.artifacts
                .take(count)
                .forEach { artifact -> versions += artifact.versionNumber }
        }

        fun makeProjectBootstrapStateEmpty() {
            remoteCategories = emptySet()
            remoteAdditionalCategories = emptySet()
            remoteEnvironments = setOf(VersionEnvironment.UNKNOWN)
            remoteClientSide = SideSupport.UNKNOWN
            remoteServerSide = SideSupport.UNKNOWN
        }

        fun makeBootstrapStateEmpty() {
            makeProjectBootstrapStateEmpty()
            remoteDisclosures = emptyList()
        }

        fun makeProjectBootstrapStatePartial() {
            remoteAdditionalCategories = emptySet()
            remoteClientSide = SideSupport.UNKNOWN
        }

        fun makeProjectBootstrapStateConflict() {
            remoteCategories = setOf("adventure")
        }

        fun makeDisclosureBootstrapStateConflict() {
            remoteDisclosures =
                listOf(
                    RemoteDisclosure(
                        type = DisclosureType.AI_CONTENT,
                        note = fixture.manifest.project.aiDisclosureNote,
                        aiUses = setOf(AiUse.ASSETS),
                    ),
                )
        }

        fun makeDisclosureBootstrapStateDeleted() {
            remoteDisclosures =
                listOf(
                    RemoteDisclosure(
                        type = DisclosureType.AI_CONTENT,
                        note = fixture.manifest.project.aiDisclosureNote,
                        aiUses = fixture.manifest.project.aiDisclosureUses,
                        deleted = true,
                    ),
                )
        }

        override fun close() {
            server.stop(0)
            executor.shutdownNow()
        }

        private fun handle(exchange: HttpExchange) {
            try {
                val path = exchange.requestURI.path
                when {
                    path == "/v2/project/$PROJECT_ID" && exchange.requestMethod == "GET" -> project(exchange)
                    path == "/v2/project/$PROJECT_ID" && exchange.requestMethod == "PATCH" -> submit(exchange)
                    path == "/v3/project/$PROJECT_ID" && exchange.requestMethod == "PATCH" -> bootstrapProject(exchange)
                    path == "/v3/project/$PROJECT_ID/disclosures" -> disclosures(exchange)
                    path == "/v2/tag/game_version" -> gameVersions(exchange)
                    path == "/v2/project/$PROJECT_ID/version" -> versions(exchange)
                    path == "/v2/version" && exchange.requestMethod == "POST" -> create(exchange)
                    path.startsWith("/cdn/") -> download(exchange, path.removePrefix("/cdn/"))
                    else -> respond(exchange, 404, "{}")
                }
            } catch (failure: Throwable) {
                runCatching { respond(exchange, 500, JsonOutput.toJson(mapOf("error" to failure.message.orEmpty()))) }
            } finally {
                exchange.close()
            }
        }

        private fun project(exchange: HttpExchange) {
            projectReadRequests += 1
            if (rateLimitFirstProjectBootstrapBeforeCommit && projectBootstrapRequests == 1) {
                projectBootstrapRecoveryReads += 1
            }
            if (0L < projectResponseDelayMillis) Thread.sleep(projectResponseDelayMillis)
            if (transientProjectStatuses.isNotEmpty()) {
                respond(exchange, transientProjectStatuses.removeFirst(), "transient")
                return
            }
            val failure = projectFailureBody
            if (failure != null) {
                respond(exchange, 400, failure)
                return
            }
            val project = fixture.manifest.project
            val visibleStatus =
                if (0 < submitRequests && 0 < remainingStaleProjectReadsAfterSubmit) {
                    remainingStaleProjectReadsAfterSubmit -= 1
                    ProjectStatus.DRAFT
                } else {
                    projectStatus
                }
            val gallery =
                project.gallery.map { image ->
                    linkedMapOf<String, Any?>(
                        "url" to "$baseUrl/cdn/optimized-${image.id}",
                        "raw_url" to "$baseUrl/cdn/${image.id}",
                        "featured" to image.featured,
                        "title" to image.title,
                        "description" to image.description,
                        "ordering" to image.ordering,
                    )
                }
            overviewGalleryResponseMutation(gallery.first())
            val response =
                linkedMapOf<String, Any?>(
                    "id" to PROJECT_ID,
                    "slug" to project.slug,
                    "title" to project.title,
                    "description" to project.description,
                    "body" to project.body,
                    "project_type" to projectType.wireValue,
                    "status" to visibleStatus.wireValue,
                    "categories" to remoteCategories,
                    "additional_categories" to remoteAdditionalCategories,
                    "environment" to remoteEnvironments.map(VersionEnvironment::wireValue),
                    "license" to mapOf("id" to project.licenseId),
                    "client_side" to remoteClientSide.wireValue,
                    "server_side" to remoteServerSide.wireValue,
                    "source_url" to project.sourceUrl,
                    "issues_url" to project.issuesUrl,
                    "wiki_url" to project.documentationUrl,
                    "raw_icon_url" to "$baseUrl/cdn/icon",
                    "gallery" to gallery,
                )
            projectResponseMutation(response)
            respond(
                exchange,
                200,
                JsonOutput.toJson(response),
            )
        }

        private fun bootstrapProject(exchange: HttpExchange) {
            projectBootstrapRequests += 1
            writeEvents += WriteEvent.PATCH_PROJECT
            val project = fixture.manifest.project
            val payload = requestObject(exchange)
            val expectedPayload = linkedMapOf<String, Any>()
            if (remoteCategories != project.categories) expectedPayload["categories"] = project.categories.toList()
            if (remoteAdditionalCategories != project.additionalCategories) {
                expectedPayload["additional_categories"] = project.additionalCategories.toList()
            }
            if (
                remoteEnvironments != setOf(VersionEnvironment.CLIENT_ONLY) ||
                remoteClientSide != project.clientSide ||
                remoteServerSide != project.serverSide
            ) {
                expectedPayload["environment"] = VersionEnvironment.CLIENT_ONLY.wireValue
            }
            check(expectedPayload.isNotEmpty())
            check(payload == expectedPayload)
            projectBootstrapPayloadKeys = payload.keys.filterIsInstance<String>().toSet()
            if (rateLimitFirstProjectBootstrapBeforeCommit && projectBootstrapRequests == 1) {
                respond(exchange, 429, "rate limited")
                return
            }
            remoteCategories = project.categories
            remoteAdditionalCategories = project.additionalCategories
            remoteEnvironments = setOf(VersionEnvironment.CLIENT_ONLY)
            remoteClientSide = project.clientSide
            remoteServerSide = project.serverSide
            respond(exchange, 204, "")
        }

        private fun disclosures(exchange: HttpExchange) {
            if (exchange.requestMethod == "PATCH") {
                bootstrapDisclosures(exchange)
            } else {
                respond(
                    exchange,
                    200,
                    JsonOutput.toJson(
                        mapOf(
                            "disclosures" to
                                remoteDisclosures.map { disclosure ->
                                    linkedMapOf<String, Any?>(
                                        "type" to disclosure.type.wireValue,
                                        "note" to disclosure.note,
                                        "uses" to disclosure.aiUses.map(AiUse::wireValue).sorted(),
                                    ).apply {
                                        if (disclosure.deleted) {
                                            this["deleted_at"] = "2026-08-25T00:00:00Z"
                                        }
                                    }
                                },
                        ),
                    ),
                )
            }
        }

        private fun bootstrapDisclosures(exchange: HttpExchange) {
            disclosureBootstrapRequests += 1
            writeEvents += WriteEvent.PATCH_DISCLOSURES
            val payload = requestObject(exchange)
            check(payload.keys == setOf("set", "remove"))
            check(payload["remove"] == emptyList<Any>())
            val set = payload["set"] as? List<*> ?: error("Disclosure bootstrap set must be an array.")
            check(set.size == 1)
            val disclosure = set.single() as? Map<*, *> ?: error("Disclosure bootstrap entry must be an object.")
            check(disclosure.keys == setOf("type", "note", "uses"))
            check(disclosure["type"] == DisclosureType.AI_CONTENT.wireValue)
            check(disclosure["note"] == fixture.manifest.project.aiDisclosureNote)
            check((disclosure["uses"] as? List<*>)?.toSet() == setOf(AiUse.CODE.wireValue, AiUse.TEXT.wireValue))
            val rejection = initialDisclosureBootstrapRejectionStatus
            if (disclosureBootstrapRequests == 1 && rejection != null) {
                respond(exchange, rejection, "rejected")
                return
            }
            remoteDisclosures =
                listOf(
                    RemoteDisclosure(
                        type = DisclosureType.AI_CONTENT,
                        note = fixture.manifest.project.aiDisclosureNote,
                        aiUses = fixture.manifest.project.aiDisclosureUses,
                    ),
                )
            respond(exchange, 204, "")
        }

        private fun gameVersions(exchange: HttpExchange) {
            respond(exchange, 200, JsonOutput.toJson(GAME_VERSIONS.map { version -> mapOf("version" to version) }))
        }

        private fun versions(exchange: HttpExchange) {
            versionReadRequests += 1
            val visibleVersions =
                if (
                    fixture.manifest.artifacts
                        .first()
                        .versionNumber in versions &&
                    0 < remainingStaleVersionReadsAfterFirstCreate
                ) {
                    remainingStaleVersionReadsAfterFirstCreate -= 1
                    versions.drop(1)
                } else {
                    versions
                }
            respond(
                exchange,
                200,
                JsonOutput.toJson(
                    visibleVersions
                        .map { number ->
                            val artifact = fixture.manifest.artifacts.single { candidate -> candidate.versionNumber == number }
                            version(artifact)
                        }.let { expected ->
                            if (unexpectedVersionPresent) expected + unexpectedVersion() else expected
                        },
                ),
            )
        }

        private fun create(exchange: HttpExchange) {
            val body = String(exchange.requestBody.readBytes(), StandardCharsets.ISO_8859_1)
            val artifact =
                fixture.manifest.artifacts.singleOrNull { candidate ->
                    body.contains("\"version_number\":\"${candidate.versionNumber}\"")
                } ?: error("Create request did not contain one exact known version number.")
            createRequests += 1
            writeEvents += WriteEvent.CREATE_VERSION
            val initialRejection = initialCreateRejectionStatus
            if (createRequests == 1 && initialRejection != null) {
                respond(
                    exchange,
                    initialRejection,
                    JsonOutput.toJson(mapOf("error" to "duplicate_version", "description" to "Initial create rejected.")),
                )
                return
            }
            if (rateLimitFirstCreateBeforeCommit && createRequests == 1) {
                respond(exchange, 429, "rate limited")
                return
            }
            if (requestTimeoutFirstCreateBeforeCommit && createRequests == 1) {
                respond(exchange, 408, "request timed out")
                return
            }
            if (versions.add(artifact.versionNumber).not()) {
                respond(
                    exchange,
                    400,
                    JsonOutput.toJson(mapOf("error" to "duplicate_version", "description" to "Version already exists.")),
                )
                return
            }
            if (versions.size == 1) {
                projectTypeAfterFirstVersion?.let { inferredType -> projectType = inferredType }
            }
            if (timeoutFirstCreateAfterCommit && createRequests == 1) {
                Thread.sleep(100L)
                respond(exchange, 200, JsonOutput.toJson(version(artifact)))
            } else if (ambiguousFirstCreate && createRequests == 1) {
                respond(exchange, 503, "temporarily unavailable")
            } else {
                respond(exchange, 200, JsonOutput.toJson(version(artifact)))
            }
        }

        private fun submit(exchange: HttpExchange) {
            submitRequests += 1
            val initialRejection = initialSubmitRejectionStatus
            if (submitRequests == 1 && initialRejection != null) {
                respond(
                    exchange,
                    initialRejection,
                    JsonOutput.toJson(mapOf("error" to "rejected_submission", "description" to "Initial submit rejected.")),
                )
                return
            }
            if (1 < submitRequests) {
                projectStatusAfterRepeatSubmitRejection?.let { status -> projectStatus = status }
                respond(
                    exchange,
                    repeatSubmitRejectionStatus,
                    JsonOutput.toJson(mapOf("error" to "rejected_submission", "description" to "Project was already submitted.")),
                )
                return
            }
            projectStatus = ProjectStatus.PROCESSING
            if (ambiguousFirstSubmitAfterCommit) {
                respond(exchange, 503, "temporarily unavailable")
            } else {
                respond(exchange, 204, "")
            }
        }

        private fun download(
            exchange: HttpExchange,
            name: String,
        ) {
            if (exchange.requestHeaders.containsKey("Authorization")) cdnReceivedAuthorization = true
            if (escapeCdnRedirect && name == "icon") {
                exchange.responseHeaders.add("Location", "https://example.com/escaped")
                respond(exchange, 302, "")
                return
            }
            if (redirectToFailingCdn && name == "redirect") {
                exchange.responseHeaders.add("Location", "$baseUrl/cdn/failing")
                respond(exchange, 302, "")
                return
            }
            if (redirectToFailingCdn && name == "failing") {
                failingDownloadRequests += 1
                respond(exchange, 503, "transient")
                return
            }
            val bytes =
                when (name) {
                    "icon" -> {
                        fixture.iconBytes
                    }

                    "overview", "inventory", "progress" -> {
                        "gallery-$name".toByteArray(StandardCharsets.UTF_8)
                    }

                    "optimized-overview", "optimized-inventory", "optimized-progress" -> {
                        optimizedGalleryDownloadRequests += 1
                        "optimized-$name".toByteArray(StandardCharsets.UTF_8)
                    }

                    else -> {
                        downloadRequests += name
                        fixture.bundle.resolve("artifacts/$name").readBytes()
                    }
                }
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.write(bytes)
        }

        private fun version(artifact: ModrinthManifest.Artifact): Map<String, Any?> {
            val dependency =
                linkedMapOf<String, Any?>(
                    "project_id" to ModrinthManifest.FABRIC_LANGUAGE_KOTLIN_PROJECT_ID,
                    "version_id" to null,
                    "file_name" to null,
                    "dependency_type" to "required",
                )
            dependencyResponseMutation(dependency)
            val hashes =
                linkedMapOf<String, Any?>(
                    "sha256" to artifact.sha256,
                    "sha512" to artifact.sha512,
                )
            fileHashesResponseMutation(hashes)
            val file =
                linkedMapOf<String, Any?>(
                    "hashes" to hashes,
                    "url" to "$baseUrl/cdn/${artifact.fileName}",
                    "filename" to artifact.fileName,
                    "primary" to true,
                    "size" to artifact.size,
                )
            fileResponseMutation(file)
            return linkedMapOf(
                "id" to "id-${artifact.gameVersion}",
                "project_id" to PROJECT_ID,
                "name" to
                    if (corruptFirstVersionName && artifact == fixture.manifest.artifacts.first()) {
                        "corrupt"
                    } else {
                        artifact.versionName
                    },
                "version_number" to artifact.versionNumber,
                "changelog" to fixture.manifest.changelog,
                "dependencies" to listOf(dependency),
                "game_versions" to listOf(artifact.gameVersion),
                "version_type" to "release",
                "loaders" to listOf("fabric"),
                "featured" to true,
                "status" to "listed",
                "environment" to "client_only",
                "files" to listOf(file),
            )
        }

        private fun unexpectedVersion(): Map<String, Any?> {
            val artifact = fixture.manifest.artifacts.first()
            return version(artifact) +
                mapOf(
                    "id" to "unexpected-id",
                    "version_number" to "unexpected-version",
                    "files" to
                        listOf(
                            mapOf(
                                "hashes" to mapOf("sha256" to "0".repeat(64), "sha512" to "0".repeat(128)),
                                "url" to "$baseUrl/cdn/unexpected.jar",
                                "filename" to "unexpected.jar",
                                "primary" to true,
                                "size" to 1,
                            ),
                        ),
                )
        }

        private fun respond(
            exchange: HttpExchange,
            status: Int,
            body: String,
        ) {
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(status, if (status == 204) -1L else bytes.size.toLong())
            if (status != 204) exchange.responseBody.write(bytes)
        }

        private fun requestObject(exchange: HttpExchange): Map<*, *> =
            JsonSlurper().parse(exchange.requestBody, StandardCharsets.UTF_8.name()) as? Map<*, *>
                ?: error("Bootstrap request must contain one JSON object.")
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

    private enum class WriteEvent {
        CREATE_VERSION,
        PATCH_PROJECT,
        PATCH_DISCLOSURES,
    }

    companion object {
        private const val PROJECT_ID = "project-id"
        private val GAME_VERSIONS =
            listOf(
                "1.20.1",
                "1.20.2",
                "1.20.3",
                "1.20.4",
                "1.20.5",
                "1.20.6",
                "1.21",
                "1.21.1",
                "1.21.2",
                "1.21.3",
                "1.21.4",
                "1.21.5",
                "1.21.6",
                "1.21.7",
                "1.21.8",
                "1.21.9",
                "1.21.10",
                "1.21.11",
                "26.1",
                "26.2",
            )
    }
}
