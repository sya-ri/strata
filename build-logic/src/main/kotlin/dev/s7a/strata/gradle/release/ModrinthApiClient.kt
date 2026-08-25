package dev.s7a.strata.gradle.release

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.math.BigInteger
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.util.UUID

// Keeping transport, decoding, and credential redaction together preserves one auditable authentication boundary.

/**
 * Minimal Modrinth client used only by explicit release tasks.
 *
 * External discriminators are decoded into enums at this boundary.
 * The client retains no credential after the task releases it, never logs request headers, and removes the token from every surfaced error.
 * Read operations retry bounded transient failures.
 * Ambiguous writes are returned without raw response causes so the coordinator can re-query authoritative state before retrying.
 */
@Suppress("TooManyFunctions")
internal class ModrinthApiClient(
    apiBaseUrl: String,
    private val token: String,
    private val userAgent: String,
    private val httpClient: HttpClient =
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build(),
    private val allowedDownloadHosts: Set<String> = setOf(MODRINTH_CDN_HOST),
    private val allowInsecureDownloads: Boolean = false,
    private val requestTimeout: Duration = Duration.ofSeconds(60),
    private val retryBaseMillis: Long = DEFAULT_RETRY_MILLIS,
) {
    private val baseUrl = apiBaseUrl.trimEnd('/')
    private val baseUri = URI.create(baseUrl)
    private val apiOrigin = "${baseUri.scheme}://${baseUri.rawAuthority}"

    init {
        require(token.isNotBlank()) { "A non-blank Modrinth token is required." }
        require(userAgent.isNotBlank()) { "A non-blank Modrinth User-Agent is required." }
        require(requestTimeout.isZero.not() && requestTimeout.isNegative.not()) { "The Modrinth request timeout must be positive." }
        require(0L < retryBaseMillis) { "The Modrinth retry base delay must be positive." }
    }

    /**
     * Gets project metadata, including typed lifecycle state and project assets.
     */
    fun getProject(projectId: String): RemoteProject = decodeProject(parseObject(read("/project/${encode(projectId)}"), "Modrinth project"))

    /**
     * Gets the current content disclosures through the v3 project disclosure endpoint.
     */
    fun getProjectDisclosures(projectId: String): List<RemoteDisclosure> {
        val root = parseObject(readAbsolute("$apiOrigin/v3/project/${encode(projectId)}/disclosures"), "Modrinth disclosures")
        return root.requiredList("disclosures").map { value ->
            decodeDisclosure(value.asRequiredObject("Modrinth disclosure"))
        }
    }

    /**
     * Gets every canonical game-version tag currently accepted by Modrinth.
     */
    fun getGameVersions(): Set<String> =
        parseList(read("/tag/game_version"), "Modrinth game-version tags")
            .map { value -> value.asRequiredObject("Modrinth game-version tag").requiredString("version") }
            .toSet()

    /**
     * Lists every project version, including changelogs required for exact reconciliation.
     */
    fun getProjectVersions(projectId: String): List<RemoteVersion> =
        parseList(
            read("/project/${encode(projectId)}/version?include_changelog=true"),
            "Modrinth project versions",
        ).map { value -> decodeVersion(value.asRequiredObject("Modrinth version")) }

    /**
     * Creates one listed version with exactly one primary JAR.
     *
     * @throws AmbiguousWriteException when the response is unknown after a timeout, transport error, rate limit, or server failure.
     * @throws WriteRejectedException when Modrinth deterministically rejects the request with a non-retryable client status.
     */
    fun createListedVersion(
        manifest: ModrinthManifest,
        artifact: ModrinthManifest.Artifact,
        file: File,
    ) {
        val boundary = "strata-${UUID.randomUUID()}"
        val data =
            linkedMapOf<String, Any>(
                "name" to artifact.versionName,
                "version_number" to artifact.versionNumber,
                "changelog" to manifest.changelog,
                "dependencies" to
                    listOf(
                        linkedMapOf(
                            "project_id" to ModrinthManifest.FABRIC_LANGUAGE_KOTLIN_PROJECT_ID,
                            "dependency_type" to DependencyType.REQUIRED.wireValue,
                        ),
                    ),
                "game_versions" to listOf(artifact.gameVersion),
                "version_type" to ReleaseType.RELEASE.wireValue,
                "loaders" to listOf(Loader.FABRIC.wireValue),
                "featured" to ModrinthManifest.FEATURED,
                "status" to VersionStatus.LISTED.wireValue,
                "project_id" to manifest.projectId,
                "file_parts" to listOf("primary"),
                "primary_file" to "primary",
                "environment" to VersionEnvironment.CLIENT_ONLY.wireValue,
            )
        writeOnce(
            method = "POST",
            path = "/version",
            contentType = "multipart/form-data; boundary=$boundary",
            publisher = multipart(boundary, JsonOutput.toJson(data), artifact.fileName, file),
        )
    }

    /**
     * Sets only absent project classification fields through the authenticated v3 project endpoint.
     *
     * Null arguments are omitted so an exact existing value is never rewritten.
     *
     * @throws AmbiguousWriteException when the response is unknown after a timeout, transport error, rate limit, or server failure.
     * @throws WriteRejectedException when Modrinth deterministically rejects the request with a non-retryable client status.
     */
    fun updateProjectClassification(
        projectId: String,
        categories: Set<String>?,
        additionalCategories: Set<String>?,
        environment: VersionEnvironment?,
    ) {
        val data = linkedMapOf<String, Any>()
        categories?.let { values -> data["categories"] = values.sorted() }
        additionalCategories?.let { values -> data["additional_categories"] = values.sorted() }
        environment?.let { value -> data["environment"] = value.wireValue }
        check(data.isNotEmpty()) { "A Modrinth project classification patch must contain at least one absent field." }
        writeAbsoluteOnce(
            method = "PATCH",
            url = "$apiOrigin/v3/project/${encode(projectId)}",
            contentType = "application/json",
            publisher = HttpRequest.BodyPublishers.ofString(JsonOutput.toJson(data)),
        )
    }

    /**
     * Sets the exact AI-content disclosure without removing any existing disclosure.
     *
     * @throws AmbiguousWriteException when the response is unknown after a timeout, transport error, rate limit, or server failure.
     * @throws WriteRejectedException when Modrinth deterministically rejects the request with a non-retryable client status.
     */
    fun setAiDisclosure(
        projectId: String,
        note: String,
        uses: Set<AiUse>,
    ) {
        val disclosure =
            linkedMapOf<String, Any>(
                "type" to DisclosureType.AI_CONTENT.wireValue,
                "uses" to uses.map(AiUse::wireValue).sorted(),
                "note" to note,
            )
        val data = linkedMapOf<String, Any>("set" to listOf(disclosure), "remove" to emptyList<String>())
        writeAbsoluteOnce(
            method = "PATCH",
            url = "$apiOrigin/v3/project/${encode(projectId)}/disclosures",
            contentType = "application/json",
            publisher = HttpRequest.BodyPublishers.ofString(JsonOutput.toJson(data)),
        )
    }

    /**
     * Submits a completely staged project for public review without changing version metadata.
     *
     * @throws AmbiguousWriteException when the response is unknown after a timeout, transport error, rate limit, or server failure.
     * @throws WriteRejectedException when Modrinth deterministically rejects the request with a non-retryable client status.
     */
    fun submitProject(projectId: String) {
        writeOnce(
            method = "PATCH",
            path = "/project/${encode(projectId)}",
            contentType = "application/json",
            publisher =
                HttpRequest.BodyPublishers.ofString(
                    JsonOutput.toJson(mapOf("requested_status" to ProjectStatus.APPROVED.wireValue)),
                ),
        )
    }

    /**
     * Downloads one authoritative Modrinth CDN object without forwarding the API token.
     *
     * Redirects are followed manually only when every hop remains HTTPS on the configured CDN host allowlist.
     */
    fun hashRemoteFile(url: String): DownloadedFile {
        var uri = validatedDownloadUri(URI.create(url))
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            var lastFailureMessage = ""
            var redirectTarget: URI? = null
            for (attempt in 0 until MAX_ATTEMPTS) {
                try {
                    val response =
                        httpClient.send(
                            HttpRequest
                                .newBuilder(uri)
                                .timeout(requestTimeout)
                                .header("Accept", "*/*")
                                .header("User-Agent", userAgent)
                                .GET()
                                .build(),
                            HttpResponse.BodyHandlers.ofInputStream(),
                        )
                    when {
                        response.statusCode() in 200..299 -> {
                            return hash(response)
                        }

                        response.statusCode() in 300..399 -> {
                            response.body().close()
                            check(redirectCount < MAX_REDIRECTS) { "Modrinth CDN exceeded the redirect limit." }
                            val location =
                                response.headers().firstValue("Location").orElse(null)
                                    ?: error("Modrinth CDN redirect omitted Location.")
                            redirectTarget = validatedDownloadUri(uri.resolve(location))
                            break
                        }

                        isTransientStatus(response.statusCode()) -> {
                            response.body().close()
                            lastFailureMessage = "Modrinth CDN returned HTTP ${response.statusCode()}."
                            pause(response.headers().firstValue("X-Ratelimit-Reset").orElse(null), attempt)
                        }

                        else -> {
                            response.body().close()
                            error("Modrinth CDN returned HTTP ${response.statusCode()}.")
                        }
                    }
                } catch (failure: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IllegalStateException("Interrupted while hashing a Modrinth CDN object.")
                } catch (failure: IOException) {
                    lastFailureMessage = safe(failure.message)
                    pause(null, attempt)
                }
            }
            if (redirectTarget != null) {
                uri = redirectTarget
                return@repeat
            }
            error("Modrinth CDN download failed after $MAX_ATTEMPTS attempts: $lastFailureMessage")
        }
        error("Modrinth CDN exceeded the redirect limit.")
    }

    private fun decodeProject(value: Map<*, *>): RemoteProject =
        RemoteProject(
            id = value.requiredString("id"),
            slug = value.requiredString("slug"),
            title = value.requiredString("title"),
            description = value.requiredString("description"),
            body = value.requiredString("body"),
            projectType = ProjectType.decode(value.requiredString("project_type")),
            status = ProjectStatus.decode(value.requiredString("status")),
            categories = value.stringSet("categories"),
            additionalCategories = value.stringSet("additional_categories"),
            environments = value.stringSet("environment").map(VersionEnvironment::decode).toSet(),
            licenseId = value.requiredMap("license").requiredString("id"),
            clientSide = SideSupport.decode(value.requiredString("client_side")),
            serverSide = SideSupport.decode(value.requiredString("server_side")),
            sourceUrl = value.optionalString("source_url"),
            issuesUrl = value.optionalString("issues_url"),
            documentationUrl = value.optionalString("wiki_url"),
            rawIconUrl = value.optionalString("raw_icon_url") ?: value.optionalString("icon_url"),
            gallery =
                value.requiredList("gallery").map { item ->
                    val gallery = item.asRequiredObject("Modrinth gallery image")
                    RemoteGalleryImage(
                        rawUrl = gallery.requiredString("raw_url"),
                        featured = gallery.requiredBoolean("featured"),
                        title = gallery.optionalString("title"),
                        description = gallery.optionalString("description"),
                        ordering = gallery.requiredInt("ordering"),
                    )
                },
        )

    private fun decodeDisclosure(value: Map<*, *>): RemoteDisclosure =
        RemoteDisclosure(
            type = DisclosureType.decode(value.requiredString("type")),
            note = value.optionalString("note"),
            aiUses =
                (value["uses"] as? List<*>)
                    ?.map { item ->
                        AiUse.decode(item as? String ?: error("Modrinth disclosure uses must contain strings."))
                    }?.toSet()
                    .orEmpty(),
            deleted = value.optionalString("deleted_at") != null,
        )

    private fun decodeVersion(value: Map<*, *>): RemoteVersion =
        RemoteVersion(
            id = value.requiredString("id"),
            projectId = value.requiredString("project_id"),
            name = value.requiredString("name"),
            versionNumber = value.requiredString("version_number"),
            changelog = value.optionalString("changelog"),
            dependencies =
                value.requiredList("dependencies").map { item ->
                    val dependency = item.asRequiredObject("Modrinth dependency")
                    RemoteDependency(
                        projectId = dependency.optionalString("project_id"),
                        versionId = dependency.optionalString("version_id"),
                        fileName = dependency.optionalString("file_name"),
                        type = DependencyType.decode(dependency.requiredString("dependency_type")),
                    )
                },
            gameVersions = value.stringSet("game_versions"),
            releaseType = ReleaseType.decode(value.requiredString("version_type")),
            loaders = value.stringSet("loaders").map(Loader::decode).toSet(),
            featured = value.requiredBoolean("featured"),
            status = VersionStatus.decode(value.requiredString("status")),
            environment = VersionEnvironment.decode(value.requiredString("environment")),
            files =
                value.requiredList("files").map { item ->
                    val file = item.asRequiredObject("Modrinth version file")
                    val hashes = file.requiredMap("hashes")
                    RemoteFile(
                        fileName = file.requiredString("filename"),
                        size = file.requiredLong("size"),
                        primary = file.requiredBoolean("primary"),
                        url = file.requiredString("url"),
                        sha256 = hashes.optionalString("sha256"),
                        sha512 = hashes.requiredString("sha512"),
                    )
                },
        )

    private fun read(path: String): String = readAbsolute("$baseUrl$path")

    private fun readAbsolute(url: String): String {
        var lastFailureMessage = ""
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                val response = httpClient.send(request(url).GET().build(), HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() in 200..299) return safe(response.body())
                val message = remoteFailureMessage(response)
                if (isTransientStatus(response.statusCode())) {
                    lastFailureMessage = message
                    pause(response.headers().firstValue("X-Ratelimit-Reset").orElse(null), attempt)
                } else {
                    error(message)
                }
            } catch (failure: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IllegalStateException("Interrupted while reading Modrinth state.")
            } catch (failure: IOException) {
                lastFailureMessage = safe(failure.message)
                pause(null, attempt)
            }
        }
        error("Modrinth read failed after $MAX_ATTEMPTS attempts: $lastFailureMessage")
    }

    private fun writeOnce(
        method: String,
        path: String,
        contentType: String,
        publisher: HttpRequest.BodyPublisher,
    ) = writeAbsoluteOnce(method, "$baseUrl$path", contentType, publisher)

    // The exception mapping intentionally drops transport causes so credential-bearing request state cannot escape this boundary.
    @Suppress("SwallowedException", "ThrowsCount")
    private fun writeAbsoluteOnce(
        method: String,
        url: String,
        contentType: String,
        publisher: HttpRequest.BodyPublisher,
    ) {
        try {
            val response =
                httpClient.send(
                    request(url).header("Content-Type", contentType).method(method, publisher).build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
            if (response.statusCode() in 200..299) return
            val message = remoteFailureMessage(response)
            if (isTransientStatus(response.statusCode())) {
                throw AmbiguousWriteException(message, retryDelayMillis(response.headers().firstValue("X-Ratelimit-Reset").orElse(null), 0))
            }
            if (response.statusCode() in 400..499) throw WriteRejectedException(response.statusCode())
            error(message)
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("Interrupted while writing Modrinth state.")
        } catch (failure: IOException) {
            throw AmbiguousWriteException(
                "Modrinth write response was not observed: ${safe(failure.message)}",
                retryBaseMillis,
            )
        }
    }

    private fun request(url: String): HttpRequest.Builder =
        HttpRequest
            .newBuilder(URI.create(url))
            .timeout(requestTimeout)
            .header("Authorization", token)
            .header("Accept", "application/json")
            .header("User-Agent", userAgent)

    private fun multipart(
        boundary: String,
        data: String,
        fileName: String,
        file: File,
    ): HttpRequest.BodyPublisher {
        val prefix =
            "--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"data\"\r\n" +
                "Content-Type: application/json\r\n\r\n" +
                data +
                "\r\n--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"primary\"; filename=\"$fileName\"\r\n" +
                "Content-Type: application/java-archive\r\n\r\n"
        val suffix = "\r\n--$boundary--\r\n"
        return HttpRequest.BodyPublishers.concat(
            HttpRequest.BodyPublishers.ofByteArray(prefix.toByteArray(StandardCharsets.UTF_8)),
            HttpRequest.BodyPublishers.ofFile(file.toPath()),
            HttpRequest.BodyPublishers.ofByteArray(suffix.toByteArray(StandardCharsets.UTF_8)),
        )
    }

    private fun hash(response: HttpResponse<InputStream>): DownloadedFile {
        val sha256 = MessageDigest.getInstance("SHA-256")
        val sha512 = MessageDigest.getInstance("SHA-512")
        var size = 0L
        response.body().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                size += count
                sha256.update(buffer, 0, count)
                sha512.update(buffer, 0, count)
            }
        }
        return DownloadedFile(size, sha256.hex(), sha512.hex())
    }

    private fun validatedDownloadUri(uri: URI): URI {
        val scheme = DownloadScheme.decode(uri.scheme)
        if (allowInsecureDownloads) {
            check(scheme in setOf(DownloadScheme.HTTP, DownloadScheme.HTTPS)) { "Test download URL must use HTTP or HTTPS." }
        } else {
            check(scheme == DownloadScheme.HTTPS) { "Modrinth CDN URL must use HTTPS." }
        }
        check(uri.host?.lowercase() in allowedDownloadHosts.map(String::lowercase)) {
            "Modrinth CDN URL uses a non-authoritative host."
        }
        check(uri.userInfo == null) { "Modrinth CDN URL must not contain user information." }
        return uri
    }

    private fun pause(
        reset: String?,
        attempt: Int,
    ) {
        val delay = retryDelayMillis(reset, attempt)
        if (0L < delay) Thread.sleep(delay)
    }

    private fun retryDelayMillis(
        reset: String?,
        attempt: Int,
    ): Long {
        val exponential = retryBaseMillis * (1L shl attempt.coerceAtMost(4))
        return ((reset?.toLongOrNull()?.times(1_000L)) ?: exponential).coerceIn(0L, MAX_RETRY_MILLIS)
    }

    private fun remoteFailureMessage(response: HttpResponse<String>): String = "Modrinth returned HTTP ${response.statusCode()}: ${safe(response.body()).take(MAX_ERROR_BODY_LENGTH)}"

    private fun safe(value: String?): String = value.orEmpty().replace(token, "<redacted>")

    private fun isTransientStatus(statusCode: Int): Boolean = statusCode == HTTP_REQUEST_TIMEOUT || statusCode == HTTP_TOO_MANY_REQUESTS || statusCode in 500..599

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

    private fun parseObject(
        body: String,
        description: String,
    ): Map<*, *> = parseJson(body, description) as? Map<*, *> ?: error("$description must be a JSON object.")

    private fun parseList(
        body: String,
        description: String,
    ): List<*> = parseJson(body, description) as? List<*> ?: error("$description must be a JSON array.")

    private fun parseJson(
        body: String,
        description: String,
    ): Any =
        try {
            JsonSlurper().parseText(safe(body))
        } catch (_: RuntimeException) {
            error("$description returned invalid JSON.")
        }

    private fun Any?.asRequiredObject(description: String): Map<*, *> = this as? Map<*, *> ?: error("$description must be an object.")

    private fun Map<*, *>.requiredString(name: String): String = (this[name] as? String)?.takeIf(String::isNotBlank) ?: error("Modrinth field $name must be a non-blank string.")

    private fun Map<*, *>.optionalString(name: String): String? {
        val value = this[name]
        if (containsKey(name).not() || value == null) return null
        return value as? String ?: error("Modrinth field $name must be a string or null.")
    }

    private fun Map<*, *>.requiredBoolean(name: String): Boolean = this[name] as? Boolean ?: error("Modrinth field $name must be a boolean.")

    private fun Map<*, *>.requiredLong(name: String): Long =
        when (val value = this[name]) {
            is Byte -> value.toLong()
            is Short -> value.toLong()
            is Int -> value.toLong()
            is Long -> value
            is BigInteger -> value.toLong().takeIf { converted -> BigInteger.valueOf(converted) == value }
            else -> null
        } ?: error("Modrinth field $name must be a 64-bit JSON integer.")

    private fun Map<*, *>.requiredInt(name: String): Int =
        requiredLong(name)
            .let { value -> value.toInt().takeIf { converted -> converted.toLong() == value } }
            ?: error("Modrinth field $name must be a 32-bit JSON integer.")

    private fun Map<*, *>.requiredList(name: String): List<*> = this[name] as? List<*> ?: error("Modrinth field $name must be an array.")

    private fun Map<*, *>.requiredMap(name: String): Map<*, *> = this[name] as? Map<*, *> ?: error("Modrinth field $name must be an object.")

    private fun Map<*, *>.stringSet(name: String): Set<String> = requiredList(name).map { item -> item as? String ?: error("Modrinth field $name must contain strings.") }.toSet()

    /**
     * Signals that a write may have reached Modrinth and authoritative state must be queried before retrying.
     */
    internal class AmbiguousWriteException(
        message: String,
        val retryAfterMillis: Long,
    ) : RuntimeException(message)

    /**
     * Signals that Modrinth deterministically rejected a write with a non-retryable client status.
     *
     * The exception retains only the status code and never reads or retains response content or credentials.
     * A coordinator may recover it only after an earlier ambiguous write and bounded authoritative reads prove that exact state appeared.
     *
     * @property statusCode HTTP client-error status returned by Modrinth.
     */
    internal class WriteRejectedException(
        val statusCode: Int,
    ) : RuntimeException("Modrinth rejected the write with HTTP $statusCode.")

    /**
     * Streamed size and hashes of a downloaded remote object.
     */
    internal data class DownloadedFile(
        val size: Long,
        val sha256: String,
        val sha512: String,
    )

    /**
     * Typed Modrinth project response required for release reconciliation.
     */
    internal data class RemoteProject(
        val id: String,
        val slug: String,
        val title: String,
        val description: String,
        val body: String,
        val projectType: ProjectType,
        val status: ProjectStatus,
        val categories: Set<String>,
        val additionalCategories: Set<String>,
        val environments: Set<VersionEnvironment>,
        val licenseId: String,
        val clientSide: SideSupport,
        val serverSide: SideSupport,
        val sourceUrl: String?,
        val issuesUrl: String?,
        val documentationUrl: String?,
        val rawIconUrl: String?,
        val gallery: List<RemoteGalleryImage>,
    )

    /**
     * Typed Modrinth gallery metadata.
     */
    internal data class RemoteGalleryImage(
        val rawUrl: String,
        val featured: Boolean,
        val title: String?,
        val description: String?,
        val ordering: Int,
    )

    /**
     * Typed project disclosure returned by the v3 disclosure endpoint, including its soft-deletion state.
     */
    internal data class RemoteDisclosure(
        val type: DisclosureType,
        val note: String?,
        val aiUses: Set<AiUse>,
        val deleted: Boolean = false,
    )

    /**
     * Typed Modrinth version response required for exact idempotency.
     */
    internal data class RemoteVersion(
        val id: String,
        val projectId: String,
        val name: String,
        val versionNumber: String,
        val changelog: String?,
        val dependencies: List<RemoteDependency>,
        val gameVersions: Set<String>,
        val releaseType: ReleaseType,
        val loaders: Set<Loader>,
        val featured: Boolean,
        val status: VersionStatus,
        val environment: VersionEnvironment,
        val files: List<RemoteFile>,
    )

    /**
     * Typed project dependency attached to a Modrinth version.
     */
    internal data class RemoteDependency(
        val projectId: String?,
        val versionId: String?,
        val fileName: String?,
        val type: DependencyType,
    )

    /**
     * Typed primary-file metadata attached to a Modrinth version.
     */
    internal data class RemoteFile(
        val fileName: String,
        val size: Long,
        val primary: Boolean,
        val url: String,
        val sha256: String?,
        val sha512: String,
    )

    /**
     * Lifecycle status of a Modrinth project.
     */
    internal enum class ProjectStatus(
        val wireValue: String,
    ) {
        /**
         * Publicly approved project.
         */
        APPROVED("approved"),

        /**
         * Archived project.
         */
        ARCHIVED("archived"),

        /**
         * Rejected project.
         */
        REJECTED("rejected"),

        /**
         * Unsubmitted mutable project draft.
         */
        DRAFT("draft"),

        /**
         * Mutable project hidden from listings.
         */
        UNLISTED("unlisted"),

        /**
         * Project undergoing review.
         */
        PROCESSING("processing"),

        /**
         * Project withheld from publication.
         */
        WITHHELD("withheld"),

        /**
         * Project scheduled for publication.
         */
        SCHEDULED("scheduled"),

        /**
         * Private project.
         */
        PRIVATE("private"),

        /**
         * Legacy unknown project state returned explicitly by Modrinth.
         */
        UNKNOWN("unknown"),
        ;

        /**
         * Decodes project lifecycle values at the API boundary.
         */
        companion object {
            /**
             * Decodes one API value and rejects future unknown discriminators.
             */
            fun decode(value: String): ProjectStatus =
                entries.singleOrNull { status -> status.wireValue == value }
                    ?: error("Unknown Modrinth project status: $value")
        }
    }

    /**
     * Project kind exposed by Modrinth.
     */
    internal enum class ProjectType(
        val wireValue: String,
    ) {
        /**
         * Versionless project whose public type has not yet been inferred from a loader.
         */
        UNCLASSIFIED("project"),

        /**
         * Mod project.
         */
        MOD("mod"),

        /**
         * Modpack project.
         */
        MODPACK("modpack"),

        /**
         * Resource-pack project.
         */
        RESOURCE_PACK("resourcepack"),

        /**
         * Shader project.
         */
        SHADER("shader"),
        ;

        /**
         * Decodes project kinds at the API boundary.
         */
        companion object {
            /**
             * Decodes one API value and rejects future unknown discriminators.
             */
            fun decode(value: String): ProjectType =
                entries.singleOrNull { type -> type.wireValue == value }
                    ?: error("Unknown Modrinth project type: $value")
        }
    }

    /**
     * Deprecated side-support value still verified for project metadata compatibility.
     */
    internal enum class SideSupport(
        val wireValue: String,
    ) {
        /**
         * Side is required.
         */
        REQUIRED("required"),

        /**
         * Side is optional.
         */
        OPTIONAL("optional"),

        /**
         * Side is unsupported.
         */
        UNSUPPORTED("unsupported"),

        /**
         * Legacy unknown side support returned explicitly by Modrinth.
         */
        UNKNOWN("unknown"),
        ;

        /**
         * Decodes side-support values at the API boundary.
         */
        companion object {
            /**
             * Decodes one API value and rejects future unknown discriminators.
             */
            fun decode(value: String): SideSupport =
                entries.singleOrNull { support -> support.wireValue == value }
                    ?: error("Unknown Modrinth side support: $value")
        }
    }

    /**
     * Visibility state of a Modrinth version.
     */
    internal enum class VersionStatus(
        val wireValue: String,
    ) {
        /**
         * Publicly listed version.
         */
        LISTED("listed"),

        /**
         * Archived version.
         */
        ARCHIVED("archived"),

        /**
         * Draft version.
         */
        DRAFT("draft"),

        /**
         * Unlisted version.
         */
        UNLISTED("unlisted"),

        /**
         * Scheduled version.
         */
        SCHEDULED("scheduled"),

        /**
         * Legacy unknown version state returned explicitly by Modrinth.
         */
        UNKNOWN("unknown"),
        ;

        /**
         * Decodes version visibility at the API boundary.
         */
        companion object {
            /**
             * Decodes one API value and rejects future unknown discriminators.
             */
            fun decode(value: String): VersionStatus =
                entries.singleOrNull { status -> status.wireValue == value }
                    ?: error("Unknown Modrinth version status: $value")
        }
    }

    /**
     * Release channel of a Modrinth version.
     */
    internal enum class ReleaseType(
        val wireValue: String,
    ) {
        /**
         * Stable release channel.
         */
        RELEASE("release"),

        /**
         * Beta release channel.
         */
        BETA("beta"),

        /**
         * Alpha release channel.
         */
        ALPHA("alpha"),
        ;

        /**
         * Decodes release channels at the API boundary.
         */
        companion object {
            /**
             * Decodes one API value and rejects future unknown discriminators.
             */
            fun decode(value: String): ReleaseType =
                entries.singleOrNull { type -> type.wireValue == value }
                    ?: error("Unknown Modrinth release type: $value")
        }
    }

    /**
     * Loader identifier attached to a Modrinth version.
     */
    internal enum class Loader(
        val wireValue: String,
    ) {
        /**
         * Fabric loader.
         */
        FABRIC("fabric"),
        ;

        /**
         * Decodes loader identifiers at the API boundary.
         */
        companion object {
            /**
             * Decodes one API value and rejects unsupported loaders.
             */
            fun decode(value: String): Loader =
                entries.singleOrNull { loader -> loader.wireValue == value }
                    ?: error("Unsupported Modrinth loader: $value")
        }
    }

    /**
     * Runtime environment attached to a Modrinth project or version.
     */
    internal enum class VersionEnvironment(
        val wireValue: String,
    ) {
        /**
         * Client and server are both required.
         */
        CLIENT_AND_SERVER("client_and_server"),

        /**
         * Client is required and server is unsupported.
         */
        CLIENT_ONLY("client_only"),

        /**
         * Client is required and server is optional.
         */
        CLIENT_ONLY_SERVER_OPTIONAL("client_only_server_optional"),

        /**
         * Singleplayer-only environment.
         */
        SINGLEPLAYER_ONLY("singleplayer_only"),

        /**
         * Server is required and client is unsupported.
         */
        SERVER_ONLY("server_only"),

        /**
         * Server is required and client is optional.
         */
        SERVER_ONLY_CLIENT_OPTIONAL("server_only_client_optional"),

        /**
         * Dedicated-server-only environment.
         */
        DEDICATED_SERVER_ONLY("dedicated_server_only"),

        /**
         * Either client or server is sufficient.
         */
        CLIENT_OR_SERVER("client_or_server"),

        /**
         * Either side works, with both preferred.
         */
        CLIENT_OR_SERVER_PREFERS_BOTH("client_or_server_prefers_both"),

        /**
         * Legacy unknown environment returned explicitly by Modrinth.
         */
        UNKNOWN("unknown"),
        ;

        /**
         * Decodes runtime environments at the API boundary.
         */
        companion object {
            /**
             * Decodes one API value and rejects future unknown discriminators.
             */
            fun decode(value: String): VersionEnvironment =
                entries.singleOrNull { environment -> environment.wireValue == value }
                    ?: error("Unknown Modrinth environment: $value")
        }
    }

    /**
     * Dependency relationship attached to a Modrinth version.
     */
    internal enum class DependencyType(
        val wireValue: String,
    ) {
        /**
         * Required dependency.
         */
        REQUIRED("required"),

        /**
         * Optional dependency.
         */
        OPTIONAL("optional"),

        /**
         * Incompatible dependency.
         */
        INCOMPATIBLE("incompatible"),

        /**
         * Embedded dependency.
         */
        EMBEDDED("embedded"),
        ;

        /**
         * Decodes dependency relationships at the API boundary.
         */
        companion object {
            /**
             * Decodes one API value and rejects future unknown discriminators.
             */
            fun decode(value: String): DependencyType =
                entries.singleOrNull { type -> type.wireValue == value }
                    ?: error("Unknown Modrinth dependency type: $value")
        }
    }

    /**
     * Top-level Modrinth project disclosure kind.
     */
    internal enum class DisclosureType(
        val wireValue: String,
    ) {
        /**
         * AI-content disclosure.
         */
        AI_CONTENT("ai_content"),

        /**
         * Advertising disclosure.
         */
        ADVERTISEMENTS("advertisements"),

        /**
         * Epilepsy-trigger disclosure.
         */
        EPILEPSY_TRIGGERS("epilepsy_triggers"),

        /**
         * System-interaction disclosure.
         */
        SYSTEM_INTERACTIONS("system_interactions"),

        /**
         * Telemetry disclosure.
         */
        TELEMETRY("telemetry"),

        /**
         * Derivative-work disclosure.
         */
        DERIVATIVE_WORK("derivative_work"),

        /**
         * Paid-feature disclosure.
         */
        PAID_FEATURES("paid_features"),

        /**
         * Archived disclosure.
         */
        ARCHIVED("archived"),
        ;

        /**
         * Decodes disclosure kinds at the API boundary.
         */
        companion object {
            /**
             * Decodes one API value and rejects future unknown discriminators.
             */
            fun decode(value: String): DisclosureType =
                entries.singleOrNull { type -> type.wireValue == value }
                    ?: error("Unknown Modrinth disclosure type: $value")
        }
    }

    /**
     * AI contribution area nested in the Modrinth AI-content disclosure.
     */
    internal enum class AiUse(
        val wireValue: String,
    ) {
        /**
         * Source-code assistance.
         */
        CODE("code"),

        /**
         * Asset assistance.
         */
        ASSETS("assets"),

        /**
         * Text assistance.
         */
        TEXT("text"),

        /**
         * Functional-design assistance.
         */
        FUNCTIONALITY("functionality"),
        ;

        /**
         * Decodes AI contribution areas at the API boundary.
         */
        companion object {
            /**
             * Decodes one API value and rejects future unknown discriminators.
             */
            fun decode(value: String): AiUse =
                entries.singleOrNull { use -> use.wireValue == value }
                    ?: error("Unknown Modrinth AI use: $value")
        }
    }

    private fun MessageDigest.hex(): String = digest().joinToString("") { byte -> "%02x".format(byte) }

    private enum class DownloadScheme(
        val wireValue: String,
    ) {
        HTTP("http"),
        HTTPS("https"),
        ;

        companion object {
            fun decode(value: String?): DownloadScheme =
                entries.singleOrNull { scheme -> scheme.wireValue == value }
                    ?: error("Unsupported download URL scheme: $value")
        }
    }

    /**
     * Owns bounded retry and transport constants for the client.
     */
    companion object {
        private const val MAX_ATTEMPTS = 4
        private const val MAX_REDIRECTS = 3
        private const val DEFAULT_RETRY_MILLIS = 250L
        private const val MAX_RETRY_MILLIS = 10_000L
        private const val MAX_ERROR_BODY_LENGTH = 2_000
        private const val HTTP_REQUEST_TIMEOUT = 408
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val MODRINTH_CDN_HOST = "cdn.modrinth.com"
    }
}
