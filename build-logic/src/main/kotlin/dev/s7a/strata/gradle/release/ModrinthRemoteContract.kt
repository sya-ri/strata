package dev.s7a.strata.gradle.release

import dev.s7a.strata.gradle.release.ModrinthApiClient.DependencyType
import dev.s7a.strata.gradle.release.ModrinthApiClient.DisclosureType
import dev.s7a.strata.gradle.release.ModrinthApiClient.DownloadedFile
import dev.s7a.strata.gradle.release.ModrinthApiClient.Loader
import dev.s7a.strata.gradle.release.ModrinthApiClient.ProjectStatus
import dev.s7a.strata.gradle.release.ModrinthApiClient.ProjectType
import dev.s7a.strata.gradle.release.ModrinthApiClient.ReleaseType
import dev.s7a.strata.gradle.release.ModrinthApiClient.RemoteDisclosure
import dev.s7a.strata.gradle.release.ModrinthApiClient.RemoteProject
import dev.s7a.strata.gradle.release.ModrinthApiClient.RemoteVersion
import dev.s7a.strata.gradle.release.ModrinthApiClient.SideSupport
import dev.s7a.strata.gradle.release.ModrinthApiClient.VersionEnvironment
import dev.s7a.strata.gradle.release.ModrinthManifest.Artifact
import dev.s7a.strata.gradle.release.ModrinthManifest.ProjectAsset
import java.security.MessageDigest

/**
 * Verifies authoritative Modrinth project and version data against one immutable release manifest.
 *
 * The verifier is owned by one thread-confined release coordinator and retains only the manifest and API client supplied at construction.
 * Every mismatch fails before the caller proceeds to a release mutation; remote asset checks may fail with the API client's network exceptions.
 */
