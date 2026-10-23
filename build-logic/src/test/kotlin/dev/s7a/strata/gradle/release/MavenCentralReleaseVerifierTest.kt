package dev.s7a.strata.gradle.release

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
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
import java.time.Duration
import java.util.concurrent.Executors

/**
 * Verifies immutable Maven release reconciliation against an in-process repository server.
 */
internal class MavenCentralReleaseVerifierTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    private val servers = mutableListOf<MockCentralRepository>()

    /**
     * Releases the local HTTP servers owned by this test.
     */
    @AfterEach
    fun closeServers() {
        servers.forEach(MockCentralRepository::close)
    }

    @Test
    fun `non JVM artifacts are included in exact remote evidence`() {
        val fixture = fixture()
        val coordinate = fixture.coordinates.first()
        val (group, artifact, version) = coordinate.split(':')
        val directory = fixture.repository.resolve("${group.replace('.', '/')}/$artifact/$version")
        val main = directory.resolve("$artifact-$version.jar")
        Files.move(main, directory.resolve("$artifact-$version.klib"))
        Files.move(directory.resolve("$artifact-$version.jar.asc"), directory.resolve("$artifact-$version.klib.asc"))
        Files.writeString(directory.resolve("$artifact-$version-plugin.jar"), "installable plugin")
        Files.writeString(directory.resolve("$artifact-$version-plugin.jar.asc"), "plugin signature")
        val entries =
            fixture.coordinates.flatMap { entry ->
                val identity = entry.substringBeforeLast(':')
                val suffixes = if (entry == coordinate) BASE_SUFFIXES.minus(".jar") + listOf(".klib", "-plugin.jar") else BASE_SUFFIXES
                suffixes.map { suffix -> "$identity:$suffix" }
            }
        val server = server(fixture)
        val verifier = fixture.verifier(server, entries)
        val receipt = verifier.preflight(fixture.coordinates)
        assertEquals(entries.size * 2, receipt.verifiedFileCount)
        assertEquals(entries.size * 4, receipt.verifiedChecksumCount)
        val staged = verifier.stageCanonicalPublicationEvidence(fixture.coordinates, temporaryDirectory.resolve("variable-evidence"))
        assertTrue(staged.any { it.baseRelativePath.endsWith("-plugin.jar") })
        assertTrue(staged.any { it.baseRelativePath.endsWith(".klib") })
        Files.delete(directory.resolve("$artifact-$version-plugin.jar"))
        assertThrows(IllegalStateException::class.java) {
            verifier.preflight(fixture.coordinates)
        }
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
        assertEquals(fixture.coordinates.size, exact.coordinateCount)
        assertEquals(fixture.coordinates.size * BASE_SUFFIXES.size * 2, exact.verifiedFileCount)
        assertEquals(fixture.coordinates.size * BASE_SUFFIXES.size * CHECKSUM_COUNT_PER_BASE, exact.verifiedChecksumCount)
        assertEquals(setOf("md5", "sha1", "sha256", "sha512"), server.requestedChecksumExtensions)
        assertTrue(server.requestedChecksumContentPaths.none { path -> path.endsWith(".asc") })
    }

    @Test
    fun `release inventory size is derived from the supplied coordinates`() {
        val fixture = fixture(listOf("1.20", "26.2"))
        val server = server(fixture)
        val verifier = fixture.verifier(server)

        val receipt = verifier.preflight(fixture.coordinates)
        val expectedCoordinateCount = fixture.coordinates.size

        assertEquals(MavenCentralReleaseVerifier.State.EXACT, receipt.state)
        assertEquals(expectedCoordinateCount, receipt.coordinateCount)
        assertEquals(expectedCoordinateCount * BASE_SUFFIXES.size * 2, receipt.verifiedFileCount)
        assertEquals(expectedCoordinateCount * BASE_SUFFIXES.size * CHECKSUM_COUNT_PER_BASE, receipt.verifiedChecksumCount)
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
        server.hiddenPomReads = fixture.coordinates.size

        val receipt =
            fixture.verifier(server).verify(
                coordinateLines = fixture.coordinates,
                maximumAttempts = 3,
                pollDelayMillis = 0L,
            )

        assertEquals(MavenCentralReleaseVerifier.State.EXACT, receipt.state)
        assertTrue(fixture.coordinates.size < server.pomReadCount)
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

        assertEquals(fixture.coordinates.size * BASE_SUFFIXES.size, signedFiles.size)
        assertEquals(
            fixture.coordinates.count { value -> value.contains(":strata-runtime-minecraft-fabric-") },
            signedFiles.count(MavenCentralReleaseVerifier.SignedPublicationFile::githubDistributionSignature),
        )
        val fileName = "$artifact-$version.jar.asc"
        val relativePath = "${group.replace('.', '/')}/$artifact/$version/$fileName"
        assertTrue(Files.readAllBytes(output.resolve(relativePath)).contentEquals(server.remoteBytes(relativePath)))
    }

    @Test
    fun `final verification stages identical bytes with half the duplicate read requests`() {
        val fixture = fixture()
        val server = server(fixture)
        val verifier = fixture.verifier(server)
        verifier.verify(fixture.coordinates)
        verifier.stageCanonicalPublicationEvidence(fixture.coordinates, temporaryDirectory.resolve("baseline-evidence"))
        val baselineRequests = server.requestCount
        server.requestCount = 0
        val output = temporaryDirectory.resolve("single-pass-evidence")

        val receipt = verifier.verify(fixture.coordinates, outputDirectory = output)

        val baseFileCount = fixture.coordinates.size * BASE_SUFFIXES.size
        assertEquals(baseFileCount * (CHECKSUM_COUNT_PER_BASE + 2), server.requestCount)
        assertEquals(baselineRequests, server.requestCount * 2)
        assertEquals(baseFileCount, receipt.signedFiles.size)
        assertEquals(baseFileCount * 2, receipt.verifiedFileCount)
        receipt.signedFiles.forEach { file ->
            listOf(file.baseRelativePath, file.signatureRelativePath).forEach { path ->
                assertTrue(Files.readAllBytes(output.resolve(path)).contentEquals(server.remoteBytes(path)))
            }
        }
    }

    @Test
    fun `failed and cancelled verification discards partially staged bytes`() {
        val fixture = fixture()
        val server = server(fixture)
        val output = temporaryDirectory.resolve("partial-evidence")
        for (mode in listOf(Mode.MISSING_SIGNATURE, Mode.MISSING_BASE_CHECKSUM, Mode.MISMATCH)) {
            server.mode = mode
            assertThrows(IllegalStateException::class.java) {
                fixture.verifier(server).verify(fixture.coordinates, maximumAttempts = 1, outputDirectory = output)
            }
            assertFalse(Files.exists(output))
        }

        server.mode = Mode.MISSING_SIGNATURE
        val verifier =
            MavenCentralReleaseVerifier(
                localRepository = fixture.repository,
                repositoryBaseUri = URI("${server.baseUrl}/maven2/"),
                sleeper = { throw InterruptedException("cancelled propagation wait") },
            )
        assertThrows(InterruptedException::class.java) {
            verifier.verify(fixture.coordinates, maximumAttempts = 2, outputDirectory = output)
        }
        assertFalse(Files.exists(output))
    }

    @Test
    fun `staging retries transient reads and refuses existing evidence`() {
        val fixture = fixture()
        val server = server(fixture)
        server.transientStatuses.addAll(listOf(429, 503))
        val output = temporaryDirectory.resolve("retried-evidence")
        val receipt = fixture.verifier(server).verify(fixture.coordinates, outputDirectory = output)
        assertEquals(MavenCentralReleaseVerifier.State.EXACT, receipt.state)
        assertEquals(fixture.coordinates.size * BASE_SUFFIXES.size * (CHECKSUM_COUNT_PER_BASE + 2) + 2, server.requestCount)
        val originalRequests = server.requestCount
        val originalFiles = Files.walk(output).use { it.filter(Files::isRegularFile).toList() }
        assertThrows(IllegalStateException::class.java) {
            fixture.verifier(server).verify(fixture.coordinates, outputDirectory = output)
        }
        assertEquals(originalRequests, server.requestCount)
        assertTrue(originalFiles.all(Files::isRegularFile))
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

    private fun fixture(gameVersions: List<String> = GAME_VERSIONS): Fixture {
        val repository = temporaryDirectory.resolve("repository")
        val coordinates =
            listOf(
                "dev.s7a.strata:strata-api:0.1.1",
                "dev.s7a.strata:strata-runtime-core:0.1.1",
                "dev.s7a.strata:strata-runtime-headless:0.1.1",
                "dev.s7a.strata:strata-runtime-minecraft:0.1.1",
                "dev.s7a.strata:strata-runtime-minecraft-fonts-lwjgl:0.1.1",
            ) +
                gameVersions.map { gameVersion ->
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
        fun verifier(
            server: MockCentralRepository,
            publicationFiles: List<String>? = null,
        ): MavenCentralReleaseVerifier =
            MavenCentralReleaseVerifier(
                localRepository = repository,
                publicationFiles = publicationFiles,
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
            if (isUnavailable(relativePath)) {
                respond(exchange, 404, ByteArray(0))
                return
            }

            val checksum = MavenFixtureChecksum.entries.firstOrNull { relativePath.endsWith(".${it.extension}") }
            val contentPath =
                if (checksum == null) {
                    relativePath
                } else {
                    requestedChecksumExtensions += checksum.extension
                    val path = relativePath.removeSuffix(".${checksum.extension}")
                    requestedChecksumContentPaths += path
                    path
                }
            if (checksum != null && contentPath.endsWith(".asc") && mode != Mode.ORPHAN_SIGNATURE_MD5) {
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
                if (checksum == null) {
                    bytes
                } else {
                    (checksum.digest(bytes) + "\n").toByteArray(StandardCharsets.UTF_8)
                }
            respond(exchange, 200, responseBytes)
        }

        /**
         * Resolves which paths the selected incomplete-publication scenario hides.
         */
        private fun isUnavailable(relativePath: String): Boolean =
            when (mode) {
                Mode.ABSENT -> true
                Mode.ORPHAN -> relativePath.endsWith("strata-api-0.1.1.jar").not()
                Mode.ORPHAN_MD5 -> relativePath.endsWith("strata-api-0.1.1.jar.md5").not()
                Mode.ORPHAN_SIGNATURE_MD5 -> relativePath.endsWith("strata-api-0.1.1.jar.asc.md5").not()
                Mode.PARTIAL -> relativePath.startsWith("dev/s7a/strata/strata-api/").not()
                Mode.MISSING_SIGNATURE -> relativePath.endsWith("strata-api-0.1.1.jar.asc")
                Mode.MISSING_BASE_CHECKSUM -> relativePath.endsWith("strata-api-0.1.1.jar.sha512")
                Mode.EXACT, Mode.MISMATCH -> false
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

    /**
     * Defines the complete artifact and checksum inventory served by the repository fixture.
     */
    companion object {
        private const val CHECKSUM_COUNT_PER_BASE = 4
        private val BASE_SUFFIXES = listOf(".pom", ".module", ".jar", "-sources.jar", "-javadoc.jar")
        private val GAME_VERSIONS =
            listOf(
                "1.20",
                "1.21",
                "26.2",
            )
    }
}
