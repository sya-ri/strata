package dev.s7a.strata.gradle.release

import dev.s7a.strata.gradle.release.ModrinthApiClient.AmbiguousWriteException
import dev.s7a.strata.gradle.release.ModrinthApiClient.DependencyType
import dev.s7a.strata.gradle.release.ModrinthApiClient.DisclosureType
import dev.s7a.strata.gradle.release.ModrinthApiClient.DownloadedFile
import dev.s7a.strata.gradle.release.ModrinthApiClient.Loader
import dev.s7a.strata.gradle.release.ModrinthApiClient.ProjectStatus
import dev.s7a.strata.gradle.release.ModrinthApiClient.ProjectType
import dev.s7a.strata.gradle.release.ModrinthApiClient.ReleaseType
import dev.s7a.strata.gradle.release.ModrinthApiClient.RemoteProject
import dev.s7a.strata.gradle.release.ModrinthApiClient.RemoteVersion
import dev.s7a.strata.gradle.release.ModrinthApiClient.VersionEnvironment
import dev.s7a.strata.gradle.release.ModrinthApiClient.VersionStatus
import dev.s7a.strata.gradle.release.ModrinthApiClient.WriteRejectedException
import java.io.File
import java.security.MessageDigest

/**
 * Reconciles one complete release against authoritative Modrinth state.
 *
 * Reconciliation is exact and monotonic.
 * Existing remote data is never overwritten or deleted, and every ambiguous write is followed by an authoritative read before a bounded retry.
 * The coordinator is thread-confined to its calling Gradle task.
 */
