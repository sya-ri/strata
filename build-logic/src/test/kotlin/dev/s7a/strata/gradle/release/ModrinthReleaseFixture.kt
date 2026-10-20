package dev.s7a.strata.gradle.release

import dev.s7a.strata.gradle.release.ModrinthApiClient.AiUse
import dev.s7a.strata.gradle.release.ModrinthApiClient.SideSupport
import dev.s7a.strata.gradle.release.ModrinthReleaseFixture.Companion.GAME_VERSIONS
import dev.s7a.strata.gradle.release.ModrinthReleaseFixture.Companion.PREVIOUS_PROJECT_BODY
import dev.s7a.strata.gradle.release.ModrinthReleaseFixture.Companion.PROJECT_ID
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat

/**
 * Retains the manifest, authored artifacts and local client used by the release tests.
 */
internal data class ModrinthReleaseFixture(
    val bundle: File,
    val manifest: ModrinthManifest,
    val iconBytes: ByteArray,
) {
    /**
     * Uses only the local fixture API and explicitly permitted local download host.
     */
    fun client(
        server: MockModrinthServer,
        token: String = "test-token",
        requestTimeoutMillis: Long = 60_000L,
        retryBaseMillis: Long = 1L,
    ): ModrinthApiClient =
        ModrinthApiClient(
            apiBaseUrl = "${server.baseUrl}/v2",
            token = token,
            userAgent = "strata-test",
            allowedDownloadHosts = setOf("localhost"),
            allowInsecureDownloads = true,
            requestTimeout = Duration.ofMillis(requestTimeoutMillis),
            retryBaseMillis = retryBaseMillis,
        )

    /**
     * Reconciles the fixture manifest using the selected local client.
     */
    fun coordinator(
        server: MockModrinthServer,
        client: ModrinthApiClient = client(server),
    ): ModrinthReleaseCoordinator = ModrinthReleaseCoordinator(manifest, bundle, client)

    /**
     * Provides deterministic compatibility targets and independently hashed release bytes.
     */
    companion object {
        const val PROJECT_ID = "project-id"
        const val PREVIOUS_PROJECT_BODY = "# Strata\n\nPrevious release.\n"
        val GAME_VERSIONS =
            listOf(
                "1.20",
                "1.21",
                "26.2",
            )

        /**
         * Creates a complete release bundle without contacting a remote service.
         */
        fun create(
            directory: Path,
            gameVersions: List<String>,
        ): ModrinthReleaseFixture {
            val bundle = directory.resolve("bundle").toFile()
            val artifactsDirectory = bundle.resolve("artifacts").also(File::mkdirs)
            val artifacts =
                gameVersions.mapIndexed { index, gameVersion ->
                    val fileName = "strata-runtime-minecraft-fabric-$gameVersion-0.1.1.jar"
                    val bytes = "artifact-$index-$gameVersion".toByteArray(StandardCharsets.UTF_8)
                    artifactsDirectory.resolve(fileName).writeBytes(bytes)
                    ModrinthManifest.Artifact(
                        gameVersion = gameVersion,
                        versionNumber = "0.1.1+mc$gameVersion",
                        versionName = "Strata 0.1.1 for Minecraft $gameVersion",
                        fileName = fileName,
                        relativePath = "artifacts/$fileName",
                        size = bytes.size.toLong(),
                        sha256 = bytes.hash("SHA-256"),
                        sha512 = bytes.hash("SHA-512"),
                        mavenCoordinate = "dev.s7a.strata:strata-runtime-minecraft-fabric-$gameVersion:0.1.1",
                        githubAssetName = fileName,
                    )
                }
            val gallery = galleryAssets()
            val iconBytes = "icon".toByteArray(StandardCharsets.UTF_8)
            val manifest =
                ModrinthManifest(
                    schemaVersion = ModrinthManifest.CURRENT_SCHEMA_VERSION,
                    projectId = PROJECT_ID,
                    project =
                        ModrinthManifest.ProjectMetadata(
                            slug = "strata-ui",
                            title = "Strata",
                            description = ModrinthManifest.PROJECT_DESCRIPTION,
                            body = "# Strata\n\nCompiled API-only example.\n",
                            previousBodySha256 = PREVIOUS_PROJECT_BODY.toByteArray(StandardCharsets.UTF_8).hash("SHA-256"),
                            categories = setOf("library"),
                            additionalCategories = setOf("utility"),
                            licenseId = "MIT",
                            clientSide = SideSupport.REQUIRED,
                            serverSide = SideSupport.OPTIONAL,
                            sourceUrl = "https://github.com/sya-ri/strata",
                            issuesUrl = "https://github.com/sya-ri/strata/issues",
                            documentationUrl = "https://gh.s7a.dev/strata/",
                            aiDisclosureNote = ModrinthManifest.AI_DISCLOSURE_NOTE,
                            aiDisclosureUses = setOf(AiUse.CODE, AiUse.TEXT),
                            icon = ModrinthManifest.ProjectAsset("icon.png", iconBytes.hash("SHA-256")),
                            gallery = gallery,
                        ),
                    releaseVersion = "0.1.1",
                    changelog = "Release notes\n",
                    artifacts = artifacts,
                )
            return ModrinthReleaseFixture(bundle, manifest, iconBytes)
        }

        /**
         * Creates independently hashed gallery records with one featured overview.
         */
        private fun galleryAssets(): List<ModrinthManifest.GalleryAsset> =
            listOf("overview", "inventory", "progress").mapIndexed { index, id ->
                val bytes = "gallery-$id".toByteArray(StandardCharsets.UTF_8)
                ModrinthManifest.GalleryAsset(
                    id = id,
                    path = "docs/components/$id.png",
                    sha256 = bytes.hash("SHA-256"),
                    featured = index == 0,
                    title = id.replaceFirstChar(Char::uppercase),
                    description = "$id gallery",
                    ordering = index,
                )
            }

        /**
         * Computes fixture checksums through the JDK digest and hex APIs.
         */
        private fun ByteArray.hash(algorithm: String): String = HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(this))
    }
}
