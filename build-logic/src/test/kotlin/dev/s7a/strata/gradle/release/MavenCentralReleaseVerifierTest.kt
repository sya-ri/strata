package dev.s7a.strata.gradle.release

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
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

/** Verifies immutable Maven release reconciliation against an in-process repository server. */
internal class MavenCentralReleaseVerifierTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    private val servers = mutableListOf<MockCentralRepository>()

    @AfterEach
    fun closeServers() {
        servers.forEach(MockCentralRepository::close)
    }

    @Test
    fun `preflight distinguishes wholly absent and byte-exact releases`() {
        val fixture = fixture()
        val server = server(fixture)
        val verifier = fixture.verifier(server)

        server.mode = Mode.ABSENT
        assertEquals(MavenCentralReleaseVerifier.State.ABSENT, verifier.preflight(fixture.coordinates).state)

        server.mode = Mode.EXACT
        server.requestedChecksumExtensions.clear()
        val exact = verifier.preflight(fixture.coordinates)
        assertEquals(MavenCentralReleaseVerifier.State.EXACT, exact.state)
        assertEquals(25, exact.coordinateCount)
        assertEquals(250, exact.verifiedFileCount)
        assertEquals(500, exact.verifiedChecksumCount)
        assertEquals(setOf("md5", "sha1", "sha256", "sha512"), server.requestedChecksumExtensions)
        assertTrue(server.requestedChecksumContentPaths.none { path -> path.endsWith(".asc") })
    }

    @Test
    fun `partial and differing immutable releases fail closed`() {
        val fixture = fixture()
        val server = server(fixture)
        val verifier = fixture.verifier(server)

        server.mode = Mode.PARTIAL
        val partial = assertThrows(IllegalStateException::class.java) { verifier.preflight(fixture.coordinates) }
        assertTrue(partial.message.orEmpty().contains("partial immutable release"))

        server.mode = Mode.ORPHAN
        val orphan = assertThrows(IllegalStateException::class.java) { verifier.preflight(fixture.coordinates) }
        assertTrue(orphan.message.orEmpty().contains("partial immutable release"))

        server.mode = Mode.ORPHAN_MD5
        val checksumOrphan = assertThrows(IllegalStateException::class.java) { verifier.preflight(fixture.coordinates) }
        assertTrue(checksumOrphan.message.orEmpty().contains("partial immutable release"))

        server.mode = Mode.ORPHAN_SIGNATURE_MD5
        val signatureChecksumOrphan = assertThrows(IllegalStateException::class.java) { verifier.preflight(fixture.coordinates) }
        assertTrue(signatureChecksumOrphan.message.orEmpty().contains("partial immutable release"))

        server.mode = Mode.MISSING_BASE_CHECKSUM
        val missingBaseChecksum = assertThrows(IllegalStateException::class.java) { verifier.preflight(fixture.coordinates) }
        assertTrue(missingBaseChecksum.message.orEmpty().contains("partial immutable release"))

        server.mode = Mode.MISSING_SIGNATURE
        val missingSignature = assertThrows(IllegalStateException::class.java) { verifier.preflight(fixture.coordinates) }
        assertTrue(missingSignature.message.orEmpty().contains("partial immutable release"))

        server.mode = Mode.MISMATCH
        val mismatch = assertThrows(IllegalStateException::class.java) { verifier.preflight(fixture.coordinates) }
        assertTrue(mismatch.message.orEmpty().contains("differs from the staged publication"))
    }

    @Test
    fun `reads retry rate limits and server failures without accepting different bytes`() {
        val fixture = fixture()
        val server = server(fixture)
        server.transientStatuses.addAll(listOf(429, 503))

        val receipt = fixture.verifier(server).preflight(fixture.coordinates)

        assertEquals(MavenCentralReleaseVerifier.State.EXACT, receipt.state)
        assertTrue(2 < server.requestCount)
    }

    @Test
    fun `verification polls boundedly until all coordinates propagate`() {
        val fixture = fixture()
        val server = server(fixture)
        server.hiddenPomReads = 25

        val receipt =
            fixture.verifier(server).verify(
                coordinateLines = fixture.coordinates,
                maximumAttempts = 3,
                pollDelayMillis = 0L,
            )

        assertEquals(MavenCentralReleaseVerifier.State.EXACT, receipt.state)
        assertTrue(49 < server.pomReadCount)
    }

    @Test
    fun `canonical Fabric signatures come from Central across a differently timed local rerun`() {
        val fixture = fixture()
        val server = server(fixture)
        val coordinate = fixture.coordinates.single { value -> value.contains("minecraft-fabric-1.20:") }
        val (group, artifact, version) = coordinate.split(':')
        val localSignature =
            fixture.repository
                .resolve(group.replace('.', '/'))
                .resolve(artifact)
                .resolve(version)
                .resolve("$artifact-$version.jar.asc")
        Files.writeString(localSignature, "new time-varying local signature", StandardCharsets.UTF_8)

        assertEquals(MavenCentralReleaseVerifier.State.EXACT, fixture.verifier(server).preflight(fixture.coordinates).state)
        val output = temporaryDirectory.resolve("canonical-signatures")
        val signedFiles = fixture.verifier(server).stageCanonicalPublicationEvidence(fixture.coordinates, output)

        assertEquals(125, signedFiles.size)
        assertEquals(21, signedFiles.count(MavenCentralReleaseVerifier.SignedPublicationFile::githubDistributionSignature))
        val fileName = "$artifact-$version.jar.asc"
        val relativePath = "${group.replace('.', '/')}/$artifact/$version/$fileName"
        assertTrue(Files.readAllBytes(output.resolve(relativePath)).contentEquals(server.remoteBytes(relativePath)))
    }

    @Test
    fun `task endpoint is pinned to the public Central repository`() {
        assertEquals(
            "https://repo1.maven.org/maven2/",
            MavenCentralReleaseTask.validateRepositoryBaseUrl("https://repo1.maven.org/maven2"),
        )
        assertThrows(IllegalStateException::class.java) {
            MavenCentralReleaseTask.validateRepositoryBaseUrl("https://example.com/maven2")
        }
    }

    private fun fixture(): Fixture {
        val repository = temporaryDirectory.resolve("repository")
        val coordinates =
            listOf(
                "dev.s7a.strata:strata-api:0.1.1",
                "dev.s7a.strata:strata-runtime-core:0.1.1",
                "dev.s7a.strata:strata-runtime-headless:0.1.1",
                "dev.s7a.strata:strata-runtime-minecraft:0.1.1",
            ) +
                GAME_VERSIONS.map { gameVersion ->
                    "dev.s7a.strata:strata-runtime-minecraft-fabric-$gameVersion:0.1.1"
                }
        coordinates.forEach { coordinate ->
            val (group, artifact, version) = coordinate.split(':')
            val directory = repository.resolve(group.replace('.', '/')).resolve(artifact).resolve(version)
            Files.createDirectories(directory)
            BASE_SUFFIXES.forEach { suffix ->
                val file = directory.resolve("$artifact-$version$suffix")
                Files.writeString(file, "$coordinate$suffix", StandardCharsets.UTF_8)
                Files.writeString(file.resolveSibling("${file.fileName}.asc"), "signature:$coordinate$suffix", StandardCharsets.UTF_8)
            }
        }
        return Fixture(repository, coordinates)
    }

    private fun server(fixture: Fixture): MockCentralRepository = MockCentralRepository(fixture).also { server -> servers += server }

    private data class Fixture(
        val repository: Path,
        val coordinates: List<String>,
    ) {
        fun verifier(server: MockCentralRepository): MavenCentralReleaseVerifier =
            MavenCentralReleaseVerifier(
                localRepository = repository,
                repositoryBaseUri = URI("${server.baseUrl}/maven2/"),
                requestTimeout = Duration.ofSeconds(2),
                retryBaseMillis = 0L,
                sleeper = {},
            )
    }

    private class MockCentralRepository(
        private val fixture: Fixture,
    ) : AutoCloseable {
        private val executor = Executors.newCachedThreadPool()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        private val remoteFiles: Map<String, ByteArray> =
            Files.walk(fixture.repository).use { paths ->
                paths
                    .filter(Files::isRegularFile)
                    .toList()
                    .associate { path ->
                        fixture.repository
                            .relativize(path)
                            .toString()
                            .replace('\\', '/') to Files.readAllBytes(path)
                    }
            }

        var mode: Mode = Mode.EXACT
        var hiddenPomReads: Int = 0
        var pomReadCount: Int = 0
        var requestCount: Int = 0
        val requestedChecksumExtensions: MutableSet<String> = linkedSetOf()
        val requestedChecksumContentPaths: MutableSet<String> = linkedSetOf()
        val transientStatuses: ArrayDeque<Int> = ArrayDeque()

        val baseUrl: String
            get() = "http://127.0.0.1:${server.address.port}"

        init {
            server.executor = executor
            server.createContext("/maven2/") { exchange -> serve(exchange) }
            server.start()
        }

        fun remoteBytes(relativePath: String): ByteArray = remoteFiles[relativePath]?.copyOf() ?: error("No remote fixture exists for $relativePath")

        private fun serve(exchange: HttpExchange) {
            requestCount += 1
            if (transientStatuses.isEmpty().not()) {
                respond(exchange, transientStatuses.removeFirst(), "transient".toByteArray(StandardCharsets.UTF_8))
                return
            }
            val relativePath = exchange.requestURI.path.removePrefix("/maven2/")
            if (relativePath.endsWith(".pom")) {
                pomReadCount += 1
                if (0 < hiddenPomReads) {
                    hiddenPomReads -= 1
                    respond(exchange, 404, ByteArray(0))
                    return
                }
            }
            if (mode == Mode.ABSENT) {
                respond(exchange, 404, ByteArray(0))
                return
            }
            if (mode == Mode.ORPHAN && relativePath.endsWith("strata-api-0.1.1.jar").not()) {
                respond(exchange, 404, ByteArray(0))
                return
            }
            if (mode == Mode.ORPHAN_MD5 && relativePath.endsWith("strata-api-0.1.1.jar.md5").not()) {
                respond(exchange, 404, ByteArray(0))
                return
            }
            if (mode == Mode.ORPHAN_SIGNATURE_MD5 && relativePath.endsWith("strata-api-0.1.1.jar.asc.md5").not()) {
                respond(exchange, 404, ByteArray(0))
                return
            }
            if (mode == Mode.PARTIAL && relativePath.startsWith("dev/s7a/strata/strata-api/").not()) {
                respond(exchange, 404, ByteArray(0))
                return
            }
            if (mode == Mode.MISSING_SIGNATURE && relativePath.endsWith("strata-api-0.1.1.jar.asc")) {
                respond(exchange, 404, ByteArray(0))
                return
            }
            if (mode == Mode.MISSING_BASE_CHECKSUM && relativePath.endsWith("strata-api-0.1.1.jar.sha512")) {
                respond(exchange, 404, ByteArray(0))
                return
            }

            val checksumAlgorithm =
                when {
                    relativePath.endsWith(".md5") -> "MD5".also { requestedChecksumExtensions += "md5" }
                    relativePath.endsWith(".sha1") -> "SHA-1".also { requestedChecksumExtensions += "sha1" }
                    relativePath.endsWith(".sha256") -> "SHA-256".also { requestedChecksumExtensions += "sha256" }
                    relativePath.endsWith(".sha512") -> "SHA-512".also { requestedChecksumExtensions += "sha512" }
                    else -> null
                }
            val contentPath =
                when (checksumAlgorithm) {
                    "MD5" -> relativePath.removeSuffix(".md5")
                    "SHA-1" -> relativePath.removeSuffix(".sha1")
                    "SHA-256" -> relativePath.removeSuffix(".sha256")
                    "SHA-512" -> relativePath.removeSuffix(".sha512")
                    else -> relativePath
                }
            if (checksumAlgorithm != null) requestedChecksumContentPaths += contentPath
            if (checksumAlgorithm != null && contentPath.endsWith(".asc") && mode != Mode.ORPHAN_SIGNATURE_MD5) {
                respond(exchange, 404, ByteArray(0))
                return
            }
            val storedBytes = remoteFiles[contentPath]
            if (storedBytes == null) {
                respond(exchange, 404, ByteArray(0))
                return
            }
            var bytes = storedBytes
            if (mode == Mode.MISMATCH && contentPath.endsWith("strata-api-0.1.1.jar")) {
                bytes = "different immutable bytes".toByteArray(StandardCharsets.UTF_8)
            }
            val responseBytes =
                if (checksumAlgorithm == null) {
                    bytes
                } else {
                    (bytes.hash(checksumAlgorithm) + "\n").toByteArray(StandardCharsets.UTF_8)
                }
            respond(exchange, 200, responseBytes)
        }

        private fun respond(
            exchange: HttpExchange,
            status: Int,
            bytes: ByteArray,
        ) {
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { output -> output.write(bytes) }
        }

        override fun close() {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    private enum class Mode {
        ABSENT,
        ORPHAN,
        ORPHAN_MD5,
        ORPHAN_SIGNATURE_MD5,
        PARTIAL,
        MISSING_BASE_CHECKSUM,
        MISSING_SIGNATURE,
        EXACT,
        MISMATCH,
    }

    companion object {
        private val BASE_SUFFIXES = listOf(".pom", ".module", ".jar", "-sources.jar", "-javadoc.jar")
        private val GAME_VERSIONS =
            listOf(
                "1.20",
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

        private fun ByteArray.hash(algorithm: String): String = MessageDigest.getInstance(algorithm).digest(this).joinToString("") { byte -> "%02x".format(byte) }
    }
}
