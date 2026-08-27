package dev.s7a.strata.gradle.release

import dev.s7a.strata.gradle.release.ModrinthApiClient.AmbiguousWriteException
import dev.s7a.strata.gradle.release.ModrinthApiClient.DownloadedFile
import dev.s7a.strata.gradle.release.ModrinthApiClient.ProjectStatus
import dev.s7a.strata.gradle.release.ModrinthApiClient.ProjectType
import dev.s7a.strata.gradle.release.ModrinthApiClient.RemoteProject
import dev.s7a.strata.gradle.release.ModrinthApiClient.RemoteVersion
import dev.s7a.strata.gradle.release.ModrinthApiClient.VersionEnvironment
import dev.s7a.strata.gradle.release.ModrinthApiClient.VersionStatus
import dev.s7a.strata.gradle.release.ModrinthApiClient.WriteRejectedException
import dev.s7a.strata.gradle.release.ModrinthRemoteContract.BodyPolicy
import dev.s7a.strata.gradle.release.ModrinthRemoteContract.BootstrapPolicy
import dev.s7a.strata.gradle.release.ModrinthRemoteContract.ProjectPatchPlan
import java.io.File
import java.security.MessageDigest

// Keeping monotonic phase orchestration together makes every mutation share the same exact-state checks and recovery policy.

/**
 * Reconciles one complete release against authoritative Modrinth state.
 *
 * Reconciliation is exact and monotonic.
 * Existing remote data is never overwritten or deleted, and every ambiguous write is followed by an authoritative read before a bounded retry.
 * The coordinator is thread-confined to its calling Gradle task.
 */
