package dev.s7a.strata.gradle.release

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import groovy.json.JsonOutput
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.Executors

/** Verifies authenticated Central deployment reconciliation against an in-process Portal. */
internal class MavenCentralPortalCoordinatorTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    private val servers = mutableListOf<MockCentralPortal>()

    @AfterEach
    fun closeServers() {
        servers.forEach(MockCentralPortal::close)
    }

    @Test
    fun `preflight distinguishes absent and exact deployments with base checksums`() {
        val fixture = fixture()
        val server = server(fixture)
        val coordinator = fixture.coordinator(server)

        server.mode = Mode.ABSENT
        val absent = coordinator.preflight(fixture.coordinates, temporaryDirectory.resolve("absent"), incompleteAttempts = 1)
        assertEquals(MavenCentralPortalCoordinator.State.ABSENT, absent.state)
        assertFalse(Files.exists(temporaryDirectory.resolve("absent")))

        server.mode = Mode.EXACT
        server.transientListStatuses.addAll(listOf(429, 503))
        server.statuses.add(MavenCentralPortalCoordinator.DeploymentState.VALIDATED)
        val evidence = temporaryDirectory.resolve("exact")
        val exact =
            coordinator.preflight(
                fixture.coordinates,
                evidence,
                incompleteAttempts = 1,
                statusAttempts = 1,
                statusDelayMillis = 0L,
            )
        assertEquals(MavenCentralPortalCoordinator.State.EXACT, exact.state)
        assertEquals(MavenCentralPortalCoordinator.DeploymentState.VALIDATED, exact.deploymentState)
        assertEquals(10, exact.verifiedContentFileCount)
        assertEquals(20, exact.verifiedChecksumCount)
        assertEquals(10, Files.walk(evidence).use { paths -> paths.filter(Files::isRegularFile).count() })
        assertTrue(2 < server.listRequestCount)
    }

    @Test
    fun `exact deployment counts derive from every supplied coordinate`() {
        val fixture = fixture(listOf("strata-api", "strata-runtime-core"))
        val server = server(fixture)
        server.statuses.add(MavenCentralPortalCoordinator.DeploymentState.VALIDATED)
        val evidence = temporaryDirectory.resolve("multiple-coordinates")

        val exact =
            fixture.coordinator(server).preflight(
                fixture.coordinates,
                evidence,
                incompleteAttempts = 1,
                statusAttempts = 1,
                statusDelayMillis = 0L,
            )

        val expectedContentFileCount = fixture.coordinates.size * BASE_SUFFIXES.size * 2
        val expectedChecksumCount = fixture.coordinates.size * BASE_SUFFIXES.size * CHECKSUMS.size
        assertEquals(MavenCentralPortalCoordinator.State.EXACT, exact.state)
        assertEquals(expectedContentFileCount, exact.verifiedContentFileCount)
        assertEquals(expectedChecksumCount, exact.verifiedChecksumCount)
        assertEquals(expectedContentFileCount.toLong(), Files.walk(evidence).use { paths -> paths.filter(Files::isRegularFile).count() })
    }

    @Test
    fun `preflight polls consecutive absence before accepting an existing exact deployment`() {
        val fixture = fixture()
        val server = server(fixture)
        server.hiddenListReads = 2
        server.statuses.add(MavenCentralPortalCoordinator.DeploymentState.VALIDATED)

        val receipt =
            fixture.coordinator(server).preflight(
                coordinateLines = fixture.coordinates,
                evidenceDirectory = temporaryDirectory.resolve("stale-list"),
                incompleteAttempts = 3,
                pollDelayMillis = 0L,
                statusAttempts = 1,
                statusDelayMillis = 0L,
            )

        assertEquals(MavenCentralPortalCoordinator.State.EXACT, receipt.state)
        assertEquals(3, server.listRequestCount)
    }

    @Test
    fun `duplicate partial failed and differing deployments fail closed`() {
        val fixture = fixture()
        val server = server(fixture)
        val coordinator = fixture.coordinator(server)

        server.mode = Mode.DUPLICATE
        assertTrue(
            assertThrows(IllegalStateException::class.java) {
                coordinator.preflight(fixture.coordinates, temporaryDirectory.resolve("duplicate"), incompleteAttempts = 1)
            }.message.orEmpty().contains("duplicate deployments"),
        )

        server.mode = Mode.PARTIAL
        assertTrue(
            assertThrows(IllegalStateException::class.java) {
                coordinator.preflight(fixture.coordinates, temporaryDirectory.resolve("partial"), incompleteAttempts = 1)
            }.message.orEmpty().contains("inventory remained incomplete"),
        )

        server.mode = Mode.FAILED
        assertTrue(
            assertThrows(IllegalStateException::class.java) {
                coordinator.preflight(fixture.coordinates, temporaryDirectory.resolve("failed"), incompleteAttempts = 1)
            }.message.orEmpty().contains("failed validation"),
        )

        server.mode = Mode.MISMATCH
        server.statuses.add(MavenCentralPortalCoordinator.DeploymentState.VALIDATED)
        assertTrue(
            assertThrows(IllegalStateException::class.java) {
                coordinator.preflight(
                    fixture.coordinates,
                    temporaryDirectory.resolve("mismatch"),
                    incompleteAttempts = 1,
                    statusAttempts = 1,
                    statusDelayMillis = 0L,
                )
            }.message.orEmpty().contains("differs from the staged publication"),
        )
    }

    @Test
    fun `response schema drift duplicate paths and extra bundle content fail closed`() {
        val fixture = fixture()
        val server = server(fixture)
        val coordinator = fixture.coordinator(server)

        server.omitDeployments = true
        assertTrue(
            assertThrows(IllegalStateException::class.java) {
                coordinator.preflight(fixture.coordinates, temporaryDirectory.resolve("missing-deployments"), incompleteAttempts = 1)
            }.message.orEmpty().contains("omitted array deployments"),
        )

        server.omitDeployments = false
        server.omitPageCount = true
        assertTrue(
            assertThrows(IllegalStateException::class.java) {
                coordinator.preflight(fixture.coordinates, temporaryDirectory.resolve("missing-page-count"), incompleteAttempts = 1)
            }.message.orEmpty().contains("omitted number pageCount"),
        )

        server.omitPageCount = false
        server.deploymentType = "SINGLE"
        assertTrue(
            assertThrows(IllegalStateException::class.java) {
                coordinator.preflight(fixture.coordinates, temporaryDirectory.resolve("single-deployment"), incompleteAttempts = 1)
            }.message.orEmpty().contains("not a release bundle"),
        )

        server.deploymentType = "BUNDLE"
        server.mode = Mode.DUPLICATE_FILE
        assertTrue(
            assertThrows(IllegalStateException::class.java) {
                coordinator.preflight(fixture.coordinates, temporaryDirectory.resolve("duplicate-file"), incompleteAttempts = 1)
            }.message.orEmpty().contains("duplicate deployment file path"),
        )

        server.mode = Mode.EXTRA_PATH
        assertTrue(
            assertThrows(IllegalStateException::class.java) {
                coordinator.preflight(fixture.coordinates, temporaryDirectory.resolve("extra-path"), incompleteAttempts = 1)
            }.message.orEmpty().contains("unexpected release files"),
        )
    }

    @Test
    fun `release contract requires nonempty safe coordinates before network access`() {
        val fixture = fixture()
        val server = server(fixture)

        val countFailure =
            assertThrows(IllegalStateException::class.java) {
                fixture.coordinator(server).preflight(
                    emptyList(),
                    temporaryDirectory.resolve("coordinate-count"),
                    incompleteAttempts = 1,
                )
            }
        assertTrue(countFailure.message.orEmpty().contains("at least one coordinate"))
        assertEquals(0, server.listRequestCount)

        val unsafeFailure =
            assertThrows(IllegalStateException::class.java) {
                fixture.coordinator(server).preflight(
                    listOf("..:strata-api:0.1.1"),
                    temporaryDirectory.resolve("unsafe-coordinate"),
                    incompleteAttempts = 1,
                )
            }
        assertTrue(unsafeFailure.message.orEmpty().contains("unsafe path segment"))
        assertEquals(0, server.listRequestCount)
    }

    @Test
    fun `verification discovers an ambiguous upload and waits for publication`() {
        val fixture = fixture()
        val server = server(fixture)
        server.hiddenListReads = 2
        server.statuses.addAll(
            listOf(
                MavenCentralPortalCoordinator.DeploymentState.PENDING,
                MavenCentralPortalCoordinator.DeploymentState.VALIDATING,
                MavenCentralPortalCoordinator.DeploymentState.PUBLISHED,
            ),
        )

        val receipt =
            fixture.coordinator(server).verifyUntilPublished(
                coordinateLines = fixture.coordinates,
                evidenceDirectory = temporaryDirectory.resolve("published"),
                discoveryAttempts = 3,
                discoveryDelayMillis = 0L,
                statusAttempts = 3,
                statusDelayMillis = 0L,
            )

        assertEquals(MavenCentralPortalCoordinator.DeploymentState.PUBLISHED, receipt.deploymentState)
        assertEquals(3, server.listRequestCount)
        assertEquals(3, server.statusRequestCount)
    }

    @Test
    fun `verification never rewrites after absent discovery or a terminal failure`() {
        val fixture = fixture()
        val server = server(fixture)
        server.mode = Mode.ABSENT
        val absent =
            assertThrows(IllegalStateException::class.java) {
                fixture.coordinator(server).verifyUntilPublished(
                    coordinateLines = fixture.coordinates,
                    evidenceDirectory = temporaryDirectory.resolve("missing"),
                    discoveryAttempts = 2,
                    discoveryDelayMillis = 0L,
                    statusAttempts = 1,
                    statusDelayMillis = 0L,
                )
            }
        assertTrue(absent.message.orEmpty().contains("no matching deployment"))

        server.mode = Mode.EXACT
        server.statuses.add(MavenCentralPortalCoordinator.DeploymentState.FAILED)
        val failed =
            assertThrows(IllegalStateException::class.java) {
                fixture.coordinator(server).verifyUntilPublished(
                    coordinateLines = fixture.coordinates,
                    evidenceDirectory = temporaryDirectory.resolve("terminal"),
                    discoveryAttempts = 1,
                    discoveryDelayMillis = 0L,
                    statusAttempts = 1,
                    statusDelayMillis = 0L,
                )
            }
        assertTrue(failed.message.orEmpty().contains("failed validation"))
    }

    @Test
    fun `task endpoint remains pinned to the official Portal`() {
        assertEquals(
            "https://central.sonatype.com/",
            MavenCentralPortalTask.validatePortalBaseUrl("https://central.sonatype.com"),
        )
        assertThrows(IllegalStateException::class.java) {
            MavenCentralPortalTask.validatePortalBaseUrl("https://example.com")
        }
        assertThrows(IllegalStateException::class.java) {
            MavenCentralPortalTask.validatePortalBaseUrl("https://central.sonatype.com.evil.example/")
        }
        assertThrows(IllegalStateException::class.java) {
            MavenCentralPortalTask.validatePortalBaseUrl("https://user@central.sonatype.com/")
        }
    }

    @Test
    fun `authenticated response failures never expose credentials or response bodies`() {
        val fixture = fixture()
        val server = server(fixture)
        val username = "credential-user"
        val password = "credential-password"
        server.listRejectionStatus = 401
        server.listRejectionBody = "server echoed $username:$password"

        val failure =
            assertThrows(IllegalStateException::class.java) {
                fixture.coordinator(server, username, password).preflight(
                    fixture.coordinates,
                    temporaryDirectory.resolve("credential-failure"),
                    incompleteAttempts = 1,
                )
            }

        assertTrue(failure.message.orEmpty().contains("HTTP 401"))
        assertFalse(failure.message.orEmpty().contains(username))
        assertFalse(failure.message.orEmpty().contains(password))
        assertFalse(failure.message.orEmpty().contains(server.listRejectionBody))
    }

    private fun fixture(artifacts: List<String> = listOf("strata-api")): Fixture {
        val repository = temporaryDirectory.resolve("repository")
        val coordinates =
            artifacts.map { artifact ->
                val coordinate = "dev.s7a.strata:$artifact:0.1.1"
                val directory = repository.resolve("dev/s7a/strata/$artifact/0.1.1")
                Files.createDirectories(directory)
                BASE_SUFFIXES.forEach { suffix ->
                    val file = directory.resolve("$artifact-0.1.1$suffix")
                    Files.writeString(file, "$coordinate$suffix", StandardCharsets.UTF_8)
                }
                coordinate
            }
        return Fixture(repository, coordinates)
    }

    private fun server(fixture: Fixture): MockCentralPortal = MockCentralPortal(fixture).also { server -> servers += server }

    private data class Fixture(
        val repository: Path,
        val coordinates: List<String>,
    ) {
        fun coordinator(
            server: MockCentralPortal,
            username: String = "user",
            password: String = "password",
        ): MavenCentralPortalCoordinator =
            MavenCentralPortalCoordinator(
                portalBaseUri = URI("${server.baseUrl}/"),
                username = username,
                password = password,
                localRepository = repository,
                requestTimeout = Duration.ofSeconds(2),
                retryBaseMillis = 0L,
                sleeper = {},
            )
    }

    private class MockCentralPortal(
        fixture: Fixture,
    ) : AutoCloseable {
        private val executor = Executors.newCachedThreadPool()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        private val exactFiles = linkedMapOf<String, ByteArray>()
        private val purls =
            fixture.coordinates.map { coordinate ->
                val (group, artifact, version) = coordinate.split(':')
                "pkg:maven/$group/$artifact@$version"
            }

        var mode: Mode = Mode.EXACT
        var hiddenListReads: Int = 0
        var listRequestCount: Int = 0
        var statusRequestCount: Int = 0
        var omitDeployments: Boolean = false
        var omitPageCount: Boolean = false
        var listRejectionStatus: Int? = null
        var listRejectionBody: String = "rejected"
        var deploymentType: String = "BUNDLE"
        val transientListStatuses: ArrayDeque<Int> = ArrayDeque()
        val statuses: ArrayDeque<MavenCentralPortalCoordinator.DeploymentState> = ArrayDeque()

        val baseUrl: String
            get() = "http://127.0.0.1:${server.address.port}"

        init {
            Files.walk(fixture.repository).use { paths ->
                paths.filter(Files::isRegularFile).forEach { path ->
                    val relativePath =
                        fixture.repository
                            .relativize(path)
                            .toString()
                            .replace('\\', '/')
                    exactFiles[relativePath] = Files.readAllBytes(path)
                    exactFiles["$relativePath.asc"] = "signature:$relativePath".toByteArray(StandardCharsets.UTF_8)
                }
            }
            exactFiles.keys.filter { path -> path.endsWith(".asc").not() }.toList().forEach { path ->
                val content = exactFiles[path] ?: error("Missing mock content for $path")
                CHECKSUMS.forEach { algorithm ->
                    exactFiles["$path.${algorithm.extension}"] = (content.hash(algorithm.messageDigestName) + "\n").toByteArray(StandardCharsets.UTF_8)
                }
            }
            server.executor = executor
            server.createContext("/api/v1/publisher/deployments/files") { exchange -> serveDeployments(exchange) }
            server.createContext("/api/v1/publisher/status") { exchange -> serveStatus(exchange) }
            server.createContext("/api/v1/publisher/deployment/") { exchange -> serveDownload(exchange) }
            server.start()
        }

        private fun serveDeployments(exchange: HttpExchange) {
            listRequestCount += 1
            val rejectionStatus = listRejectionStatus
            if (rejectionStatus != null) {
                respond(exchange, rejectionStatus, listRejectionBody)
                return
            }
            if (transientListStatuses.isEmpty().not()) {
                respond(exchange, transientListStatuses.removeFirst(), "transient")
                return
            }
            if (0 < hiddenListReads) {
                hiddenListReads -= 1
                respond(exchange, 200, inventory(emptyList()))
                return
            }
            if (mode == Mode.ABSENT) {
                respond(exchange, 200, inventory(emptyList()))
                return
            }
            val deployments =
                if (mode == Mode.DUPLICATE) {
                    listOf(deployment("deployment-1"), deployment("deployment-2"))
                } else {
                    listOf(deployment("deployment-1"))
                }
            respond(exchange, 200, inventory(deployments))
        }

        private fun serveStatus(exchange: HttpExchange) {
            statusRequestCount += 1
            val state = statuses.removeFirstOrNull() ?: state()
            respond(
                exchange,
                200,
                JsonOutput.toJson(
                    mapOf(
                        "deploymentId" to "deployment-1",
                        "deploymentName" to "dev.s7a.strata-0.1.1",
                        "deploymentState" to state.wireValue,
                        "purls" to purls,
                    ),
                ),
            )
        }

        private fun serveDownload(exchange: HttpExchange) {
            val prefix = "/api/v1/publisher/deployment/deployment-1/download/"
            val relativePath = exchange.requestURI.path.removePrefix(prefix)
            var bytes = exactFiles[relativePath]
            if (mode == Mode.MISMATCH && relativePath.endsWith("strata-api-0.1.1.jar")) {
                bytes = "different bytes".toByteArray(StandardCharsets.UTF_8)
            }
            if (bytes == null) {
                respond(exchange, 404, "missing")
            } else {
                respond(exchange, 200, bytes)
            }
        }

        private fun deployment(id: String): Map<String, Any> {
            val files =
                exactFiles.entries
                    .filterIndexed { index, _ -> mode != Mode.PARTIAL || index != exactFiles.size - 1 }
                    .map { (path, bytes) ->
                        mapOf(
                            "relativePath" to path,
                            "fileName" to path.substringAfterLast('/'),
                            "fileSize" to bytes.size,
                            "fileTimestamp" to 1L,
                        )
                    }.toMutableList()
            if (mode == Mode.DUPLICATE_FILE) files += files.first()
            if (mode == Mode.EXTRA_PATH) {
                files +=
                    mapOf(
                        "relativePath" to "org/example/unrelated/1.0/unrelated-1.0.jar",
                        "fileName" to "unrelated-1.0.jar",
                        "fileSize" to 1,
                        "fileTimestamp" to 1L,
                    )
            }
            return linkedMapOf(
                "deploymentId" to id,
                "deploymentName" to "dev.s7a.strata-0.1.1",
                "deploymentState" to state().wireValue,
                "deploymentType" to deploymentType,
                "createTimestamp" to 1L,
                "purls" to purls,
                "deploymentFiles" to files,
            )
        }

        private fun state(): MavenCentralPortalCoordinator.DeploymentState =
            if (mode == Mode.FAILED) {
                MavenCentralPortalCoordinator.DeploymentState.FAILED
            } else {
                MavenCentralPortalCoordinator.DeploymentState.PENDING
            }

        private fun inventory(deployments: List<Map<String, Any>>): String =
            JsonOutput.toJson(
                linkedMapOf<String, Any>(
                    "page" to 0,
                    "pageSize" to 500,
                    "totalResultCount" to deployments.size,
                ).apply {
                    if (omitDeployments.not()) put("deployments", deployments)
                    if (omitPageCount.not()) put("pageCount", 1)
                },
            )

        private fun respond(
            exchange: HttpExchange,
            status: Int,
            body: String,
        ) {
            respond(exchange, status, body.toByteArray(StandardCharsets.UTF_8))
        }

        private fun respond(
            exchange: HttpExchange,
            status: Int,
            body: ByteArray,
        ) {
            exchange.sendResponseHeaders(status, body.size.toLong())
            exchange.responseBody.use { output -> output.write(body) }
        }

        override fun close() {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    private enum class Mode {
        ABSENT,
        EXACT,
        DUPLICATE,
        PARTIAL,
        FAILED,
        MISMATCH,
        DUPLICATE_FILE,
        EXTRA_PATH,
    }

    private data class Checksum(
        val extension: String,
        val messageDigestName: String,
    )

    companion object {
        private val BASE_SUFFIXES = listOf(".pom", ".module", ".jar", "-sources.jar", "-javadoc.jar")
        private val CHECKSUMS =
            listOf(
                Checksum("md5", "MD5"),
                Checksum("sha1", "SHA-1"),
                Checksum("sha256", "SHA-256"),
                Checksum("sha512", "SHA-512"),
            )

        private fun ByteArray.hash(algorithm: String): String =
            MessageDigest
                .getInstance(algorithm)
                .digest(this)
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}
