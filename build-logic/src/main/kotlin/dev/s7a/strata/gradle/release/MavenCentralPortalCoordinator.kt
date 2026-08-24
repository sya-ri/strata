package dev.s7a.strata.gradle.release

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64

/**
 * Reconciles one signed Strata release against authenticated Central Publisher Portal deployments.
 *
 * The coordinator owns no remote mutation.
 * It lists every deployment containing the release path, rejects duplicates and differing bundles, downloads all signed publication files, validates all four configured checksum sidecars, and stages evidence for an external OpenPGP verifier.
 * Credentials are retained only by this instance, are never written or logged, and are redacted from transport failures.
 */
internal class MavenCentralPortalCoordinator(
    portalBaseUri: URI,
    username: String,
    password: String,
    private val localRepository: Path,
    private val expectedCoordinateCount: Int = EXPECTED_COORDINATE_COUNT,
    private val httpClient: HttpClient =
        HttpClient
            .newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(30))
            .build(),
    private val requestTimeout: Duration = Duration.ofSeconds(60),
    private val retryBaseMillis: Long = DEFAULT_RETRY_MILLIS,
    private val sleeper: (Long) -> Unit = Thread::sleep,
) {
    private val portalBase = portalBaseUri.toString().let { value -> if (value.endsWith('/')) value else "$value/" }
    private val authorization =
        "Bearer " +
            Base64.getEncoder().encodeToString("$username:$password".toByteArray(StandardCharsets.UTF_8))

    init {
        require(portalBaseUri.isAbsolute) { "The Central Publisher Portal base URI must be absolute." }
        require(username.isNotBlank()) { "A non-blank Maven Central username is required." }
        require(password.isNotBlank()) { "A non-blank Maven Central password is required." }
        require(0 < expectedCoordinateCount) { "The expected Maven coordinate count must be positive." }
        require(requestTimeout.isZero.not() && requestTimeout.isNegative.not()) {
            "The Central Publisher Portal request timeout must be positive."
        }
        require(0L <= retryBaseMillis) { "The Central Publisher Portal retry delay must not be negative." }
    }

    /**
     * Reads current Portal state and returns only a wholly absent or byte-exact deployment.
     *
     * @param coordinateLines canonical `group:artifact:version` release inventory.
     * @param evidenceDirectory task-owned directory receiving remote signed content when a deployment exists.
     * @param incompleteAttempts number of bounded list reads permitted while an existing deployment exposes an incomplete file inventory.
     * @param pollDelayMillis delay between incomplete inventory reads.
     * @param statusAttempts number of bounded status reads permitted while files are not yet downloadable.
     * @param statusDelayMillis delay between non-downloadable lifecycle states.
     * @return redacted deployment evidence suitable for a workflow decision.
     * @throws IllegalStateException when a duplicate, failed, partial, or differing deployment exists.
     */
    internal fun preflight(
        coordinateLines: List<String>,
        evidenceDirectory: Path,
        incompleteAttempts: Int = DEFAULT_DISCOVERY_ATTEMPTS,
        pollDelayMillis: Long = DEFAULT_DISCOVERY_DELAY_MILLIS,
        statusAttempts: Int = DEFAULT_STATUS_ATTEMPTS,
        statusDelayMillis: Long = DEFAULT_STATUS_DELAY_MILLIS,
    ): Receipt {
        val release = Release.parse(coordinateLines, localRepository, expectedCoordinateCount)
        val deployment = discover(release, allowAbsent = true, incompleteAttempts, pollDelayMillis)
            ?: return Receipt(State.ABSENT, null, null, 0, 0)
        val readyState = waitUntilDownloadable(deployment, statusAttempts, statusDelayMillis)
        val verified = verifyDeployment(release, deployment, evidenceDirectory)
        return Receipt(
            state = State.EXACT,
            deploymentId = deployment.id,
            deploymentState = readyState,
            verifiedContentFileCount = verified.contentFileCount,
            verifiedChecksumCount = verified.checksumCount,
        )
    }

    /**
     * Recovers an existing or ambiguously uploaded deployment and waits until automatic publication completes.
     *
     * @param coordinateLines canonical `group:artifact:version` release inventory.
     * @param evidenceDirectory task-owned directory receiving the exact remote signed content.
     * @param discoveryAttempts number of bounded list reads permitted before an accepted upload must become visible.
     * @param discoveryDelayMillis delay between absent or incomplete list reads.
     * @param statusAttempts number of bounded status reads permitted after exact deployment discovery.
     * @param statusDelayMillis delay between non-terminal status reads.
     * @return exact published deployment evidence.
     * @throws IllegalStateException when discovery is exhausted or remote state is failed, duplicated, partial, or different.
     */
    internal fun verifyUntilPublished(
        coordinateLines: List<String>,
        evidenceDirectory: Path,
        discoveryAttempts: Int = DEFAULT_DISCOVERY_ATTEMPTS,
        discoveryDelayMillis: Long = DEFAULT_DISCOVERY_DELAY_MILLIS,
        statusAttempts: Int = DEFAULT_STATUS_ATTEMPTS,
        statusDelayMillis: Long = DEFAULT_STATUS_DELAY_MILLIS,
    ): Receipt {
        val release = Release.parse(coordinateLines, localRepository, expectedCoordinateCount)
        val deployment = discover(release, allowAbsent = false, discoveryAttempts, discoveryDelayMillis)
            ?: error("Central Publisher Portal deployment discovery unexpectedly returned no deployment.")
        val readyState = waitUntilDownloadable(deployment, statusAttempts, statusDelayMillis)
        val verified = verifyDeployment(release, deployment, evidenceDirectory)
        val publishedState = waitUntilPublished(deployment.id, readyState, statusAttempts, statusDelayMillis)
        return Receipt(
            state = State.EXACT,
            deploymentId = deployment.id,
            deploymentState = publishedState,
            verifiedContentFileCount = verified.contentFileCount,
            verifiedChecksumCount = verified.checksumCount,
        )
    }

    private fun discover(
        release: Release,
        allowAbsent: Boolean,
        attempts: Int,
        delayMillis: Long,
    ): Deployment? {
        require(0 < attempts) { "Central Publisher Portal discovery requires at least one attempt." }
        require(0L <= delayMillis) { "Central Publisher Portal discovery delay must not be negative." }
        var lastIncomplete = ""
        var observedCandidate = false
        for (attempt in 1..attempts) {
            val candidates = listDeployments(release).filter { deployment -> deployment.isCandidate(release) }
            if (1 < candidates.size) {
                error("Central Publisher Portal contains duplicate deployments for ${release.version}; refusing publication.")
            }
            val candidate = candidates.singleOrNull()
            if (candidate == null) {
                lastIncomplete = "no matching deployment is visible"
            } else {
                observedCandidate = true
                validateStableMetadata(release, candidate)
                val missing = release.expectedDeploymentFiles - candidate.files.keys
                if (missing.isEmpty()) return candidate
                lastIncomplete = "${missing.size} expected files are not visible for deployment ${candidate.id}"
            }
            if (attempt < attempts) sleeper(delayMillis)
        }
        if (allowAbsent && observedCandidate.not()) return null
        error("Central Publisher Portal deployment inventory remained incomplete after $attempts attempts: $lastIncomplete.")
    }

    private fun validateStableMetadata(
        release: Release,
        deployment: Deployment,
    ) {
        check(deployment.name == release.deploymentName) {
            "Central Publisher Portal deployment name differs for ${release.version}."
        }
        check(deployment.state != DeploymentState.FAILED) {
            "Central Publisher Portal deployment ${deployment.id} failed validation."
        }
        check(deployment.type == DeploymentType.BUNDLE) {
            "Central Publisher Portal deployment ${deployment.id} is not a release bundle."
        }
        if (deployment.purls.isNotEmpty()) {
            check(deployment.purls == release.purls) {
                "Central Publisher Portal deployment coordinates differ for ${release.version}."
            }
        }
        val unexpectedFiles = deployment.files.keys - release.expectedDeploymentFiles
        check(unexpectedFiles.isEmpty()) {
            "Central Publisher Portal deployment contains unexpected release files: ${unexpectedFiles.sorted().take(MAX_ERROR_PATHS)}"
        }
        deployment.files.forEach { (path, file) ->
            check(0L < file.size) { "Central Publisher Portal reports an empty deployment file: $path" }
        }
    }

    private fun verifyDeployment(
        release: Release,
        deployment: Deployment,
        evidenceDirectory: Path,
    ): Verification {
        Files.createDirectories(evidenceDirectory)
        var contentCount = 0
        var checksumCount = 0
        release.baseFiles.forEach { base ->
            val local = Files.readAllBytes(base.localPath)
            val remote = download(deployment.id, base.relativePath)
            check(remote.contentEquals(local)) {
                "Central Publisher Portal deployment file differs from the staged publication: ${base.relativePath}"
            }
            validateListedSize(deployment, base.relativePath, remote)
            writeEvidence(evidenceDirectory, base.relativePath, remote)
            contentCount += 1
            checksumCount += verifyChecksums(deployment, base.relativePath, remote)

            val signaturePath = "${base.relativePath}.asc"
            val signature = download(deployment.id, signaturePath)
            validateListedSize(deployment, signaturePath, signature)
            writeEvidence(evidenceDirectory, signaturePath, signature)
            contentCount += 1
            checksumCount += verifyChecksums(deployment, signaturePath, signature)
        }
        check(contentCount == expectedCoordinateCount * BASE_SUFFIXES.size * 2) {
            "Central Publisher Portal signed-content inventory has an unexpected size."
        }
        check(checksumCount == contentCount * ChecksumAlgorithm.entries.size) {
            "Central Publisher Portal checksum inventory has an unexpected size."
        }
        return Verification(contentCount, checksumCount)
    }

    private fun validateListedSize(
        deployment: Deployment,
        path: String,
        content: ByteArray,
    ) {
        val listed = deployment.files[path] ?: error("Central Publisher Portal omitted deployment file metadata: $path")
        check(listed.size == content.size.toLong()) {
            "Central Publisher Portal deployment file size differs from downloaded content: $path"
        }
    }

    private fun verifyChecksums(
        deployment: Deployment,
        path: String,
        content: ByteArray,
    ): Int {
        ChecksumAlgorithm.entries.forEach { algorithm ->
            val checksumPath = "$path.${algorithm.extension}"
            val bytes = download(deployment.id, checksumPath)
            validateListedSize(deployment, checksumPath, bytes)
            val actual = String(bytes, StandardCharsets.UTF_8).trim().split(Regex("\\s+"), limit = 2).firstOrNull().orEmpty()
            val expected = content.hash(algorithm.messageDigestName)
            check(actual.equals(expected, ignoreCase = true)) {
                "Central Publisher Portal checksum differs from deployment content: $checksumPath"
            }
        }
        return ChecksumAlgorithm.entries.size
    }

    private fun waitUntilDownloadable(
        deployment: Deployment,
        attempts: Int,
        delayMillis: Long,
    ): DeploymentState {
        require(0 < attempts) { "Central Publisher Portal readiness verification requires at least one attempt." }
        require(0L <= delayMillis) { "Central Publisher Portal status delay must not be negative." }
        var state = deployment.state
        for (attempt in 1..attempts) {
            when (state) {
                DeploymentState.VALIDATED,
                DeploymentState.PUBLISHING,
                DeploymentState.PUBLISHED,
                -> return state
                DeploymentState.FAILED -> error("Central Publisher Portal deployment ${deployment.id} failed validation.")
                DeploymentState.PENDING,
                DeploymentState.VALIDATING,
                -> {
                    state = readStatus(deployment.id)
                    if (attempt < attempts && state in setOf(DeploymentState.PENDING, DeploymentState.VALIDATING)) {
                        sleeper(delayMillis)
                    }
                }
            }
        }
        if (state in setOf(DeploymentState.VALIDATED, DeploymentState.PUBLISHING, DeploymentState.PUBLISHED)) return state
        if (state == DeploymentState.FAILED) error("Central Publisher Portal deployment ${deployment.id} failed validation.")
        error("Central Publisher Portal deployment ${deployment.id} did not become downloadable after $attempts status reads; last state was ${state.wireValue}.")
    }

    private fun waitUntilPublished(
        deploymentId: String,
        initialState: DeploymentState,
        attempts: Int,
        delayMillis: Long,
    ): DeploymentState {
        require(0 < attempts) { "Central Publisher Portal status verification requires at least one attempt." }
        require(0L <= delayMillis) { "Central Publisher Portal status delay must not be negative." }
        var state = initialState
        if (state == DeploymentState.PUBLISHED) return state
        for (attempt in 1..attempts) {
            state = readStatus(deploymentId)
            when (state) {
                DeploymentState.PUBLISHED -> return state
                DeploymentState.FAILED -> error("Central Publisher Portal deployment $deploymentId failed validation.")
                DeploymentState.PENDING,
                DeploymentState.VALIDATING,
                DeploymentState.VALIDATED,
                DeploymentState.PUBLISHING,
                -> if (attempt < attempts) sleeper(delayMillis)
            }
        }
        error("Central Publisher Portal deployment $deploymentId did not publish after $attempts status reads; last state was ${state.wireValue}.")
    }

    private fun listDeployments(release: Release): List<Deployment> {
        val deployments = mutableListOf<Deployment>()
        var page = 0
        var pageCount: Int
        var expectedPageCount: Int? = null
        var expectedTotalCount: Int? = null
        do {
            val body =
                JsonOutput.toJson(
                    linkedMapOf(
                        "page" to page,
                        "size" to PAGE_SIZE,
                        "sortField" to "createdTimestamp",
                        "sortDirection" to "desc",
                        "pathStarting" to release.groupPathPrefix,
                    ),
                )
            val root = parseObject(postJson("api/v1/publisher/deployments/files", body), "Central deployment inventory")
            check(root.requiredInt("page") == page) { "Central Publisher Portal returned a different deployment page." }
            val pageSize = root.requiredInt("pageSize")
            check(0 < pageSize && pageSize <= PAGE_SIZE) { "Central Publisher Portal returned an invalid deployment page size." }
            pageCount = root.requiredInt("pageCount")
            check(pageCount in 0..MAXIMUM_PAGES) { "Central Publisher Portal returned an invalid deployment page count." }
            val totalCount = root.requiredInt("totalResultCount")
            check(0 <= totalCount) { "Central Publisher Portal returned a negative deployment result count." }
            check(totalCount == 0 || 0 < pageCount) { "Central Publisher Portal returned an inconsistent empty page count." }
            val stablePageCount = expectedPageCount
            check(stablePageCount == null || stablePageCount == pageCount) {
                "Central Publisher Portal deployment page count changed during discovery."
            }
            expectedPageCount = pageCount
            val stableTotalCount = expectedTotalCount
            check(stableTotalCount == null || stableTotalCount == totalCount) {
                "Central Publisher Portal deployment result count changed during discovery."
            }
            expectedTotalCount = totalCount
            val pageDeployments = root.requiredList("deployments")
            check(pageDeployments.size <= pageSize) { "Central Publisher Portal returned more deployments than the declared page size." }
            deployments += pageDeployments.map { value -> decodeDeployment(value.requiredObject("Central deployment")) }
            page += 1
        } while (page < pageCount)
        check(deployments.size == expectedTotalCount) {
            "Central Publisher Portal deployment pagination did not return the declared result count."
        }
        check(deployments.map(Deployment::id).distinct().size == deployments.size) {
            "Central Publisher Portal returned a duplicate deployment identifier."
        }
        return deployments
    }

    private fun decodeDeployment(value: Map<*, *>): Deployment {
        val fileEntries =
            value.optionalList("deploymentFiles").map { item ->
                val file = item.requiredObject("Central deployment file")
                val path = file.requiredString("relativePath")
                check(file.requiredString("fileName") == path.substringAfterLast('/')) {
                    "Central Publisher Portal deployment filename differs from its relative path."
                }
                path to DeploymentFile(file.requiredLong("fileSize"))
            }
        check(fileEntries.map { entry -> entry.first }.distinct().size == fileEntries.size) {
            "Central Publisher Portal returned a duplicate deployment file path."
        }
        return Deployment(
            id = value.requiredString("deploymentId"),
            name = value.requiredString("deploymentName"),
            state = DeploymentState.decode(value.requiredString("deploymentState")),
            type = DeploymentType.decode(value.requiredString("deploymentType")),
            purls = value.optionalStringSet("purls"),
            files = fileEntries.toMap(),
        )
    }

    private fun readStatus(deploymentId: String): DeploymentState {
        val encoded = URLEncoder.encode(deploymentId, StandardCharsets.UTF_8).replace("+", "%20")
        val root = parseObject(post("api/v1/publisher/status?id=$encoded"), "Central deployment status")
        check(root.requiredString("deploymentId") == deploymentId) {
            "Central Publisher Portal status returned a different deployment identifier."
        }
        return DeploymentState.decode(root.requiredString("deploymentState"))
    }

    private fun download(
        deploymentId: String,
        relativePath: String,
    ): ByteArray {
        val encodedId = URLEncoder.encode(deploymentId, StandardCharsets.UTF_8).replace("+", "%20")
        val encodedPath = relativePath.split('/').joinToString("/") { segment ->
            URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20")
        }
        return read("api/v1/publisher/deployment/$encodedId/download/$encodedPath", "Central deployment file $relativePath")
    }

    private fun postJson(
        relativePath: String,
        body: String,
    ): String =
        send(
            relativePath,
            HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8),
            "application/json",
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
        )

    private fun post(relativePath: String): String =
        send(
            relativePath,
            HttpRequest.BodyPublishers.noBody(),
            null,
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
        )

    private fun read(
        relativePath: String,
        description: String,
    ): ByteArray =
        send(
            relativePath,
            HttpRequest.BodyPublishers.noBody(),
            null,
            HttpResponse.BodyHandlers.ofByteArray(),
            method = "GET",
            description = description,
        )

    private fun <T> send(
        relativePath: String,
        body: HttpRequest.BodyPublisher,
        contentType: String?,
        handler: HttpResponse.BodyHandler<T>,
        method: String = "POST",
        description: String = "Central Publisher Portal response",
    ): T {
        var lastFailure = ""
        for (attempt in 1..MAXIMUM_READ_ATTEMPTS) {
            val request =
                HttpRequest
                    .newBuilder(URI.create(portalBase).resolve(relativePath))
                    .timeout(requestTimeout)
                    .header("Authorization", authorization)
                    .header("Accept", "application/json, application/octet-stream")
                    .header("User-Agent", USER_AGENT)
                    .apply { if (contentType != null) header("Content-Type", contentType) }
                    .method(method, body)
                    .build()
            val response =
                try {
                    httpClient.send(request, handler)
                } catch (failure: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IllegalStateException("Interrupted while reading $description.")
                } catch (failure: IOException) {
                    lastFailure = safe(failure.message)
                    if (attempt < MAXIMUM_READ_ATTEMPTS) {
                        sleeper(retryBaseMillis * attempt)
                        continue
                    }
                    throw IllegalStateException("$description failed after $MAXIMUM_READ_ATTEMPTS attempts: $lastFailure")
                }
            when {
                response.statusCode() in 200..299 -> return response.body()
                response.statusCode() == HTTP_TOO_MANY_REQUESTS || response.statusCode() in 500..599 -> {
                    lastFailure = "HTTP ${response.statusCode()}"
                    if (attempt < MAXIMUM_READ_ATTEMPTS) {
                        sleeper(retryBaseMillis * attempt)
                        continue
                    }
                }
                else -> error("$description returned HTTP ${response.statusCode()}.")
            }
        }
        error("$description failed after $MAXIMUM_READ_ATTEMPTS attempts: $lastFailure")
    }

    private fun parseObject(
        body: String,
        description: String,
    ): Map<*, *> =
        try {
            JsonSlurper().parseText(body) as? Map<*, *> ?: error("$description must be a JSON object.")
        } catch (failure: RuntimeException) {
            throw IllegalStateException("$description contained invalid JSON.", failure)
        }

    private fun writeEvidence(
        directory: Path,
        relativePath: String,
        content: ByteArray,
    ) {
        val root = directory.toAbsolutePath().normalize()
        val output = root.resolve(relativePath).normalize()
        check(output.startsWith(root)) { "Central Publisher Portal evidence path escapes its task-owned directory." }
        Files.createDirectories(output.parent)
        Files.write(output, content)
    }

    private fun safe(value: String?): String = value.orEmpty().replace(authorization, "<redacted>")

    private fun ByteArray.hash(algorithm: String): String =
        MessageDigest
            .getInstance(algorithm)
            .digest(this)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun Map<*, *>.requiredString(key: String): String =
        (this[key] as? String)?.takeIf(String::isNotBlank)
            ?: error("Central Publisher Portal response omitted non-blank string $key.")

    private fun Map<*, *>.requiredLong(key: String): Long =
        (this[key] as? Number)?.toLong() ?: error("Central Publisher Portal response omitted number $key.")

    private fun Map<*, *>.requiredInt(key: String): Int =
        (this[key] as? Number)?.toInt() ?: error("Central Publisher Portal response omitted number $key.")

    private fun Map<*, *>.optionalList(key: String): List<*> =
        when (val value = this[key]) {
            null -> emptyList<Any>()
            is List<*> -> value
            else -> error("Central Publisher Portal response field $key must be an array.")
        }

    private fun Map<*, *>.requiredList(key: String): List<*> =
        this[key] as? List<*> ?: error("Central Publisher Portal response omitted array $key.")

    private fun Map<*, *>.optionalStringSet(key: String): Set<String> =
        optionalList(key).map { value -> value as? String ?: error("Central Publisher Portal $key must contain strings.") }.toSet()

    private fun Any?.requiredObject(description: String): Map<*, *> =
        this as? Map<*, *> ?: error("$description must be a JSON object.")

    /** Central deployment lifecycle states accepted at the external JSON boundary. */
    internal enum class DeploymentState(
        val wireValue: String,
    ) {
        PENDING("PENDING"),
        VALIDATING("VALIDATING"),
        VALIDATED("VALIDATED"),
        PUBLISHING("PUBLISHING"),
        PUBLISHED("PUBLISHED"),
        FAILED("FAILED"),
        ;

        companion object {
            /** Decodes one exact Portal wire value or fails closed on API drift. */
            internal fun decode(value: String): DeploymentState =
                entries.firstOrNull { state -> state.wireValue == value }
                    ?: error("Unknown Central Publisher Portal deployment state: $value")
        }
    }

    /** Portal release inventory state used by the protected workflow. */
    internal enum class State(
        val wireValue: String,
    ) {
        ABSENT("absent"),
        EXACT("exact"),
    }

    /**
     * Redacted Portal reconciliation evidence.
     *
     * @property state absent or exact deployment inventory.
     * @property deploymentId non-secret immutable deployment identifier when exact.
     * @property deploymentState last typed Portal lifecycle state when exact.
     * @property verifiedContentFileCount signed base files and detached signatures downloaded and verified.
     * @property verifiedChecksumCount checksum sidecars validated against downloaded content.
     */
    internal data class Receipt(
        val state: State,
        val deploymentId: String?,
        val deploymentState: DeploymentState?,
        val verifiedContentFileCount: Int,
        val verifiedChecksumCount: Int,
    )

    private data class Verification(
        val contentFileCount: Int,
        val checksumCount: Int,
    )

    private data class DeploymentFile(
        val size: Long,
    )

    private data class Deployment(
        val id: String,
        val name: String,
        val state: DeploymentState,
        val type: DeploymentType,
        val purls: Set<String>,
        val files: Map<String, DeploymentFile>,
    ) {
        fun isCandidate(release: Release): Boolean =
            name == release.deploymentName ||
                purls.any(release.purls::contains) ||
                files.keys.any { path -> release.versionDirectoryPrefixes.any(path::startsWith) }
    }

    private data class BaseFile(
        val relativePath: String,
        val localPath: Path,
    )

    private data class Coordinate(
        val group: String,
        val artifact: String,
        val version: String,
    ) {
        val directoryPrefix: String = "${group.replace('.', '/')}/$artifact/$version/"

        fun relativePath(suffix: String): String = "$directoryPrefix$artifact-$version$suffix"

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

    private data class Release(
        val coordinates: List<Coordinate>,
        val version: String,
        val deploymentName: String,
        val groupPathPrefix: String,
        val purls: Set<String>,
        val versionDirectoryPrefixes: Set<String>,
        val baseFiles: List<BaseFile>,
        val expectedDeploymentFiles: Set<String>,
    ) {
        companion object {
            fun parse(
                lines: List<String>,
                localRepository: Path,
                expectedCoordinateCount: Int,
            ): Release {
                val coordinates = lines.filter(String::isNotBlank).map(Coordinate::parse)
                check(coordinates.size == expectedCoordinateCount) {
                    "Central Publisher Portal verification requires exactly $expectedCoordinateCount coordinates."
                }
                check(coordinates.distinct().size == coordinates.size) { "Maven release coordinates must be unique." }
                val groups = coordinates.map(Coordinate::group).distinct()
                val versions = coordinates.map(Coordinate::version).distinct()
                check(groups.size == 1) { "Central Publisher Portal verification requires one Maven group." }
                check(versions.size == 1) { "Central Publisher Portal verification requires one release version." }
                val group = groups.single()
                val version = versions.single()
                val repositoryRoot = localRepository.toAbsolutePath().normalize()
                val baseFiles =
                    coordinates.flatMap { coordinate ->
                        BASE_SUFFIXES.map { suffix ->
                            val relativePath = coordinate.relativePath(suffix)
                            val localPath = repositoryRoot.resolve(relativePath).normalize()
                            check(localPath.startsWith(repositoryRoot)) {
                                "Maven release coordinate escapes the local staged repository: $coordinate"
                            }
                            check(Files.isRegularFile(localPath) && 0L < Files.size(localPath)) {
                                "Local staged publication is missing or empty: $localPath"
                            }
                            BaseFile(relativePath, localPath)
                        }
                    }
                val contentPaths = baseFiles.flatMap { base -> listOf(base.relativePath, "${base.relativePath}.asc") }
                val expectedFiles =
                    contentPaths.flatMap { path ->
                        listOf(path) + ChecksumAlgorithm.entries.map { algorithm -> "$path.${algorithm.extension}" }
                    }.toSet()
                return Release(
                    coordinates = coordinates,
                    version = version,
                    deploymentName = "$group-$version",
                    groupPathPrefix = "${group.replace('.', '/')}/",
                    purls = coordinates.map { coordinate -> "pkg:maven/${coordinate.group}/${coordinate.artifact}@${coordinate.version}" }.toSet(),
                    versionDirectoryPrefixes = coordinates.map(Coordinate::directoryPrefix).toSet(),
                    baseFiles = baseFiles,
                    expectedDeploymentFiles = expectedFiles,
                )
            }
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

    private enum class DeploymentType(
        val wireValue: String,
    ) {
        BUNDLE("BUNDLE"),
        SINGLE("SINGLE"),
        ;

        companion object {
            fun decode(value: String): DeploymentType =
                entries.firstOrNull { type -> type.wireValue == value }
                    ?: error("Unknown Central Publisher Portal deployment type: $value")
        }
    }

    companion object {
        private const val EXPECTED_COORDINATE_COUNT = 24
        private const val PAGE_SIZE = 500
        private const val MAXIMUM_PAGES = 100
        private const val MAXIMUM_READ_ATTEMPTS = 4
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val DEFAULT_RETRY_MILLIS = 250L
        private const val DEFAULT_DISCOVERY_ATTEMPTS = 12
        private const val DEFAULT_DISCOVERY_DELAY_MILLIS = 5_000L
        private const val DEFAULT_STATUS_ATTEMPTS = 120
        private const val DEFAULT_STATUS_DELAY_MILLIS = 15_000L
        private const val MAX_ERROR_PATHS = 8
        private const val USER_AGENT = "sya-ri/strata-release"
        private val BASE_SUFFIXES = listOf(".pom", ".module", ".jar", "-sources.jar", "-javadoc.jar")
    }
}
