package dev.s7a.strata.gradle.release

import dev.s7a.strata.gradle.release.ModrinthApiClient.AiUse
import dev.s7a.strata.gradle.release.ModrinthApiClient.Loader
import dev.s7a.strata.gradle.release.ModrinthApiClient.ReleaseType
import dev.s7a.strata.gradle.release.ModrinthApiClient.SideSupport
import dev.s7a.strata.gradle.release.ModrinthApiClient.VersionEnvironment
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.io.File

/**
 * Complete immutable description of a Modrinth release and its canonical local files.
 *
 * The model never contains credentials and may be persisted as release evidence.
 */
internal data class ModrinthManifest(
    val schemaVersion: Int,
    val projectId: String,
    val project: ProjectMetadata,
    val releaseVersion: String,
    val changelog: String,
    val artifacts: List<Artifact>,
) {
    /**
     * One immutable Minecraft distribution and the metadata that must match remotely.
     */
    internal data class Artifact(
        val gameVersion: String,
        val versionNumber: String,
        val versionName: String,
        val fileName: String,
        val relativePath: String,
        val size: Long,
        val sha256: String,
        val sha512: String,
        val mavenCoordinate: String,
        val githubAssetName: String,
    )

    /**
     * Canonical immutable metadata that must match the Modrinth project before any version mutation.
     */
    internal data class ProjectMetadata(
        val slug: String,
        val title: String,
        val description: String,
        val body: String,
        val categories: Set<String>,
        val additionalCategories: Set<String>,
        val licenseId: String,
        val clientSide: SideSupport,
        val serverSide: SideSupport,
        val sourceUrl: String,
        val issuesUrl: String,
        val documentationUrl: String,
        val aiDisclosureNote: String,
        val aiDisclosureUses: Set<AiUse>,
        val icon: ProjectAsset,
        val gallery: List<GalleryAsset>,
    )

    /**
     * Tracked local project asset and its required remote content hash.
     */
    internal data class ProjectAsset(
        val path: String,
        val sha256: String,
    )

    /**
     * Tracked local gallery asset and the exact metadata used to identify it remotely.
     */
    internal data class GalleryAsset(
        val id: String,
        val path: String,
        val sha256: String,
        val featured: Boolean,
        val title: String,
        val description: String,
        val ordering: Int,
    )

    /**
     * Converts this manifest into a stable JSON-compatible map without credentials or machine-specific paths.
     */
    fun toMap(): Map<String, Any> =
        linkedMapOf(
            "schemaVersion" to schemaVersion,
            "projectId" to projectId,
            "project" to
                linkedMapOf(
                    "slug" to project.slug,
                    "title" to project.title,
                    "description" to project.description,
                    "body" to project.body,
                    "categories" to project.categories.sorted(),
                    "additionalCategories" to project.additionalCategories.sorted(),
                    "licenseId" to project.licenseId,
                    "clientSide" to project.clientSide.wireValue,
                    "serverSide" to project.serverSide.wireValue,
                    "sourceUrl" to project.sourceUrl,
                    "issuesUrl" to project.issuesUrl,
                    "documentationUrl" to project.documentationUrl,
                    "aiDisclosureNote" to project.aiDisclosureNote,
                    "aiDisclosureUses" to project.aiDisclosureUses.map(AiUse::wireValue).sorted(),
                    "icon" to
                        linkedMapOf(
                            "path" to project.icon.path,
                            "sha256" to project.icon.sha256,
                        ),
                    "gallery" to
                        project.gallery.map { asset ->
                            linkedMapOf(
                                "id" to asset.id,
                                "path" to asset.path,
                                "sha256" to asset.sha256,
                                "featured" to asset.featured,
                                "title" to asset.title,
                                "description" to asset.description,
                                "ordering" to asset.ordering,
                            )
                        },
                ),
            "releaseVersion" to releaseVersion,
            "changelog" to changelog,
            "loader" to Loader.FABRIC.wireValue,
            "versionType" to ReleaseType.RELEASE.wireValue,
            "environment" to VersionEnvironment.CLIENT_ONLY.wireValue,
            "featured" to FEATURED,
            "requiredProjectDependencies" to listOf(FABRIC_LANGUAGE_KOTLIN_PROJECT_ID),
            "artifacts" to
                artifacts.map { artifact ->
                    linkedMapOf(
                        "gameVersion" to artifact.gameVersion,
                        "versionNumber" to artifact.versionNumber,
                        "versionName" to artifact.versionName,
                        "fileName" to artifact.fileName,
                        "relativePath" to artifact.relativePath,
                        "size" to artifact.size,
                        "sha256" to artifact.sha256,
                        "sha512" to artifact.sha512,
                        "mavenCoordinate" to artifact.mavenCoordinate,
                        "githubAssetName" to artifact.githubAssetName,
                    )
                },
        )

    /**
     * Writes deterministic pretty-printed JSON to [file], replacing only that generated file.
     */
    fun write(file: File) {
        file.parentFile.mkdirs()
        file.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(toMap())) + "\n")
    }

    /**
     * Owns the release schema constants and credential-free manifest parsing entry points.
     */
    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 1
        const val EXPECTED_ARTIFACT_COUNT: Int = 20
        const val FABRIC_LANGUAGE_KOTLIN_PROJECT_ID: String = "Ha28R6CL"
        const val FEATURED: Boolean = true
        const val AI_DISCLOSURE_NOTE: String =
            "Generative AI substantially assisted implementation, review, tests, documentation, and release-page text. The maintainer directed the design and validates release artifacts through the public test suite."
        const val PROJECT_DESCRIPTION: String =
            "Declarative, reusable Minecraft UI with headless rendering and exact versioned Fabric adapters."
        const val SOURCE_URL: String = "https://github.com/sya-ri/strata"
        const val ISSUES_URL: String = "https://github.com/sya-ri/strata/issues"
        const val DOCUMENTATION_URL: String = "https://gh.s7a.dev/strata/"
        private val CANONICAL_PROJECT_IDENTITY =
            CanonicalProjectIdentity(
                slug = "strata-ui",
                title = "Strata",
                licenseId = "MIT",
                iconPath = "icon.png",
            )

        /**
         * Reads and validates the generated manifest from [file].
         */
        fun read(file: File): ModrinthManifest {
            val root = JsonSlurper().parse(file) as? Map<*, *> ?: error("The Modrinth manifest root must be an object.")
            val schemaVersion =
                (root["schemaVersion"] as? Number)?.toInt()
                    ?: error("The Modrinth manifest must declare schemaVersion.")
            check(schemaVersion == CURRENT_SCHEMA_VERSION) {
                "Unsupported Modrinth manifest schema $schemaVersion."
            }
            check(Loader.decode(root.requiredString("loader")) == Loader.FABRIC) {
                "The Modrinth manifest loader must be ${Loader.FABRIC.wireValue}."
            }
            check(ReleaseType.decode(root.requiredString("versionType")) == ReleaseType.RELEASE) {
                "The Modrinth manifest version type must be ${ReleaseType.RELEASE.wireValue}."
            }
            check(VersionEnvironment.decode(root.requiredString("environment")) == VersionEnvironment.CLIENT_ONLY) {
                "The Modrinth manifest environment must be ${VersionEnvironment.CLIENT_ONLY.wireValue}."
            }
            check(root["featured"] == FEATURED) { "Every supported release must be featured." }
            check(root["requiredProjectDependencies"] == listOf(FABRIC_LANGUAGE_KOTLIN_PROJECT_ID)) {
                "The Modrinth manifest must require Fabric Language Kotlin."
            }
            val artifacts =
                (root["artifacts"] as? List<*>)?.map { item ->
                    val value = item as? Map<*, *> ?: error("Each Modrinth artifact must be an object.")
                    Artifact(
                        gameVersion = value.requiredString("gameVersion"),
                        versionNumber = value.requiredString("versionNumber"),
                        versionName = value.requiredString("versionName"),
                        fileName = value.requiredString("fileName"),
                        relativePath = value.requiredString("relativePath"),
                        size = (value["size"] as? Number)?.toLong() ?: error("Artifact size must be numeric."),
                        sha256 = value.requiredString("sha256"),
                        sha512 = value.requiredString("sha512"),
                        mavenCoordinate = value.requiredString("mavenCoordinate"),
                        githubAssetName = value.requiredString("githubAssetName"),
                    )
                } ?: error("The Modrinth manifest must contain artifacts.")
            val projectValue = root["project"] as? Map<*, *> ?: error("The Modrinth manifest must contain project metadata.")
            return ModrinthManifest(
                schemaVersion = schemaVersion,
                projectId = root["projectId"] as? String ?: "",
                project = projectValue.toProjectMetadata(),
                releaseVersion = root.requiredString("releaseVersion"),
                changelog = root.requiredString("changelog"),
                artifacts = artifacts,
            ).also(ModrinthManifest::validate)
        }

        private fun Map<*, *>.requiredString(name: String): String =
            (this[name] as? String)?.takeIf(String::isNotBlank)
                ?: error("The Modrinth manifest field $name must be a non-blank string.")

        private fun Map<*, *>.stringSet(name: String): Set<String> =
            (this[name] as? List<*>)?.map { item -> item as? String ?: error("Project metadata $name must contain strings.") }?.toSet()
                ?: error("Project metadata $name must be an array.")

        private fun Map<*, *>.toProjectMetadata(bodyOverride: String? = null): ProjectMetadata =
            ProjectMetadata(
                slug = requiredString("slug"),
                title = requiredString("title"),
                description = requiredString("description"),
                body = bodyOverride ?: requiredString("body"),
                categories = stringSet("categories"),
                additionalCategories = stringSet("additionalCategories"),
                licenseId = requiredString("licenseId"),
                clientSide = SideSupport.decode(requiredString("clientSide")),
                serverSide = SideSupport.decode(requiredString("serverSide")),
                sourceUrl = requiredString("sourceUrl"),
                issuesUrl = requiredString("issuesUrl"),
                documentationUrl = requiredString("documentationUrl"),
                aiDisclosureNote = requiredString("aiDisclosureNote"),
                aiDisclosureUses = stringSet("aiDisclosureUses").map(AiUse::decode).toSet(),
                icon = requiredObject("icon").toProjectAsset(),
                gallery =
                    requiredList("gallery").map { item ->
                        item.asRequiredObject("Project gallery asset").toGalleryAsset()
                    },
            )

        private fun Map<*, *>.requiredObject(name: String): Map<*, *> = this[name] as? Map<*, *> ?: error("Project metadata $name must be an object.")

        private fun Map<*, *>.requiredList(name: String): List<*> = this[name] as? List<*> ?: error("Project metadata $name must be an array.")

        private fun Any?.asRequiredObject(description: String): Map<*, *> = this as? Map<*, *> ?: error("$description must be an object.")

        private fun Map<*, *>.toProjectAsset(): ProjectAsset =
            ProjectAsset(
                path = requiredString("path"),
                sha256 = requiredString("sha256"),
            )

        private fun Map<*, *>.toGalleryAsset(): GalleryAsset =
            GalleryAsset(
                id = requiredString("id"),
                path = requiredString("path"),
                sha256 = requiredString("sha256"),
                featured = this["featured"] as? Boolean ?: error("Gallery featured must be a boolean."),
                title = requiredString("title"),
                description = requiredString("description"),
                ordering = (this["ordering"] as? Number)?.toInt() ?: error("Gallery ordering must be numeric."),
            )

        /**
         * Reads tracked project metadata from [file] and rejects unknown structural omissions.
         */
        fun readProjectMetadata(
            file: File,
            body: String,
        ): ProjectMetadata {
            val value = JsonSlurper().parse(file) as? Map<*, *> ?: error("Tracked Modrinth project metadata must be an object.")
            return value.toProjectMetadata(body)
        }

        /**
         * Reads the immutable tracked project ID, permitting a blank value only before the project exists.
         */
        fun readTrackedProjectId(file: File): String {
            val value = JsonSlurper().parse(file) as? Map<*, *> ?: error("Tracked Modrinth project metadata must be an object.")
            return value["projectId"] as? String ?: error("Tracked Modrinth project metadata must declare projectId.")
        }
    }

    // Keeping cross-field validation atomic prevents a release caller from accidentally invoking only part of the contract.

    /**
     * Validates uniqueness, canonical version numbers, and hash syntax before any network operation.
     */
    @Suppress("LongMethod")
    fun validate() {
        check(artifacts.size == EXPECTED_ARTIFACT_COUNT) {
            "A release must contain exactly $EXPECTED_ARTIFACT_COUNT Minecraft artifacts, found ${artifacts.size}."
        }
        check(artifacts.map(Artifact::gameVersion).distinct().size == artifacts.size) {
            "Minecraft game versions must be unique."
        }
        check(artifacts.map(Artifact::versionNumber).distinct().size == artifacts.size) {
            "Modrinth version numbers must be unique."
        }
        check(artifacts.map(Artifact::fileName).distinct().size == artifacts.size) {
            "Canonical artifact filenames must be unique."
        }
        check(artifacts.map(Artifact::sha512).distinct().size == artifacts.size) {
            "Every Minecraft distribution must have a distinct SHA-512 hash."
        }
        artifacts.forEach { artifact ->
            check(artifact.versionNumber == "$releaseVersion+mc${artifact.gameVersion}") {
                "Unexpected Modrinth version number ${artifact.versionNumber}."
            }
            check(artifact.sha512.matches(Regex("[0-9a-f]{128}"))) {
                "Artifact ${artifact.fileName} has an invalid SHA-512 hash."
            }
            check(artifact.sha256.matches(Regex("[0-9a-f]{64}"))) {
                "Artifact ${artifact.fileName} has an invalid SHA-256 hash."
            }
            check(0L < artifact.size) { "Artifact ${artifact.fileName} must not be empty." }
            check(artifact.relativePath == "artifacts/${artifact.fileName}") {
                "Artifact ${artifact.fileName} must use its canonical bundle path."
            }
            check(artifact.githubAssetName == artifact.fileName) {
                "GitHub asset names must equal canonical distribution filenames."
            }
            check(
                artifact.mavenCoordinate ==
                    "dev.s7a.strata:strata-runtime-minecraft-fabric-${artifact.gameVersion}:$releaseVersion",
            ) {
                "Artifact ${artifact.fileName} has an unexpected Maven coordinate."
            }
        }
        check(project.slug == CANONICAL_PROJECT_IDENTITY.slug) { "The canonical Modrinth slug must be strata-ui." }
        check(project.title == CANONICAL_PROJECT_IDENTITY.title) { "The canonical Modrinth title must be Strata." }
        check(project.description == PROJECT_DESCRIPTION) {
            "The canonical Modrinth description differs from the reviewed release description."
        }
        check(project.body.isNotBlank()) { "The canonical Modrinth project body must not be blank." }
        check(project.categories == setOf("library")) { "The canonical Modrinth category must be library." }
        check(project.licenseId == CANONICAL_PROJECT_IDENTITY.licenseId) { "The canonical Modrinth license must be MIT." }
        check(project.clientSide == SideSupport.REQUIRED && project.serverSide == SideSupport.UNSUPPORTED) {
            "The canonical Modrinth side metadata must describe a client-only mod."
        }
        check(project.additionalCategories == setOf("utility")) {
            "The canonical Modrinth additional category must be utility."
        }
        check(project.sourceUrl == SOURCE_URL) { "The canonical Modrinth source URL differs." }
        check(project.issuesUrl == ISSUES_URL) { "The canonical Modrinth issues URL differs." }
        check(project.documentationUrl == DOCUMENTATION_URL) { "The canonical Modrinth documentation URL differs." }
        check(project.aiDisclosureUses == setOf(AiUse.CODE, AiUse.TEXT)) {
            "The AI disclosure must identify exactly code and text use."
        }
        check(project.aiDisclosureNote == AI_DISCLOSURE_NOTE) {
            "The AI disclosure statement must match the reviewed release statement exactly."
        }
        check(project.icon.path == CANONICAL_PROJECT_IDENTITY.iconPath) {
            "The canonical Modrinth project icon must be icon.png."
        }
        check(project.gallery.map(GalleryAsset::id) == listOf("overview", "inventory", "progress")) {
            "The gallery must contain only overview, inventory, and progress in canonical order."
        }
        val projectAssets = listOf(project.icon) + project.gallery.map { asset -> ProjectAsset(asset.path, asset.sha256) }
        projectAssets.forEach { asset ->
            check(
                asset.path.matches(Regex("[0-9A-Za-z._/-]+")) &&
                    asset.path.startsWith('/').not() &&
                    asset.path.contains("..").not(),
            ) {
                "Project asset path is unsafe: ${asset.path}"
            }
            check(asset.sha256.matches(Regex("[0-9a-f]{64}"))) {
                "Project asset ${asset.path} has an invalid SHA-256 hash."
            }
        }
        check(
            project.gallery
                .map(GalleryAsset::ordering)
                .distinct()
                .size == project.gallery.size,
        ) {
            "Gallery ordering values must be unique."
        }
    }

    private data class CanonicalProjectIdentity(
        val slug: String,
        val title: String,
        val licenseId: String,
        val iconPath: String,
    )
}