@Suppress("TooManyFunctions")
internal class ModrinthReleaseCoordinator(
    private val manifest: ModrinthManifest,
    private val bundleDirectory: File,
    private val api: ModrinthApiClient,
) {
    private val remoteContract = ModrinthRemoteContract(manifest, api)

    /**
     * Verifies project metadata, assets, tags, and existing release targets without writing.
     */
    fun preflight(): Receipt {
        val snapshot =
            reconcile(
                requirePresent = false,
                requireApproved = false,
                bootstrapPolicy = BootstrapPolicy.ELIGIBLE_DRAFT,
                bodyPolicy = BodyPolicy.ALLOW_TRACKED_PREDECESSOR,
            )
        return snapshot.receipt(Operation.PREFLIGHT)
    }

    /**
     * Creates only missing exact listed versions and completes an authorized first-release draft bootstrap.
     */
    fun stage(): Receipt {
        val initial =
            reconcile(
                requirePresent = false,
                requireApproved = false,
                bootstrapPolicy = BootstrapPolicy.ELIGIBLE_DRAFT,
                bodyPolicy = BodyPolicy.ALLOW_TRACKED_PREDECESSOR,
            )
        check(initial.projectStatus in VERSION_APPEND_PROJECT_STATUSES) {
            "Unsupported Modrinth project lifecycle state."
        }
        stageMissingVersions(initial)
        if (initial.bootstrapPlan.isEmpty.not()) {
            check(initial.projectStatus == ProjectStatus.DRAFT) {
                "Modrinth project bootstrap requires a draft release inventory."
            }
            val staged =
                reconcile(
                    requirePresent = true,
                    requireApproved = false,
                    bootstrapPolicy = BootstrapPolicy.AUTHORIZED,
                    bodyPolicy = BodyPolicy.STRICT,
                )
            bootstrapProject(staged.bootstrapPlan)
        }
        return reconcile(
            requirePresent = true,
            requireApproved = false,
            bootstrapPolicy = BootstrapPolicy.STRICT,
            bodyPolicy = BodyPolicy.ALLOW_TRACKED_PREDECESSOR,
        ).receipt(Operation.STAGE)
    }

    /**
     * Submits a completely listed draft or unlisted project for review and otherwise polls only.
     */
    fun submit(): Receipt {
        val initial =
            reconcile(
                requirePresent = true,
                requireApproved = false,
                bootstrapPolicy = BootstrapPolicy.STRICT,
                bodyPolicy = BodyPolicy.ALLOW_TRACKED_PREDECESSOR,
            )
        when (initial.projectStatus) {
            in MUTABLE_PROJECT_STATUSES -> submitWithRequery()
            in READ_ONLY_PROJECT_STATUSES -> Unit
            else -> error("Unsupported Modrinth project lifecycle state.")
        }
        val result =
            reconcile(
                requirePresent = true,
                requireApproved = false,
                bootstrapPolicy = BootstrapPolicy.STRICT,
                bodyPolicy = BodyPolicy.ALLOW_TRACKED_PREDECESSOR,
            )
        check(result.projectStatus in READ_ONLY_PROJECT_STATUSES) {
            "Modrinth project submission did not reach processing or approved state."
        }
        return result.receipt(Operation.SUBMIT)
    }

    /**
     * Transitions the project body only after approval and after every version in this release is exact.
     *
     * Release orchestration invokes this phase only after the predecessor release has completed its own final verification.
     * This coordinator retains no project response after the call, runs synchronously on the invoking release-task thread, and returns the exact approved remote inventory.
     * It fails before a write when project metadata or release files drift, and recovers an ambiguous accepted body update only by re-reading and matching the complete tracked contract.
     */
    fun finalizeProject(): Receipt {
        val initial =
            reconcile(
                requirePresent = true,
                requireApproved = true,
                bootstrapPolicy = BootstrapPolicy.STRICT,
                bodyPolicy = BodyPolicy.ALLOW_TRACKED_PREDECESSOR,
            )
        if (initial.bodyTransitionRequired) {
            updateProjectBodyWithRequery()
        }
        return reconcile(
            requirePresent = true,
            requireApproved = true,
            bootstrapPolicy = BootstrapPolicy.STRICT,
            bodyPolicy = BodyPolicy.STRICT,
        ).receipt(Operation.FINALIZE_PROJECT)
    }

    /**
     * Verifies approval, exact listed metadata, and bytes returned by every primary CDN URL.
     */
    fun verify(): Receipt {
        val snapshot =
            reconcile(
                requirePresent = true,
                requireApproved = true,
                bootstrapPolicy = BootstrapPolicy.STRICT,
                bodyPolicy = BodyPolicy.STRICT,
            )
        snapshot.listed.forEach { target ->
            remoteContract.assertArtifactHash("CDN artifact ${target.artifact.fileName}", api.hashRemoteFile(target.url), target.artifact)
        }
        return snapshot.receipt(Operation.VERIFY)
    }

    // One authoritative snapshot must validate immutable metadata, inventory, and bootstrap eligibility before any caller may act on it.
    @Suppress("LongMethod")
    private fun reconcile(
        requirePresent: Boolean,
        requireApproved: Boolean,
        bootstrapPolicy: BootstrapPolicy,
        bodyPolicy: BodyPolicy,
    ): Snapshot {
        manifest.validate()
        check(manifest.projectId.isNotBlank()) {
            "A stable Modrinth project ID is required through strata.modrinthProjectId or MODRINTH_PROJECT_ID."
        }
        val project = api.getProject(manifest.projectId)
        check(project.id == manifest.projectId) {
            "Configured Modrinth project ID ${manifest.projectId} does not match immutable remote ID ${project.id}."
        }
        check(project.status in MUTABLE_PROJECT_STATUSES + READ_ONLY_PROJECT_STATUSES) {
            "Modrinth project ${manifest.projectId} cannot participate in this release while status is ${project.status.wireValue}."
        }
        if (requireApproved) {
            check(project.status == ProjectStatus.APPROVED) {
                "Modrinth project ${manifest.projectId} must be approved for final verification."
            }
        }
        val remote = api.getProjectVersions(manifest.projectId)
        remoteContract.assertReleaseProjectType(project, remote, requirePresent, bootstrapPolicy)
        val bodyTransitionRequired = remoteContract.assertBasicProjectMetadata(project, bodyPolicy)
        remoteContract.assertProjectAssets(project)

        val supportedTags = api.getGameVersions()
        val unsupported = manifest.artifacts.map(ModrinthManifest.Artifact::gameVersion).filterNot(supportedTags::contains)
        check(unsupported.isEmpty()) { "Modrinth does not recognize these Minecraft versions: $unsupported" }

        val expectedNumbers = manifest.artifacts.map(ModrinthManifest.Artifact::versionNumber).toSet()
        val containsOnlyExpectedVersions = remote.all { version -> version.versionNumber in expectedNumbers }
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
                remoteContract.assertVersion(version, artifact)
                check(version.status == VersionStatus.LISTED) {
                    "Modrinth version ${artifact.versionNumber} is not listed; refusing mutation."
                }
                val primary = version.files.single { file -> file.primary }
                listed += RemoteTarget(artifact, primary.url)
            }
        }
        val allowBootstrap =
            when (bootstrapPolicy) {
                BootstrapPolicy.ELIGIBLE_DRAFT -> {
                    project.status == ProjectStatus.DRAFT && containsOnlyExpectedVersions
                }

                BootstrapPolicy.AUTHORIZED -> {
                    check(project.status == ProjectStatus.DRAFT && containsOnlyExpectedVersions) {
                        "Authorized Modrinth bootstrap may continue only for the same draft and expected version inventory."
                    }
                    true
                }

                BootstrapPolicy.STRICT -> {
                    false
                }
            }
        val bootstrapPlan =
            BootstrapPlan(
                projectPatch = remoteContract.inspectProjectClassification(project, allowBootstrap),
                disclosureMissing = remoteContract.inspectProjectDisclosures(api.getProjectDisclosures(manifest.projectId), allowBootstrap),
            )
        return Snapshot(
            manifest.projectId,
            project.projectType,
            project.status,
            absent,
            listed,
            bootstrapPlan,
            bodyTransitionRequired,
        )
    }

    private fun stageMissingVersions(initial: Snapshot) {
        if (initial.projectType != ProjectType.UNCLASSIFIED) {
            initial.absent.forEach(::createWithRequery)
            return
        }
        check(initial.listed.isEmpty() && initial.absent.size == manifest.artifacts.size) {
            "An unclassified Modrinth draft must start with an empty release inventory."
        }
        val first = initial.absent.first()
        createWithRequery(first)
        check(pollFirstVersionClassification(first)) {
            "Modrinth did not classify the project as a mod after its first listed Fabric version."
        }
        initial.absent.drop(1).forEach(::createWithRequery)
    }

    private fun pollFirstVersionClassification(artifact: ModrinthManifest.Artifact): Boolean {
        repeat(MAX_STATE_POLLS) { poll ->
            val project = api.getProject(manifest.projectId)
            check(project.id == manifest.projectId) { "Modrinth project identity changed after its first version was created." }
            check(project.status == ProjectStatus.DRAFT) {
                "Modrinth project left draft state after its first version was created."
            }
            check(project.projectType == ProjectType.UNCLASSIFIED || project.projectType == ProjectType.MOD) {
                "Modrinth project became ${project.projectType.wireValue} after its first Fabric version was created."
            }
            val remote = api.getProjectVersions(manifest.projectId)
            check(remote.size <= 1) { "Modrinth version inventory changed while classifying the first Fabric version." }
            remote.singleOrNull()?.let { version -> assertCreatedVersion(version, artifact, "First create") }
            if (project.projectType == ProjectType.MOD && remote.size == 1) return true
            if (poll + 1 < MAX_STATE_POLLS) pause(POLL_DELAY_MILLIS)
        }
        return false
    }

    private fun updateProjectBodyWithRequery() {
        if (projectBodyIsExactDuringTransition()) return
        try {
            api.updateProjectBody(manifest.projectId, manifest.project.body)
        } catch (failure: AmbiguousWriteException) {
            pause(failure.retryAfterMillis)
            if (pollProjectBodyTransition()) return
            retryProjectBodyTransition()
            return
        }
        check(pollProjectBodyTransition()) {
            "Modrinth project body update was accepted but exact state was not observable after bounded polling."
        }
    }

    private fun retryProjectBodyTransition() {
        try {
            api.updateProjectBody(manifest.projectId, manifest.project.body)
        } catch (failure: AmbiguousWriteException) {
            pause(failure.retryAfterMillis)
            if (pollProjectBodyTransition()) return
            throw failure
        } catch (failure: WriteRejectedException) {
            if (pollProjectBodyTransition()) return
            throw failure
        }
        check(pollProjectBodyTransition()) {
            "Modrinth project body update retry was accepted but exact state was not observable after bounded polling."
        }
    }

    private fun pollProjectBodyTransition(): Boolean {
        repeat(MAX_STATE_POLLS) { poll ->
            if (projectBodyIsExactDuringTransition()) return true
            if (poll + 1 < MAX_STATE_POLLS) pause(POLL_DELAY_MILLIS)
        }
        return false
    }

    private fun projectBodyIsExactDuringTransition(): Boolean {
        val project = api.getProject(manifest.projectId)
        check(project.id == manifest.projectId) { "Modrinth project identity changed during its body transition." }
        check(project.projectType == ProjectType.MOD) { "Only an existing Modrinth mod project may transition its release body." }
        check(project.status == ProjectStatus.APPROVED) {
            "Modrinth project must remain approved throughout its body transition, but entered ${project.status.wireValue}."
        }
        return remoteContract.assertBasicProjectMetadata(project, BodyPolicy.ALLOW_TRACKED_PREDECESSOR).not()
    }

    private fun bootstrapProject(plan: BootstrapPlan) {
        if (plan.projectPatch.isEmpty.not()) {
            bootstrapWriteWithRequery(
                description = "project classification",
                write = {
                    api.updateProjectClassification(
                        projectId = manifest.projectId,
                        categories = manifest.project.categories.takeIf { plan.projectPatch.categoriesMissing },
                        additionalCategories = manifest.project.additionalCategories.takeIf { plan.projectPatch.additionalCategoriesMissing },
                        environment = VersionEnvironment.CLIENT_ONLY.takeIf { plan.projectPatch.environmentMissing },
                    )
                },
                readExact = ::projectClassificationIsExactDuringBootstrap,
            )
        }
        if (plan.disclosureMissing) {
            bootstrapWriteWithRequery(
                description = "AI disclosure",
                write = {
                    api.setAiDisclosure(
                        projectId = manifest.projectId,
                        note = manifest.project.aiDisclosureNote,
                        uses = manifest.project.aiDisclosureUses,
                    )
                },
                readExact = ::disclosureIsExactDuringBootstrap,
            )
        }
    }

    private fun projectClassificationIsExactDuringBootstrap(): Boolean {
        val project = readBootstrapProject()
        return remoteContract.inspectProjectClassification(project, allowMissing = true).isEmpty
    }

    private fun disclosureIsExactDuringBootstrap(): Boolean {
        val project = readBootstrapProject()
        check(remoteContract.inspectProjectClassification(project, allowMissing = false).isEmpty) {
            "Modrinth project classification must be exact before setting its disclosure."
        }
        return remoteContract.inspectProjectDisclosures(api.getProjectDisclosures(manifest.projectId), allowMissing = true).not()
    }

    private fun readBootstrapProject(): RemoteProject {
        val project = api.getProject(manifest.projectId)
        check(project.id == manifest.projectId) { "Modrinth project identity changed during bootstrap." }
        check(project.projectType == ProjectType.MOD) {
            "Modrinth project did not become a mod after its listed Fabric versions were created."
        }
        check(project.status == ProjectStatus.DRAFT) { "Modrinth project left draft state during bootstrap." }
        check(remoteContract.assertBasicProjectMetadata(project, BodyPolicy.STRICT).not()) {
            "Modrinth project body changed during bootstrap."
        }
        assertBootstrapVersionInventory()
        return project
    }

    private fun assertBootstrapVersionInventory() {
        val remote = api.getProjectVersions(manifest.projectId)
        val expectedNumbers = manifest.artifacts.map(ModrinthManifest.Artifact::versionNumber).toSet()
        check(remote.size == manifest.artifacts.size && remote.map(RemoteVersion::versionNumber).toSet() == expectedNumbers) {
            "Modrinth version inventory changed during project bootstrap."
        }
        manifest.artifacts.forEach { artifact ->
            val version = remote.single { candidate -> candidate.versionNumber == artifact.versionNumber }
            remoteContract.assertVersion(version, artifact)
            check(version.status == VersionStatus.LISTED) { "Modrinth bootstrap requires every expected version to remain listed." }
        }
    }

    // A second write is permitted only after bounded authoritative reads still show the field as absent.
    @Suppress("ThrowsCount")
    private fun bootstrapWriteWithRequery(
        description: String,
        write: () -> Unit,
        readExact: () -> Boolean,
    ) {
        if (readExact()) return
        try {
            write()
        } catch (failure: AmbiguousWriteException) {
            pause(failure.retryAfterMillis)
            if (pollBootstrapWrite(readExact)) return
            try {
                write()
            } catch (retryFailure: AmbiguousWriteException) {
                pause(retryFailure.retryAfterMillis)
                if (pollBootstrapWrite(readExact)) return
                throw retryFailure
            } catch (retryFailure: WriteRejectedException) {
                if (pollBootstrapWrite(readExact)) return
                throw retryFailure
            }
        }
        check(pollBootstrapWrite(readExact)) {
            "Modrinth $description write was accepted but exact state was not observable after bounded polling."
        }
    }

    private fun pollBootstrapWrite(readExact: () -> Boolean): Boolean {
        repeat(MAX_STATE_POLLS) { poll ->
            if (readExact()) return true
            if (poll + 1 < MAX_STATE_POLLS) pause(POLL_DELAY_MILLIS)
        }
        return false
    }

    private fun createWithRequery(artifact: ModrinthManifest.Artifact) {
        val file = bundleDirectory.resolve(artifact.relativePath)
        remoteContract.assertArtifactHash("local artifact ${artifact.fileName}", file.hashes(), artifact)
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
        remoteContract.assertVersion(state, artifact)
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
        val matching =
            api.getProjectVersions(manifest.projectId).filter { version ->
                version.versionNumber == artifact.versionNumber
            }
        check(matching.size <= 1) {
            "Modrinth contains duplicate version number ${artifact.versionNumber}."
        }
        return matching.singleOrNull()
    }

    /**
     * Credential-free result persisted by a network task for release review.
     */
    internal data class Receipt(
        val operation: String,
        val projectId: String,
        val projectStatus: String,
        val absent: List<String>,
        val listed: List<String>,
    )

    private data class Snapshot(
        val projectId: String,
        val projectType: ProjectType,
        val projectStatus: ProjectStatus,
        val absent: List<ModrinthManifest.Artifact>,
        val listed: List<RemoteTarget>,
        val bootstrapPlan: BootstrapPlan,
        val bodyTransitionRequired: Boolean,
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

    private data class BootstrapPlan(
        val projectPatch: ProjectPatchPlan,
        val disclosureMissing: Boolean,
    ) {
        val isEmpty: Boolean
            get() = projectPatch.isEmpty && disclosureMissing.not()
    }

    private enum class Operation(
        val wireValue: String,
    ) {
        PREFLIGHT("preflight"),
        STAGE("stage"),
        SUBMIT("submit"),
        FINALIZE_PROJECT("finalize-project"),
        VERIFY("verify"),
    }

    /**
     * Owns the bounded polling policy and accepted monotonic project states.
     */
    companion object {
        private const val MAX_STATE_POLLS = 5
        private const val POLL_DELAY_MILLIS = 250L
        private val MUTABLE_PROJECT_STATUSES = setOf(ProjectStatus.DRAFT, ProjectStatus.UNLISTED)
        private val READ_ONLY_PROJECT_STATUSES = setOf(ProjectStatus.PROCESSING, ProjectStatus.APPROVED)
        private val VERSION_APPEND_PROJECT_STATUSES = MUTABLE_PROJECT_STATUSES + READ_ONLY_PROJECT_STATUSES
    }
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

private fun pause(delayMillis: Long) {
    if (0L < delayMillis) Thread.sleep(delayMillis.coerceAtMost(10_000L))
}