internal class ModrinthReleaseCoordinator(
    private val manifest: ModrinthManifest,
    private val bundleDirectory: File,
    private val api: ModrinthApiClient,
) {
    /** Verifies project metadata, assets, tags, and existing release targets without writing. */
    fun preflight(): Receipt {
        val snapshot = reconcile(requirePresent = false, requireApproved = false)
        if (snapshot.projectStatus in READ_ONLY_PROJECT_STATUSES) {
            check(snapshot.absent.isEmpty()) {
                "A processing or approved Modrinth project is read-only and cannot receive missing release versions."
            }
        }
        return snapshot.receipt(Operation.PREFLIGHT)
    }

    /** Creates only missing exact listed versions while the project remains draft or unlisted. */
    fun stage(): Receipt {
        val initial = reconcile(requirePresent = false, requireApproved = false)
        when (initial.projectStatus) {
            in MUTABLE_PROJECT_STATUSES -> initial.absent.forEach(::createWithRequery)
            in READ_ONLY_PROJECT_STATUSES ->
                check(initial.absent.isEmpty()) {
                    "A processing or approved Modrinth project is read-only and cannot receive missing release versions."
                }
            else -> error("Unsupported Modrinth project lifecycle state.")
        }
        return reconcile(requirePresent = true, requireApproved = false).receipt(Operation.STAGE)
    }

    /** Submits a completely listed draft or unlisted project for review and otherwise polls only. */
    fun submit(): Receipt {
        val initial = reconcile(requirePresent = true, requireApproved = false)
        when (initial.projectStatus) {
            in MUTABLE_PROJECT_STATUSES -> submitWithRequery()
            in READ_ONLY_PROJECT_STATUSES -> Unit
            else -> error("Unsupported Modrinth project lifecycle state.")
        }
        val result = reconcile(requirePresent = true, requireApproved = false)
        check(result.projectStatus in READ_ONLY_PROJECT_STATUSES) {
            "Modrinth project submission did not reach processing or approved state."
        }
        return result.receipt(Operation.SUBMIT)
    }

    /** Verifies approval, exact listed metadata, and bytes returned by every primary CDN URL. */
    fun verify(): Receipt {
        val snapshot = reconcile(requirePresent = true, requireApproved = true)
        snapshot.listed.forEach { target ->
            assertHash("CDN artifact ${target.artifact.fileName}", api.hashRemoteFile(target.url), target.artifact)
        }
        return snapshot.receipt(Operation.VERIFY)
    }

    private fun reconcile(
        requirePresent: Boolean,
        requireApproved: Boolean,
    ): Snapshot {
        manifest.validate()
        check(manifest.projectId.isNotBlank()) {
            "A stable Modrinth project ID is required through strata.modrinthProjectId or MODRINTH_PROJECT_ID."
        }
        val project = api.getProject(manifest.projectId)
        check(project.id == manifest.projectId) {
            "Configured Modrinth project ID ${manifest.projectId} does not match immutable remote ID ${project.id}."
        }
        check(project.projectType == ProjectType.MOD) { "The Modrinth release target must be a mod project." }
        check(project.status in MUTABLE_PROJECT_STATUSES + READ_ONLY_PROJECT_STATUSES) {
            "Modrinth project ${manifest.projectId} cannot participate in this release while status is ${project.status.wireValue}."
        }
        if (requireApproved) {
            check(project.status == ProjectStatus.APPROVED) {
                "Modrinth project ${manifest.projectId} must be approved for final verification."
            }
        }
        assertProjectMetadata(project)
        assertProjectDisclosures()
        assertProjectAssets(project)

        val supportedTags = api.getGameVersions()
        val unsupported = manifest.artifacts.map(ModrinthManifest.Artifact::gameVersion).filterNot(supportedTags::contains)
        check(unsupported.isEmpty()) { "Modrinth does not recognize these Minecraft versions: $unsupported" }

        val remote = api.getProjectVersions(manifest.projectId)
        val expectedNumbers = manifest.artifacts.map(ModrinthManifest.Artifact::versionNumber).toSet()
        val expectedHashes = manifest.artifacts.associateBy(ModrinthManifest.Artifact::sha512)
        remote.forEach { version ->
            version.files.forEach { file ->
                val expected = expectedHashes[file.sha512]
                check(expected == null || version.versionNumber == expected.versionNumber) {
                    "Artifact ${expected?.fileName} is already attached to unexpected Modrinth version ${version.versionNumber}."
                }
            }
            if (version.versionNumber in expectedNumbers) {
                check(remote.count { candidate -> candidate.versionNumber == version.versionNumber } == 1) {
                    "Modrinth contains duplicate version number ${version.versionNumber}."
                }
            }
        }

        val absent = mutableListOf<ModrinthManifest.Artifact>()
        val listed = mutableListOf<RemoteTarget>()
        manifest.artifacts.forEach { artifact ->
            val matching = remote.filter { version -> version.versionNumber == artifact.versionNumber }
            if (matching.isEmpty()) {
                check(requirePresent.not()) { "Required Modrinth version ${artifact.versionNumber} is absent." }
                absent += artifact
            } else {
                val version = matching.single()
                assertExact(version, artifact)
                check(version.status == VersionStatus.LISTED) {
                    "Modrinth version ${artifact.versionNumber} is not listed; refusing mutation."
                }
                val primary = version.files.single { file -> file.primary }
                listed += RemoteTarget(artifact, primary.url)
            }
        }
        return Snapshot(manifest.projectId, project.status, absent, listed)
    }

    private fun assertProjectMetadata(remote: RemoteProject) {
        val expected = manifest.project
        val differences = mutableListOf<String>()
        fun compare(name: String, actual: Any?, wanted: Any?) {
            if (actual != wanted) differences += "$name expected=$wanted actual=$actual"
        }
        compare("slug", remote.slug, expected.slug)
        compare("title", remote.title, expected.title)
        compare("description", remote.description, expected.description)
        compare("body", normalize(remote.body), normalize(expected.body))
        compare("categories", remote.categories, expected.categories)
        compare("additional_categories", remote.additionalCategories, expected.additionalCategories)
        compare("environment", remote.environments, setOf(VersionEnvironment.CLIENT_ONLY))
        compare("license.id", remote.licenseId, expected.licenseId)
        compare("client_side", remote.clientSide, expected.clientSide)
        compare("server_side", remote.serverSide, expected.serverSide)
        compare("source_url", remote.sourceUrl, expected.sourceUrl)
        compare("issues_url", remote.issuesUrl, expected.issuesUrl)
        compare("wiki_url", remote.documentationUrl, expected.documentationUrl)
        check(differences.isEmpty()) {
            "Modrinth project metadata differs from the tracked release contract: ${differences.joinToString("; ")}"
        }
    }

    private fun assertProjectDisclosures() {
        val disclosures = api.getProjectDisclosures(manifest.projectId)
        check(disclosures.size == 1) { "The Modrinth project must have exactly one content disclosure." }
        val disclosure = disclosures.single()
        check(disclosure.type == DisclosureType.AI_CONTENT) { "The only project disclosure must be AI content." }
        check(disclosure.note == manifest.project.aiDisclosureNote) { "The Modrinth AI disclosure statement differs." }
        check(disclosure.aiUses == manifest.project.aiDisclosureUses) {
            "The Modrinth AI disclosure must identify exactly code and text use."
        }
    }

    private fun assertProjectAssets(remote: RemoteProject) {
        val iconUrl = remote.rawIconUrl ?: error("The Modrinth project has no icon.")
        assertProjectAsset("project icon", iconUrl, manifest.project.icon)
        check(remote.gallery.size == manifest.project.gallery.size) {
            "The Modrinth gallery must contain exactly overview, inventory, and progress."
        }
        manifest.project.gallery.forEach { expected ->
            val matching = remote.gallery.filter { image -> image.title == expected.title }
            check(matching.size == 1) { "The Modrinth gallery image ${expected.id} is missing or duplicated." }
            val actual = matching.single()
            check(actual.featured == expected.featured) { "Gallery featured state differs for ${expected.id}." }
            check(actual.description == expected.description) { "Gallery description differs for ${expected.id}." }
            check(actual.ordering == expected.ordering) { "Gallery ordering differs for ${expected.id}." }
            assertProjectAsset(
                "gallery image ${expected.id}",
                actual.rawUrl,
                ModrinthManifest.ProjectAsset(expected.path, expected.sha256),
            )
        }
    }

    private fun assertProjectAsset(
        description: String,
        url: String,
        expected: ModrinthManifest.ProjectAsset,
    ) {
        val downloaded = api.hashRemoteFile(url)
        check(downloaded.sha256 == expected.sha256) { "Remote $description differs from tracked ${expected.path}." }
    }

    private fun assertExact(
        remote: RemoteVersion,
        expected: ModrinthManifest.Artifact,
    ) {
        val differences = mutableListOf<String>()
        fun compare(name: String, actual: Any?, wanted: Any?) {
            if (actual != wanted) differences += "$name expected=$wanted actual=$actual"
        }
        compare("project_id", remote.projectId, manifest.projectId)
        compare("name", remote.name, expected.versionName)
        compare("version_number", remote.versionNumber, expected.versionNumber)
        compare("changelog", normalize(remote.changelog), manifest.changelog)
        compare("game_versions", remote.gameVersions, setOf(expected.gameVersion))
        compare("version_type", remote.releaseType, ReleaseType.RELEASE)
        compare("loaders", remote.loaders, setOf(Loader.FABRIC))
        compare("featured", remote.featured, ModrinthManifest.FEATURED)
        compare("environment", remote.environment, VersionEnvironment.CLIENT_ONLY)
        compare(
            "dependencies",
            remote.dependencies.map { dependency ->
                listOf(dependency.projectId, dependency.versionId, dependency.fileName, dependency.type)
            },
            listOf(listOf(ModrinthManifest.FABRIC_LANGUAGE_KOTLIN_PROJECT_ID, null, null, DependencyType.REQUIRED)),
        )
        compare("file count", remote.files.size, 1)
        if (remote.files.size == 1) {
            val file = remote.files.single()
            compare("filename", file.fileName, expected.fileName)
            compare("file size", file.size, expected.size)
            compare("primary", file.primary, true)
            compare("sha512", file.sha512, expected.sha512)
            if (file.sha256 != null) compare("sha256", file.sha256, expected.sha256)
        }
        check(differences.isEmpty()) {
            "Existing Modrinth version ${expected.versionNumber} differs; refusing overwrite: ${differences.joinToString("; ")}"
        }
    }

    private fun createWithRequery(artifact: ModrinthManifest.Artifact) {
        val file = bundleDirectory.resolve(artifact.relativePath)
        assertHash("local artifact ${artifact.fileName}", file.hashes(), artifact)
        try {
            api.createListedVersion(manifest, artifact, file)
            return
        } catch (failure: AmbiguousWriteException) {
            pause(failure.retryAfterMillis)
            if (pollCreatedVersion(artifact, "Ambiguous create")) return
        }

        try {
            api.createListedVersion(manifest, artifact, file)
        } catch (failure: AmbiguousWriteException) {
            pause(failure.retryAfterMillis)
            if (pollCreatedVersion(artifact, "Ambiguous create retry")) return
            throw failure
        } catch (failure: WriteRejectedException) {
            if (pollCreatedVersion(artifact, "Rejected create retry")) return
            throw failure
        }
    }

    private fun pollCreatedVersion(
        artifact: ModrinthManifest.Artifact,
        operation: String,
    ): Boolean {
        repeat(MAX_STATE_POLLS) { poll ->
            val state = findRemote(artifact)
            if (state != null) {
                assertCreatedVersion(state, artifact, operation)
                return true
            }
            if (poll + 1 < MAX_STATE_POLLS) pause(POLL_DELAY_MILLIS)
        }
        return false
    }

    private fun assertCreatedVersion(
        state: RemoteVersion,
        artifact: ModrinthManifest.Artifact,
        operation: String,
    ) {
        assertExact(state, artifact)
        check(state.status == VersionStatus.LISTED) {
            "$operation produced a non-listed version for ${artifact.versionNumber}."
        }
    }

    private fun submitWithRequery() {
        try {
            api.submitProject(manifest.projectId)
        } catch (failure: AmbiguousWriteException) {
            pause(failure.retryAfterMillis)
            if (pollSubmitted()) return
            retrySubmitAfterAmbiguity()
            return
        }
        check(pollSubmitted()) {
            "Modrinth project submission was accepted but not observable after bounded polling."
        }
    }

    private fun retrySubmitAfterAmbiguity() {
        try {
            api.submitProject(manifest.projectId)
        } catch (failure: AmbiguousWriteException) {
            pause(failure.retryAfterMillis)
            if (pollSubmitted()) return
            throw failure
        } catch (failure: WriteRejectedException) {
            if (pollSubmitted()) return
            throw failure
        }
        check(pollSubmitted()) {
            "Modrinth project submission retry was accepted but not observable after bounded polling."
        }
    }

    private fun pollSubmitted(): Boolean {
        repeat(MAX_STATE_POLLS) { poll ->
            val status = api.getProject(manifest.projectId).status
            if (status in READ_ONLY_PROJECT_STATUSES) return true
            check(status in MUTABLE_PROJECT_STATUSES) {
                "Modrinth project entered unexpected status ${status.wireValue} after submission."
            }
            if (poll + 1 < MAX_STATE_POLLS) pause(POLL_DELAY_MILLIS)
        }
        return false
    }

    private fun findRemote(artifact: ModrinthManifest.Artifact): RemoteVersion? {
        val matching = api.getProjectVersions(manifest.projectId).filter { version ->
            version.versionNumber == artifact.versionNumber
        }
        check(matching.size <= 1) {
            "Modrinth contains duplicate version number ${artifact.versionNumber}."
        }
        return matching.singleOrNull()
    }

    private fun assertHash(
        description: String,
        actual: DownloadedFile,
        expected: ModrinthManifest.Artifact,
    ) {
        check(actual.size == expected.size) { "$description size differs." }
        check(actual.sha256 == expected.sha256) { "$description SHA-256 differs." }
        check(actual.sha512 == expected.sha512) { "$description SHA-512 differs." }
    }

    private fun File.hashes(): DownloadedFile {
        check(isFile) { "Canonical release file is missing: $this" }
        val sha256 = MessageDigest.getInstance("SHA-256")
        val sha512 = MessageDigest.getInstance("SHA-512")
        inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                sha256.update(buffer, 0, count)
                sha512.update(buffer, 0, count)
            }
        }
        return DownloadedFile(length(), sha256.hex(), sha512.hex())
    }

    private fun MessageDigest.hex(): String = digest().joinToString("") { byte -> "%02x".format(byte) }

    private fun normalize(value: String?): String = value.orEmpty().replace("\r\n", "\n").trimEnd() + "\n"

    private fun pause(delayMillis: Long) {
        if (0L < delayMillis) Thread.sleep(delayMillis.coerceAtMost(10_000L))
    }

    /** Credential-free result persisted by a network task for release review. */
    internal data class Receipt(
        val operation: String,
        val projectId: String,
        val projectStatus: String,
        val absent: List<String>,
        val listed: List<String>,
    )

    private data class Snapshot(
        val projectId: String,
        val projectStatus: ProjectStatus,
        val absent: List<ModrinthManifest.Artifact>,
        val listed: List<RemoteTarget>,
    ) {
        fun receipt(operation: Operation): Receipt =
            Receipt(
                operation = operation.wireValue,
                projectId = projectId,
                projectStatus = projectStatus.wireValue,
                absent = absent.map { artifact -> artifact.versionNumber },
                listed = listed.map { target -> target.artifact.versionNumber },
            )
    }

    private data class RemoteTarget(
        val artifact: ModrinthManifest.Artifact,
        val url: String,
    )

    private enum class Operation(
        val wireValue: String,
    ) {
        PREFLIGHT("preflight"),
        STAGE("stage"),
        SUBMIT("submit"),
        VERIFY("verify"),
    }

    companion object {
        private const val MAX_STATE_POLLS = 5
        private const val POLL_DELAY_MILLIS = 250L
        private val MUTABLE_PROJECT_STATUSES = setOf(ProjectStatus.DRAFT, ProjectStatus.UNLISTED)
        private val READ_ONLY_PROJECT_STATUSES = setOf(ProjectStatus.PROCESSING, ProjectStatus.APPROVED)
    }
}
