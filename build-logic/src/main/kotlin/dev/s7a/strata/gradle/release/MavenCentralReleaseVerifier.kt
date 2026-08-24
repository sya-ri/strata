package dev.s7a.strata.gradle.release

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration

/**
 * Compares one immutable Maven release matrix with local staged publications and Maven Central.
 *
 * The verifier owns no credentials or mutable remote state and may be called from one task thread.
 * It compares every deterministic base artifact with local bytes and validates each published signature and MD5, SHA-1, SHA-256, and SHA-512 checksum before reporting an exact existing release.
 * Network timeouts, rate limits, and server failures use a bounded retry budget; missing, partial, and differing releases never become a successful receipt.
 */
internal class MavenCentralReleaseVerifier(
    private val localRepository: Path,
    repositoryBaseUri: URI,
    private val httpClient: HttpClient =
        HttpClient
            .newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(30))
            .build(),
    private val requestTimeout: Duration = Duration.ofSeconds(30),
    private val retryBaseMillis: Long = 250L,
    private val sleeper: (Long) -> Unit = Thread::sleep,
) {
    private val repositoryBase = repositoryBaseUri.toString().let { value -> if (value.endsWith('/')) value else "$value/" }

    init {
        require(repositoryBaseUri.isAbsolute) { "The Maven repository base URI must be absolute." }
        require(0L <= retryBaseMillis) { "The Maven retry delay must not be negative." }
    }

    /**
     * Reads the complete remote matrix without mutation and returns whether a release is wholly absent or byte-exact.
     *
     * @param coordinateLines canonical `group:artifact:version` entries owned by the tracked release matrix.
     * @return immutable evidence for an absent or exact 24-coordinate release.
     * @throws IllegalStateException when local inputs are incomplete or Maven Central is partial, inconsistent, or different.
     */
    internal fun preflight(coordinateLines: List<String>): Receipt {
        val coordinates = parseCoordinates(coordinateLines)
        val inspection = inspect(coordinates)
        return when (inspection.state) {
            State.ABSENT -> Receipt(State.ABSENT, coordinates.size, 0, 0)
            State.EXACT ->
                Receipt(
                    State.EXACT,
                    coordinates.size,
                    inspection.verifiedFileCount,
                    inspection.verifiedFileCount * ChecksumAlgorithm.entries.size,
                )
            State.PARTIAL -> error(
                "Maven Central contains a partial immutable release (${inspection.presentCoordinateCount}/${coordinates.size} coordinates); refusing publication.",
            )
        }
    }

    /**
     * Polls for bounded Maven Central propagation and returns only after all staged bytes and checksum metadata match.
     *
     * @param coordinateLines canonical `group:artifact:version` entries owned by the tracked release matrix.
     * @param maximumAttempts total remote inspections permitted, including the first read.
     * @param pollDelayMillis delay between incomplete propagation reads.
     * @return immutable exact-match evidence.
     * @throws IllegalStateException on local input errors, remote byte mismatch, or exhausted propagation attempts.
     */
    internal fun verify(
        coordinateLines: List<String>,
        maximumAttempts: Int = DEFAULT_VERIFICATION_ATTEMPTS,
        pollDelayMillis: Long = DEFAULT_VERIFICATION_DELAY_MILLIS,
    ): Receipt {
        require(0 < maximumAttempts) { "Maven Central verification requires at least one attempt." }
        require(0L <= pollDelayMillis) { "The Maven Central poll delay must not be negative." }
        val coordinates = parseCoordinates(coordinateLines)
        for (attempt in 1..maximumAttempts) {
            val inspection = inspect(coordinates)
            if (inspection.state == State.EXACT) {
                return Receipt(
                    State.EXACT,
                    coordinates.size,
                    inspection.verifiedFileCount,
                    inspection.verifiedFileCount * ChecksumAlgorithm.entries.size,
                )
            }
            if (attempt < maximumAttempts) sleeper(pollDelayMillis)
        }
        error("Maven Central did not expose the exact 24-coordinate release after $maximumAttempts inspections.")
    }

    /**
     * Stages all 120 canonical remote publication files and detached signatures after exact Central verification.
     *
     * Local signatures are deliberately ignored because OpenPGP creation timestamps make them unstable across idempotent reruns.
     * The caller owns and clears [outputDirectory] before invocation.
     *
     * @param coordinateLines canonical 24-coordinate release inventory.
     * @param outputDirectory task-owned directory that mirrors the canonical Maven repository paths.
     * @return exact signed-file pairs, including the 20 Fabric main-JAR signatures selected for GitHub.
     * @throws IllegalStateException when the publication matrix is incomplete or remote content differs or is missing.
     */
    internal fun stageCanonicalPublicationEvidence(
        coordinateLines: List<String>,
        outputDirectory: Path,
    ): List<SignedPublicationFile> {
        val coordinates = parseCoordinates(coordinateLines)
        Files.createDirectories(outputDirectory)
        val staged =
            coordinates.flatMap { coordinate ->
                BASE_SUFFIXES.map { suffix ->
                    val basePath = coordinate.remotePath(suffix)
                    val remoteBase = readOptional(basePath) ?: error("Maven Central publication file is missing: $basePath")
                    val localBase = coordinate.localPath(localRepository, suffix).toFile().readBytes()
                    check(remoteBase.contentEquals(localBase)) {
                        "Maven Central publication file differs from the staged publication: $basePath"
                    }
                    check(verifyChecksums(basePath, remoteBase)) {
                        "Maven Central publication checksums are incomplete: $basePath"
                    }
                    val signaturePath = "$basePath.asc"
                    val signature = readOptional(signaturePath)
                        ?: error("Maven Central publication signature is missing: $signaturePath")
                    check(verifyChecksums(signaturePath, signature)) {
                        "Maven Central publication signature checksums are incomplete: $signaturePath"
                    }
                    val baseOutput = outputDirectory.resolve(basePath)
                    Files.createDirectories(baseOutput.parent)
                    Files.write(baseOutput, remoteBase)
                    Files.write(outputDirectory.resolve(signaturePath), signature)
                    SignedPublicationFile(
                        baseRelativePath = basePath,
                        signatureRelativePath = signaturePath,
                        githubDistributionSignature =
                            coordinate.artifact.startsWith(FABRIC_ARTIFACT_PREFIX) && suffix == MAIN_JAR_SUFFIX,
                    )
                }
            }
        check(staged.size == EXPECTED_SIGNED_FILE_COUNT) {
            "Canonical Central evidence must contain exactly $EXPECTED_SIGNED_FILE_COUNT signed publication files."
        }
        check(staged.count(SignedPublicationFile::githubDistributionSignature) == EXPECTED_FABRIC_COORDINATE_COUNT) {
            "Canonical GitHub signatures require exactly $EXPECTED_FABRIC_COORDINATE_COUNT Fabric runtime main JARs."
        }
        return staged
    }

    private fun parseCoordinates(lines: List<String>): List<Coordinate> {
        val coordinates = lines.filter(String::isNotBlank).map(Coordinate::parse)
        check(coordinates.size == EXPECTED_COORDINATE_COUNT) {
            "Maven Central release verification requires exactly $EXPECTED_COORDINATE_COUNT coordinates."
        }
        check(coordinates.distinct().size == coordinates.size) { "Maven Central release coordinates must be unique." }
        check(coordinates.map(Coordinate::version).distinct().size == 1) {
            "Maven Central release coordinates must use one exact release version."
        }
        coordinates.forEach { coordinate ->
            expectedBaseFiles(coordinate).forEach { file ->
                check(file.path.toFile().isFile && 0L < file.path.toFile().length()) {
                    "Local staged publication is missing or empty: ${file.path}"
                }
            }
        }
        return coordinates
    }

    private fun inspect(coordinates: List<Coordinate>): Inspection {
        val pomBytes = linkedMapOf<Coordinate, ByteArray?>()
        coordinates.forEach { coordinate ->
            pomBytes[coordinate] = readOptional(coordinate.remotePath(POM_SUFFIX))
        }
        val presentCoordinateCount = pomBytes.values.count { bytes -> bytes != null }
        if (presentCoordinateCount == 0) {
            val orphanedRemoteFile =
                coordinates.asSequence().flatMap { coordinate ->
                    REMOTE_PUBLICATION_SUFFIXES.asSequence().map(coordinate::remotePath)
                }.firstOrNull { remotePath -> readOptional(remotePath) != null }
            return if (orphanedRemoteFile == null) {
                Inspection(State.ABSENT, 0, 0)
            } else {
                Inspection(State.PARTIAL, 0, 0)
            }
        }
        if (presentCoordinateCount < coordinates.size) {
            return Inspection(State.PARTIAL, presentCoordinateCount, 0)
        }

        var verifiedFileCount = 0
        for (coordinate in coordinates) {
            for (expected in expectedBaseFiles(coordinate)) {
                val remotePath = coordinate.remotePath(expected.suffix)
                val actual =
                    if (expected.suffix == POM_SUFFIX) {
                        pomBytes[coordinate]
                    } else {
                        readOptional(remotePath)
                    } ?: return Inspection(State.PARTIAL, presentCoordinateCount, verifiedFileCount)
                val expectedBytes = expected.path.toFile().readBytes()
                check(actual.contentEquals(expectedBytes)) {
                    "Maven Central immutable file differs from the staged publication: $remotePath"
                }
                if (verifyChecksums(remotePath, expectedBytes).not()) {
                    return Inspection(State.PARTIAL, presentCoordinateCount, verifiedFileCount)
                }
                val signaturePath = "$remotePath.asc"
                val signature = readOptional(signaturePath)
                    ?: return Inspection(State.PARTIAL, presentCoordinateCount, verifiedFileCount)
                if (verifyChecksums(signaturePath, signature).not()) {
                    return Inspection(State.PARTIAL, presentCoordinateCount, verifiedFileCount)
                }
                verifiedFileCount += 2
            }
        }
        return Inspection(State.EXACT, presentCoordinateCount, verifiedFileCount)
    }

    private fun expectedBaseFiles(coordinate: Coordinate): List<ExpectedFile> =
        BASE_SUFFIXES.map { suffix -> ExpectedFile(suffix, coordinate.localPath(localRepository, suffix)) }

    private fun verifyChecksums(
        remotePath: String,
        content: ByteArray,
    ): Boolean {
        for (algorithm in ChecksumAlgorithm.entries) {
            val checksumPath = "$remotePath.${algorithm.extension}"
            val checksumBytes = readOptional(checksumPath) ?: return false
            val remoteHash = String(checksumBytes, StandardCharsets.UTF_8).trim().split(Regex("\\s+"), limit = 2).first()
            val expectedHash = content.hash(algorithm.messageDigestName)
            check(remoteHash.equals(expectedHash, ignoreCase = true)) {
                "Maven Central checksum differs from its immutable content: $checksumPath"
            }
        }
        return true
    }

    private fun readOptional(relativePath: String): ByteArray? {
        val target = URI.create(repositoryBase).resolve(relativePath)
        for (attempt in 1..MAXIMUM_READ_ATTEMPTS) {
            val response =
                try {
                    httpClient.send(
                        HttpRequest
                            .newBuilder(target)
                            .timeout(requestTimeout)
                            .GET()
                            .build(),
                        HttpResponse.BodyHandlers.ofByteArray(),
                    )
                } catch (failure: IOException) {
                    if (attempt < MAXIMUM_READ_ATTEMPTS) {
                        sleeper(retryBaseMillis * attempt)
                        continue
                    }
                    throw IllegalStateException(
                        "Maven Central read failed after $MAXIMUM_READ_ATTEMPTS attempts for $relativePath.",
                    )
                } catch (failure: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IllegalStateException("Maven Central read was interrupted for $relativePath.")
                }
            when {
                response.statusCode() == 200 -> return response.body()
                response.statusCode() == 404 -> return null
                response.statusCode() == 429 || 500 <= response.statusCode() && response.statusCode() < 600 -> {
                    if (attempt < MAXIMUM_READ_ATTEMPTS) {
                        sleeper(retryBaseMillis * attempt)
                        continue
                    }
                    error("Maven Central read failed with HTTP ${response.statusCode()} after $MAXIMUM_READ_ATTEMPTS attempts for $relativePath.")
                }
                else -> error("Maven Central read failed with HTTP ${response.statusCode()} for $relativePath.")
            }
        }
        error("Maven Central read exhausted its bounded retry state for $relativePath.")
    }

    private fun ByteArray.hash(algorithm: String): String =
        MessageDigest.getInstance(algorithm).digest(this).joinToString("") { byte -> "%02x".format(byte) }

    /** Remote release states admitted by the monotonic Central workflow. */
    internal enum class State(
        val wireValue: String,
    ) {
        ABSENT("absent"),
        PARTIAL("partial"),
        EXACT("exact"),
    }

    /**
     * Redacted immutable evidence from one Central reconciliation phase.
     *
     * @property state absent or byte-exact remote state.
     * @property coordinateCount exact number of coordinates inspected.
     * @property verifiedFileCount base artifacts and detached signatures whose bytes and all four checksum algorithms matched.
     * @property verifiedChecksumCount checksum sidecars independently matched against their immutable content.
     */
    internal data class Receipt(
        val state: State,
        val coordinateCount: Int,
        val verifiedFileCount: Int,
        val verifiedChecksumCount: Int,
    )

    /**
     * One remote base file and detached signature staged for cryptographic workflow verification.
     *
     * @property baseRelativePath Maven repository path of the immutable signed content.
     * @property signatureRelativePath Maven repository path of its detached ASCII-armored signature.
     * @property githubDistributionSignature whether this signature belongs to one of the 20 public Fabric main JARs.
     */
    internal data class SignedPublicationFile(
        val baseRelativePath: String,
        val signatureRelativePath: String,
        val githubDistributionSignature: Boolean,
    )

    private data class Inspection(
        val state: State,
        val presentCoordinateCount: Int,
        val verifiedFileCount: Int,
    )

    private data class ExpectedFile(
        val suffix: String,
        val path: Path,
    )

    private data class Coordinate(
        val group: String,
        val artifact: String,
        val version: String,
    ) {
        fun remotePath(suffix: String): String =
            "${group.replace('.', '/')}/$artifact/$version/$artifact-$version$suffix"

        fun localPath(
            repository: Path,
            suffix: String,
        ): Path {
            val root = repository.toAbsolutePath().normalize()
            val path = root.resolve(remotePath(suffix)).normalize()
            check(path.startsWith(root)) { "Maven release coordinate escapes the local staged repository: $this" }
            return path
        }

        companion object {
            fun parse(value: String): Coordinate {
                val segments = value.split(':')
                check(segments.size == 3 && segments.all(String::isNotBlank)) {
                    "Invalid Maven release coordinate: $value"
                }
                check(
                    segments[0].matches(GROUP_PATTERN) &&
                        segments.drop(1).all { segment ->
                            segment.matches(PATH_SEGMENT_PATTERN) && segment != "." && segment != ".."
                        },
                ) {
                    "Maven release coordinate contains an unsafe path segment: $value"
                }
                return Coordinate(segments[0], segments[1], segments[2])
            }

            private val GROUP_PATTERN = Regex("[A-Za-z0-9_+-]+(?:\\.[A-Za-z0-9_+-]+)*")
            private val PATH_SEGMENT_PATTERN = Regex("[A-Za-z0-9_.+-]+")
        }
    }

    private enum class ChecksumAlgorithm(
        val extension: String,
        val messageDigestName: String,
    ) {
        MD5("md5", "MD5"),
        SHA1("sha1", "SHA-1"),
        SHA256("sha256", "SHA-256"),
        SHA512("sha512", "SHA-512"),
    }

    companion object {
        private const val EXPECTED_COORDINATE_COUNT = 24
        private const val EXPECTED_FABRIC_COORDINATE_COUNT = 20
        private const val EXPECTED_SIGNED_FILE_COUNT = 120
        private const val MAXIMUM_READ_ATTEMPTS = 4
        private const val DEFAULT_VERIFICATION_ATTEMPTS = 120
        private const val DEFAULT_VERIFICATION_DELAY_MILLIS = 15_000L
        private const val POM_SUFFIX = ".pom"
        private const val MAIN_JAR_SUFFIX = ".jar"
        private const val FABRIC_ARTIFACT_PREFIX = "strata-runtime-minecraft-fabric-"
        private val BASE_SUFFIXES = listOf(POM_SUFFIX, ".module", ".jar", "-sources.jar", "-javadoc.jar")
        private val REMOTE_PUBLICATION_SUFFIXES =
            BASE_SUFFIXES.flatMap { suffix ->
                listOf(suffix, "$suffix.asc").flatMap { path ->
                    listOf(path) + ChecksumAlgorithm.entries.map { algorithm -> "$path.${algorithm.extension}" }
                }
            }
    }
}
