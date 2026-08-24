package dev.s7a.strata.gradle.release

import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import javax.inject.Inject

/**
 * Declares the immutable inputs used to construct and publish one Strata release.
 *
 * The extension retains only Gradle providers and target metadata during configuration.
 * Credentials are deliberately absent and are acquired by network tasks from their execution-time environment.
 */
public abstract class StrataReleaseExtension
    @Inject
    constructor(
        objects: ObjectFactory,
    ) {
        /**
         * Stable Modrinth project ID; an empty value permits local bundle generation but prevents every network task.
         */
        public val modrinthProjectId: Property<String> = objects.property(String::class.java).convention("")

        /**
         * Modrinth API base URL, overridable only for staging and deterministic mock-server tests.
         */
        public val modrinthApiBaseUrl: Property<String> =
            objects.property(String::class.java).convention("https://api.modrinth.com/v2")

        /**
         * Release version shared by Maven, Modrinth, and GitHub artifacts.
         */
        public val releaseVersion: Property<String> = objects.property(String::class.java)

        /**
         * Markdown release notes sent verbatim, after line-ending normalization, to release services.
         */
        public val releaseNotesFile: RegularFileProperty = objects.fileProperty()

        /**
         * Tracked canonical Modrinth project metadata verified before every remote operation.
         */
        public val modrinthProjectMetadataFile: RegularFileProperty = objects.fileProperty()

        /**
         * Generated long-form Modrinth body backed by the same compiled API-only example as reader documentation.
         */
        public val modrinthProjectBodyFile: RegularFileProperty = objects.fileProperty()

        /**
         * Build-only directory containing canonical artifacts, manifests, and remote receipts.
         */
        public val outputDirectory: DirectoryProperty = objects.directoryProperty()

        /**
         * Tracked exact Maven coordinates reconciled with Central.
         */
        public val mavenCoordinatesFile: RegularFileProperty = objects.fileProperty()

        /**
         * Local Maven repository containing the publications staged from the current revision.
         */
        public val mavenLocalRepository: DirectoryProperty = objects.directoryProperty()

        /**
         * Local icon and gallery files whose tracked hashes are verified while generating the release manifest.
         */
        public val projectAssetFiles: ConfigurableFileCollection = objects.fileCollection()

        internal val targets: MutableList<Target> = mutableListOf()

        /**
         * Adds one exact Minecraft distribution artifact.
         *
         * @param gameVersion canonical Modrinth and Minecraft version tag.
         * @param canonicalFileName stable external filename used by Modrinth and GitHub Releases.
         * @param artifact provider for the verified distribution JAR.
         * @param verificationTaskPath fully qualified Gradle task that verifies the JAR before it enters the bundle.
         * @throws IllegalArgumentException when a value is blank or the game version is already registered.
         */
        public fun target(
            gameVersion: String,
            canonicalFileName: String,
            artifact: Provider<RegularFile>,
            verificationTaskPath: String,
        ) {
            require(gameVersion.isNotBlank()) { "The Minecraft game version must not be blank." }
            require(canonicalFileName.isNotBlank()) { "The canonical artifact filename must not be blank." }
            require(verificationTaskPath.startsWith(':')) { "The verification task path must be fully qualified." }
            require(targets.none { target -> target.gameVersion == gameVersion }) {
                "Minecraft $gameVersion is already registered for release."
            }
            targets += Target(gameVersion, canonicalFileName, artifact, verificationTaskPath)
        }

        /**
         * Configuration-time provider tuple transferred to the manifest task after project evaluation.
         */
        internal data class Target(
            val gameVersion: String,
            val canonicalFileName: String,
            val artifact: Provider<RegularFile>,
            val verificationTaskPath: String,
        )
    }
