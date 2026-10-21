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
import dev.s7a.strata.gradle.release.ModrinthReleaseFixture.Companion.GAME_VERSIONS
import dev.s7a.strata.gradle.release.ModrinthReleaseFixture.Companion.PROJECT_ID
import groovy.json.JsonOutput
import java.net.InetSocketAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Owns the local project, release and CDN endpoints and their deterministic fault controls.
 */
internal class MockModrinthServer(
    private val fixture: ModrinthReleaseFixture,
) : AutoCloseable {
    private val executor = Executors.newCachedThreadPool()
    private val server = HttpServer.create(InetSocketAddress("localhost", 0), 0)
    private val versions = linkedSetOf<String>()
    private val delayedCreateResponseRelease = CountDownLatch(1)
    private var historicalVersionCount: Int = 0
    private var remoteCategories: Set<String> = fixture.manifest.project.categories
    private var remoteAdditionalCategories: Set<String> = fixture.manifest.project.additionalCategories
    private var remoteEnvironments: Set<VersionEnvironment> = setOf(VersionEnvironment.CLIENT_ONLY_SERVER_OPTIONAL)
    private var remoteClientSide: SideSupport = fixture.manifest.project.clientSide
    private var remoteServerSide: SideSupport = fixture.manifest.project.serverSide
    var remoteProjectBody: String = fixture.manifest.project.body
    private var remoteDisclosures: List<RemoteDisclosure> =
        listOf(
            RemoteDisclosure(
                type = DisclosureType.AI_CONTENT,
                note = fixture.manifest.project.aiDisclosureNote,
                aiUses = fixture.manifest.project.aiDisclosureUses,
            ),
        )

    var projectStatus: ProjectStatus = ProjectStatus.DRAFT
    var projectStatusAfterFirstRead: ProjectStatus? = null
    var projectType: ProjectType = ProjectType.MOD
    var projectTypeAfterFirstVersion: ProjectType? = null
    var createRequests: Int = 0
    var submitRequests: Int = 0
    var projectBootstrapRequests: Int = 0
    var projectBodyUpdateRequests: Int = 0
    var disclosureBootstrapRequests: Int = 0
    var projectBootstrapRecoveryReads: Int = 0
    var versionReadRequests: Int = 0
    var ambiguousFirstCreate: Boolean = false
    var ambiguousFirstBodyUpdateAfterCommit: Boolean = false
    var ambiguousFirstSubmitAfterCommit: Boolean = false
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
    var initialBodyUpdateRejectionStatus: Int? = null
    var initialDisclosureBootstrapRejectionStatus: Int? = null
    var initialSubmitRejectionStatus: Int? = null
    var repeatSubmitRejectionStatus: Int = 400
    var projectStatusAfterRepeatSubmitRejection: ProjectStatus? = null
    var remainingStaleVersionReadsAfterFirstCreate: Int = 0
    var remainingStaleProjectReadsAfterBodyUpdate: Int = 0
    var remainingStaleProjectReadsAfterSubmit: Int = 0
    private var staleProjectBody: String? = null
    private val projectReadRequestCount = AtomicInteger()
    val projectReadRequests: Int
        get() = projectReadRequestCount.get()
    var projectResponseDelayMillis: Long = 0L
    var projectResponseMutation: (MutableMap<String, Any?>) -> Unit = { _ -> }
    var overviewGalleryResponseMutation: (MutableMap<String, Any?>) -> Unit = { _ -> }
    val transientProjectStatuses: ArrayDeque<Int> = ArrayDeque()
    val downloadRequests: MutableSet<String> = linkedSetOf()
    val writeEvents: MutableList<WriteEvent> = mutableListOf()
    var projectBootstrapPayloadKeys: Set<String> = emptySet()
    val responses = MockModrinthVersionResponses(fixture) { baseUrl }
    val baseUrl: String
        get() = "http://localhost:${server.address.port}"
    val projectBootstrapIsExact: Boolean
        get() =
            remoteCategories == fixture.manifest.project.categories &&
                remoteAdditionalCategories == fixture.manifest.project.additionalCategories &&
                remoteEnvironments == setOf(VersionEnvironment.CLIENT_ONLY_SERVER_OPTIONAL) &&
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

    private val methodRoutes =
        mapOf(
            (HttpMethod.GET to URI("/v2/project/$PROJECT_ID")) to ::project,
            (HttpMethod.PATCH to URI("/v2/project/$PROJECT_ID")) to ::modifyProject,
            (HttpMethod.PATCH to URI("/v3/project/$PROJECT_ID")) to ::bootstrapProject,
            (HttpMethod.POST to URI("/v2/version")) to ::create,
        )
    private val pathRoutes =
        mapOf(
            URI("/v3/project/$PROJECT_ID/disclosures") to ::disclosures,
            URI("/v2/tag/game_version") to ::gameVersions,
            URI("/v2/project/$PROJECT_ID/version") to ::versions,
        )
    private val cdnKinds =
        mapOf("icon" to CdnKind.Icon, "redirect" to CdnKind.Redirect, "failing" to CdnKind.Failure) +
            fixture.manifest.project.gallery
                .associate { it.id to CdnKind.Gallery } +
            fixture.manifest.project.gallery
                .associate { "optimized-${it.id}" to CdnKind.OptimizedGallery }

    /**
     * Starts the native HTTP server using its owned executor.
     */
    fun start() {
        server.executor = executor
        server.createContext("/", ::handle)
        server.start()
    }

    /**
     * Marks the requested prefix of release targets as already committed.
     */
    fun seedVersions(count: Int) {
        fixture.manifest.artifacts
            .take(count)
            .forEach { artifact -> versions += artifact.versionNumber }
    }

    /**
     * Adds predecessor releases independently of the current staged targets.
     */
    fun seedHistoricalVersions(count: Int) {
        historicalVersionCount = count
    }

    /**
     * Clears classification fields to exercise safe draft bootstrap.
     */
    fun makeProjectBootstrapStateEmpty() {
        remoteCategories = emptySet()
        remoteAdditionalCategories = emptySet()
        remoteEnvironments = setOf(VersionEnvironment.UNKNOWN)
        remoteClientSide = SideSupport.UNKNOWN
        remoteServerSide = SideSupport.UNKNOWN
    }

    /**
     * Clears project and disclosure metadata before bootstrap.
     */
    fun makeBootstrapStateEmpty() {
        makeProjectBootstrapStateEmpty()
        remoteDisclosures = emptyList()
    }

    /**
     * Retains exact fields so bootstrap must patch only missing fields.
     */
    fun makeProjectBootstrapStatePartial() {
        remoteAdditionalCategories = emptySet()
        remoteClientSide = SideSupport.UNKNOWN
    }

    /**
     * Introduces conflicting nonempty classification that must block writes.
     */
    fun makeProjectBootstrapStateConflict() {
        remoteCategories = setOf("adventure")
    }

    /**
     * Introduces a disclosure that conflicts with the required release metadata.
     */
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

    /**
     * Retains a deleted disclosure that must not satisfy the active contract.
     */
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

    /**
     * Dispatches exact decoded URL paths and keeps all exchanges inside the server's error boundary.
     */
    private fun handle(exchange: HttpExchange) {
        try {
            val path = exchange.requestURI.path
            val uri = URI(null, null, path, null, null)
            val route = methodRoutes[HttpMethod.decode(exchange.requestMethod) to uri] ?: pathRoutes[uri]
            when {
                route != null -> route(exchange)
                path.startsWith("/cdn/") -> download(exchange, path.removePrefix("/cdn/"))
                else -> exchange.respond(404, "{}")
            }
        } catch (failure: Throwable) {
            runCatching { exchange.respond(500, JsonOutput.toJson(mapOf("error" to failure.message.orEmpty()))) }
        } finally {
            exchange.close()
        }
    }

    private fun project(exchange: HttpExchange) {
        val currentProjectReadRequests = projectReadRequestCount.incrementAndGet()
        if (1 < currentProjectReadRequests) {
            projectStatusAfterFirstRead?.let { status -> projectStatus = status }
        }
        if (rateLimitFirstProjectBootstrapBeforeCommit && projectBootstrapRequests == 1) {
            projectBootstrapRecoveryReads += 1
        }
        if (0L < projectResponseDelayMillis) Thread.sleep(projectResponseDelayMillis)
        if (transientProjectStatuses.isNotEmpty()) {
            exchange.respond(transientProjectStatuses.removeFirst(), "transient")
            return
        }
        val failure = projectFailureBody
        if (failure != null) {
            exchange.respond(400, failure)
            return
        }
        val project = fixture.manifest.project
        val visibleStatus = visibleProjectStatus()
        val visibleBody = visibleProjectBody()
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
                "body" to visibleBody,
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
        exchange.respond(
            200,
            JsonOutput.toJson(response),
        )
    }

    /**
     * Exposes deliberately stale submission status for the configured recovery reads.
     */
    private fun visibleProjectStatus(): ProjectStatus =
        if (0 < submitRequests && 0 < remainingStaleProjectReadsAfterSubmit) {
            remainingStaleProjectReadsAfterSubmit -= 1
            ProjectStatus.DRAFT
        } else {
            projectStatus
        }

    /**
     * Exposes the predecessor body until the configured recovery reads have elapsed.
     */
    private fun visibleProjectBody(): String =
        if (0 < remainingStaleProjectReadsAfterBodyUpdate) {
            remainingStaleProjectReadsAfterBodyUpdate -= 1
            staleProjectBody ?: remoteProjectBody
        } else {
            remoteProjectBody
        }

    private fun modifyProject(exchange: HttpExchange) {
        val payload = exchange.requestObject()
        when (payload.keys) {
            setOf("body") -> updateProjectBody(exchange, payload)
            setOf("requested_status") -> submit(exchange)
            else -> error("Unexpected v2 project patch fields: ${payload.keys}")
        }
    }

    private fun updateProjectBody(
        exchange: HttpExchange,
        payload: Map<*, *>,
    ) {
        projectBodyUpdateRequests += 1
        writeEvents += WriteEvent.PATCH_PROJECT_BODY
        check(payload["body"] == fixture.manifest.project.body)
        val rejection = initialBodyUpdateRejectionStatus
        if (projectBodyUpdateRequests == 1 && rejection != null) {
            exchange.respond(rejection, "rejected")
            return
        }
        staleProjectBody = remoteProjectBody
        remoteProjectBody = fixture.manifest.project.body
        if (ambiguousFirstBodyUpdateAfterCommit && projectBodyUpdateRequests == 1) {
            exchange.respond(503, "temporarily unavailable")
        } else {
            exchange.respond(204, "")
        }
    }

    private fun bootstrapProject(exchange: HttpExchange) {
        projectBootstrapRequests += 1
        writeEvents += WriteEvent.PATCH_PROJECT
        val project = fixture.manifest.project
        val payload = exchange.requestObject()
        val expectedPayload = linkedMapOf<String, Any>()
        if (remoteCategories != project.categories) expectedPayload["categories"] = project.categories.toList()
        if (remoteAdditionalCategories != project.additionalCategories) {
            expectedPayload["additional_categories"] = project.additionalCategories.toList()
        }
        if (
            remoteEnvironments != setOf(VersionEnvironment.CLIENT_ONLY_SERVER_OPTIONAL) ||
            remoteClientSide != project.clientSide ||
            remoteServerSide != project.serverSide
        ) {
            expectedPayload["environment"] = VersionEnvironment.CLIENT_ONLY_SERVER_OPTIONAL.wireValue
        }
        check(expectedPayload.isNotEmpty())
        check(payload == expectedPayload)
        projectBootstrapPayloadKeys = payload.keys.filterIsInstance<String>().toSet()
        if (rateLimitFirstProjectBootstrapBeforeCommit && projectBootstrapRequests == 1) {
            exchange.respond(429, "rate limited")
            return
        }
        remoteCategories = project.categories
        remoteAdditionalCategories = project.additionalCategories
        remoteEnvironments = setOf(VersionEnvironment.CLIENT_ONLY_SERVER_OPTIONAL)
        remoteClientSide = project.clientSide
        remoteServerSide = project.serverSide
        exchange.respond(204, "")
    }

    private fun disclosures(exchange: HttpExchange) {
        if (HttpMethod.decode(exchange.requestMethod) == HttpMethod.PATCH) {
            bootstrapDisclosures(exchange)
        } else {
            exchange.respond(
                200,
                JsonOutput.toJson(
                    mapOf(
                        "disclosures" to
                            remoteDisclosures.map { disclosure ->
                                linkedMapOf(
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
        val payload = exchange.requestObject()
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
            exchange.respond(rejection, "rejected")
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
        exchange.respond(204, "")
    }

    private fun gameVersions(exchange: HttpExchange) {
        exchange.respond(200, JsonOutput.toJson(GAME_VERSIONS.map { version -> mapOf("version" to version) }))
    }

    private fun versions(exchange: HttpExchange) {
        versionReadRequests += 1
        val firstVersionNumber =
            fixture.manifest.artifacts
                .first()
                .versionNumber
        if (timeoutFirstCreateAfterCommit && firstVersionNumber in versions) {
            delayedCreateResponseRelease.countDown()
        }
        val visibleVersions =
            if (
                firstVersionNumber in versions &&
                0 < remainingStaleVersionReadsAfterFirstCreate
            ) {
                remainingStaleVersionReadsAfterFirstCreate -= 1
                versions.drop(1)
            } else {
                versions
            }
        exchange.respond(
            200,
            JsonOutput.toJson(
                visibleVersions
                    .map { number ->
                        val artifact = fixture.manifest.artifacts.single { candidate -> candidate.versionNumber == number }
                        responses.version(artifact)
                    }.plus(
                        fixture.manifest.artifacts
                            .drop(1)
                            .take(historicalVersionCount)
                            .map(responses::historicalVersion),
                    ).let { expected ->
                        if (unexpectedVersionPresent) expected + responses.unexpectedVersion() else expected
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
        if (rejectInitialCreate(exchange)) return
        if (versions.add(artifact.versionNumber).not()) {
            exchange.respond(
                400,
                JsonOutput.toJson(mapOf("error" to "duplicate_version", "description" to "Version already exists.")),
            )
            return
        }
        if (versions.size == 1) {
            projectTypeAfterFirstVersion?.let { inferredType -> projectType = inferredType }
        }
        if (timeoutFirstCreateAfterCommit && createRequests == 1) {
            check(delayedCreateResponseRelease.await(10L, TimeUnit.SECONDS)) {
                "Timed out waiting for the create-timeout recovery read."
            }
            exchange.respond(200, JsonOutput.toJson(responses.version(artifact)))
        } else if (ambiguousFirstCreate && createRequests == 1) {
            exchange.respond(503, "temporarily unavailable")
        } else {
            exchange.respond(200, JsonOutput.toJson(responses.version(artifact)))
        }
    }

    /**
     * Applies the selected first-request failure before any version is committed.
     */
    private fun rejectInitialCreate(exchange: HttpExchange): Boolean {
        val initialRejection = initialCreateRejectionStatus
        if (createRequests == 1 && initialRejection != null) {
            exchange.respond(
                initialRejection,
                JsonOutput.toJson(mapOf("error" to "duplicate_version", "description" to "Initial create rejected.")),
            )
            return true
        }
        if (rateLimitFirstCreateBeforeCommit && createRequests == 1) {
            exchange.respond(429, "rate limited")
            return true
        }
        if (requestTimeoutFirstCreateBeforeCommit && createRequests == 1) {
            exchange.respond(408, "request timed out")
            return true
        }
        return false
    }

    private fun submit(exchange: HttpExchange) {
        submitRequests += 1
        val initialRejection = initialSubmitRejectionStatus
        if (submitRequests == 1 && initialRejection != null) {
            exchange.respond(
                initialRejection,
                JsonOutput.toJson(mapOf("error" to "rejected_submission", "description" to "Initial submit rejected.")),
            )
            return
        }
        if (1 < submitRequests) {
            projectStatusAfterRepeatSubmitRejection?.let { status -> projectStatus = status }
            exchange.respond(
                repeatSubmitRejectionStatus,
                JsonOutput.toJson(mapOf("error" to "rejected_submission", "description" to "Project was already submitted.")),
            )
            return
        }
        projectStatus = ProjectStatus.PROCESSING
        if (ambiguousFirstSubmitAfterCommit) {
            exchange.respond(503, "temporarily unavailable")
        } else {
            exchange.respond(204, "")
        }
    }

    private fun download(
        exchange: HttpExchange,
        name: String,
    ) {
        if (exchange.requestHeaders.containsKey("Authorization")) cdnReceivedAuthorization = true
        val kind = cdnKinds[name] ?: CdnKind.Artifact
        if (escapeCdnRedirect && kind == CdnKind.Icon) {
            exchange.responseHeaders.add("Location", "https://example.com/escaped")
            exchange.respond(302, "")
            return
        }
        if (redirectToFailingCdn && kind == CdnKind.Redirect) {
            exchange.responseHeaders.add("Location", "$baseUrl/cdn/failing")
            exchange.respond(302, "")
            return
        }
        if (redirectToFailingCdn && kind == CdnKind.Failure) {
            failingDownloadRequests += 1
            exchange.respond(503, "transient")
            return
        }
        val bytes =
            when (kind) {
                CdnKind.Icon -> {
                    fixture.iconBytes
                }

                CdnKind.Gallery -> {
                    "gallery-$name".toByteArray(StandardCharsets.UTF_8)
                }

                CdnKind.OptimizedGallery -> {
                    optimizedGalleryDownloadRequests += 1
                    "optimized-$name".toByteArray(StandardCharsets.UTF_8)
                }

                CdnKind.Artifact, CdnKind.Redirect, CdnKind.Failure -> {
                    downloadRequests += name
                    fixture.bundle.resolve("artifacts/$name").readBytes()
                }
            }
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.write(bytes)
    }

    /**
     * Records only actual API writes so every fail-before-write contract is observable.
     */
    enum class WriteEvent {
        CREATE_VERSION,
        PATCH_PROJECT_BODY,
        PATCH_PROJECT,
        PATCH_DISCLOSURES,
    }

    /**
     * Decodes HTTP transport methods while retaining an unmatched fallback for unsupported methods.
     */
    private enum class HttpMethod {
        GET,
        PATCH,
        POST,
        OTHER,
        ;

        companion object {
            /**
             * Selects the known exact wire method without broadening unsupported routes.
             */
            fun decode(value: String): HttpMethod = entries.firstOrNull { it.name == value } ?: OTHER
        }
    }

    /**
     * Separates fixed fixture asset roles from arbitrary release-artifact filenames.
     */
    private enum class CdnKind {
        Icon,
        Redirect,
        Failure,
        Gallery,
        OptimizedGallery,
        Artifact,
    }
}