internal class ModrinthRemoteContract(
    private val manifest: ModrinthManifest,
    private val api: ModrinthApiClient,
) {
    /**
     * Verifies that [project] is a mod or an explicitly eligible empty draft for [bootstrapPolicy].
     */
    internal fun assertReleaseProjectType(
        project: RemoteProject,
        remoteVersions: List<RemoteVersion>,
        requirePresent: Boolean,
        bootstrapPolicy: BootstrapPolicy,
    ) {
        if (project.projectType == ProjectType.MOD) return
        if (project.projectType == ProjectType.UNCLASSIFIED) {
            check(
                project.status == ProjectStatus.DRAFT &&
                    bootstrapPolicy == BootstrapPolicy.ELIGIBLE_DRAFT &&
                    requirePresent.not() &&
                    remoteVersions.isEmpty(),
            ) {
                "An unclassified Modrinth project is allowed only for an empty draft before its first listed Fabric version."
            }
            return
        }
        error("The Modrinth release target must be a mod project.")
    }

    /**
     * Verifies immutable project fields and returns whether [bodyPolicy] authorizes the tracked predecessor body.
     */
    internal fun assertBasicProjectMetadata(
        remote: RemoteProject,
        bodyPolicy: BodyPolicy,
    ): Boolean {
        val expected = manifest.project
        val differences = mutableListOf<String>()

        fun compare(
            name: String,
            actual: Any?,
            wanted: Any?,
        ) {
            if (actual != wanted) differences += "$name expected=$wanted actual=$actual"
        }
        compare("slug", remote.slug, expected.slug)
        compare("title", remote.title, expected.title)
        compare("description", remote.description, expected.description)
        compare("license.id", remote.licenseId, expected.licenseId)
        compare("source_url", remote.sourceUrl, expected.sourceUrl)
        compare("issues_url", remote.issuesUrl, expected.issuesUrl)
        compare("wiki_url", remote.documentationUrl, expected.documentationUrl)
        val bodyTransitionRequired =
            if (normalize(remote.body) == normalize(expected.body)) {
                false
            } else {
                val predecessorMatches =
                    bodyPolicy == BodyPolicy.ALLOW_TRACKED_PREDECESSOR &&
                        expected.previousBodySha256 != null &&
                        normalizedSha256(remote.body) == expected.previousBodySha256
                if (predecessorMatches.not()) differences += "body differs from the current body and its tracked predecessor"
                predecessorMatches
            }
        check(differences.isEmpty()) {
            "Modrinth project metadata differs from the tracked release contract: ${differences.joinToString("; ")}"
        }
        return bodyTransitionRequired
    }

    /**
     * Verifies project classification fields and returns the exact missing fields that an authorized draft bootstrap may add.
     */
    internal fun inspectProjectClassification(
        remote: RemoteProject,
        allowMissing: Boolean,
    ): ProjectPatchPlan {
        val expected = manifest.project
        val categoriesMissing = missingOrExact("categories", remote.categories, expected.categories, remote.categories.isEmpty(), allowMissing)
        val additionalCategoriesMissing =
            missingOrExact(
                "additional_categories",
                remote.additionalCategories,
                expected.additionalCategories,
                remote.additionalCategories.isEmpty(),
                allowMissing,
            )
        val environmentsMissing =
            missingOrExact(
                "environment",
                remote.environments,
                setOf(VersionEnvironment.CLIENT_ONLY),
                remote.environments.isEmpty() || remote.environments == setOf(VersionEnvironment.UNKNOWN),
                allowMissing,
            )
        val clientMissing =
            missingOrExact("client_side", remote.clientSide, expected.clientSide, remote.clientSide == SideSupport.UNKNOWN, allowMissing)
        val serverMissing =
            missingOrExact("server_side", remote.serverSide, expected.serverSide, remote.serverSide == SideSupport.UNKNOWN, allowMissing)
        return ProjectPatchPlan(
            categoriesMissing = categoriesMissing,
            additionalCategoriesMissing = additionalCategoriesMissing,
            environmentMissing = environmentsMissing || clientMissing || serverMissing,
        )
    }

    /**
     * Verifies the AI disclosure and returns whether an authorized draft bootstrap must create it.
     */
    internal fun inspectProjectDisclosures(
        disclosures: List<RemoteDisclosure>,
        allowMissing: Boolean,
    ): Boolean {
        val activeDisclosures = disclosures.filter { disclosure -> disclosure.deleted.not() }
        if (activeDisclosures.size == 1) {
            val disclosure = activeDisclosures.single()
            if (
                disclosure.type == DisclosureType.AI_CONTENT &&
                disclosure.note == manifest.project.aiDisclosureNote &&
                disclosure.aiUses == manifest.project.aiDisclosureUses
            ) {
                return false
            }
        }
        check(activeDisclosures.isEmpty() && allowMissing) {
            "Modrinth project disclosures differ from the tracked release contract; refusing overwrite or removal."
        }
        return true
    }

    /**
     * Downloads and verifies the icon and gallery assets referenced by [remote].
     */
    internal fun assertProjectAssets(remote: RemoteProject) {
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
                ProjectAsset(expected.path, expected.sha256),
            )
        }
    }

    /**
     * Verifies all immutable metadata and the primary file declaration for [remote] against [expected].
     */
    internal fun assertVersion(
        remote: RemoteVersion,
        expected: Artifact,
    ) {
        val differences = mutableListOf<String>()

        fun compare(
            name: String,
            actual: Any?,
            wanted: Any?,
        ) {
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

    /**
     * Verifies the downloaded [actual] byte count and hashes against [expected].
     */
    internal fun assertArtifactHash(
        description: String,
        actual: DownloadedFile,
        expected: Artifact,
    ) {
        check(actual.size == expected.size) { "$description size differs." }
        check(actual.sha256 == expected.sha256) { "$description SHA-256 differs." }
        check(actual.sha512 == expected.sha512) { "$description SHA-512 differs." }
    }

    private fun missingOrExact(
        name: String,
        actual: Any?,
        expected: Any?,
        missing: Boolean,
        allowMissing: Boolean,
    ): Boolean {
        if (actual == expected) return false
        check(missing && allowMissing) {
            val reason = if (missing) "is not configured" else "differs"
            "Modrinth project field $name $reason; refusing overwrite."
        }
        return true
    }

    private fun assertProjectAsset(
        description: String,
        url: String,
        expected: ProjectAsset,
    ) {
        val downloaded = api.hashRemoteFile(url)
        check(downloaded.sha256 == expected.sha256) { "Remote $description differs from tracked ${expected.path}." }
    }

    /**
     * Controls whether an unclassified empty draft may be treated as the first release bootstrap target.
     */
    internal enum class BootstrapPolicy {
        /**
         * Permits the initial empty draft to begin its first release bootstrap.
         */
        ELIGIBLE_DRAFT,

        /**
         * Permits a previously inspected draft to complete its exact bootstrap fields.
         */
        AUTHORIZED,

        /**
         * Requires the project classification and disclosures to already be complete.
         */
        STRICT,
    }

    /**
     * Controls whether exact project-body validation may accept the single tracked predecessor hash.
     */
    internal enum class BodyPolicy {
        /**
         * Permits either the current body or the one predecessor hash tracked by the manifest.
         */
        ALLOW_TRACKED_PREDECESSOR,

        /**
         * Requires the current manifest body exactly.
         */
        STRICT,
    }

    /**
     * Records project classification fields that an authorized draft bootstrap must add.
     */
    internal data class ProjectPatchPlan(
        val categoriesMissing: Boolean,
        val additionalCategoriesMissing: Boolean,
        val environmentMissing: Boolean,
    ) {
        /**
         * Whether no classification field requires a bootstrap write.
         */
        val isEmpty: Boolean
            get() = categoriesMissing.not() && additionalCategoriesMissing.not() && environmentMissing.not()
    }
}

private fun normalize(value: String?): String = value.orEmpty().replace("\r\n", "\n").trimEnd() + "\n"

private fun normalizedSha256(value: String?): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(normalize(value).toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
