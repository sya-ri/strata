package dev.s7a.strata.gradle.release

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Registers isolated release-bundle and Modrinth reconciliation tasks on the root project.
 *
 * Applying the plugin performs no network access.
 * Minecraft projects are configured only when target providers or explicit verification dependencies are selected by a release task.
 */
public class StrataReleasePlugin : Plugin<Project> {
    // Task registrations remain together so the protected workflow graph can be audited as one unit.
    @Suppress("LongMethod")
    override fun apply(project: Project) {
        val extension = project.extensions.create("strataRelease", StrataReleaseExtension::class.java)
        extension.releaseVersion.convention(project.provider { project.version.toString() })
        extension.outputDirectory.convention(project.layout.buildDirectory.dir("release/modrinth"))

        val manifest =
            project.tasks.register("modrinthReleaseManifest", GenerateModrinthManifest::class.java) {
                group = "release"
                description = "Builds the canonical Modrinth and GitHub release bundle for every configured target."
                projectId.set(extension.modrinthProjectId)
                releaseVersion.set(extension.releaseVersion)
                releaseNotesFile.set(extension.releaseNotesFile)
                projectMetadataFile.set(extension.modrinthProjectMetadataFile)
                projectBodyFile.set(extension.modrinthProjectBodyFile)
                projectAssetFiles.from(extension.projectAssetFiles)
                outputDirectory.set(extension.outputDirectory)
            }
        val githubBundle =
            project.tasks.register("githubReleaseBundle", GenerateGithubReleaseBundle::class.java) {
                group = "release"
                description = "Builds every configured signed JAR and SHA256SUMS uploaded to GitHub Releases."
                dependsOn(manifest, "mavenCentralReleaseVerify")
                manifestFile.set(extension.outputDirectory.file("manifest.json"))
                artifactDirectory.set(extension.outputDirectory.dir("artifacts"))
                signatureDirectory.set(project.layout.buildDirectory.dir("release/maven-central/signatures"))
                outputDirectory.set(project.layout.buildDirectory.dir("release/github"))
            }

        fun registerCentralTask(
            name: String,
            description: String,
            operation: MavenCentralReleaseTask.Operation,
        ) = project.tasks.register(name, MavenCentralReleaseTask::class.java) {
            group = "release"
            this.description = description
            coordinatesFile.set(extension.mavenCoordinatesFile)
            releaseVersion.set(extension.releaseVersion)
            localRepository.set(extension.mavenLocalRepository)
            repositoryBaseUrl.set("https://repo1.maven.org/maven2/")
            this.operation.set(operation)
            receiptFile.set(project.layout.buildDirectory.file("release/maven-central/${operation.wireValue}.json"))
            outputs.upToDateWhen { false }
        }

        registerCentralTask(
            "mavenCentralReleasePreflight",
            "Rejects partial or differing immutable Central state and records whether publication is required.",
            MavenCentralReleaseTask.Operation.PREFLIGHT,
        )
        registerCentralTask(
            "mavenCentralReleaseVerify",
            "Polls until every Maven Central coordinate and immutable file matches the staged publications.",
            MavenCentralReleaseTask.Operation.VERIFY,
        ).configure {
            canonicalSignatureDirectory.set(project.layout.buildDirectory.dir("release/maven-central/signatures"))
            canonicalEvidenceDirectory.set(project.layout.buildDirectory.dir("release/maven-central/evidence"))
        }

        fun registerCentralPortalTask(
            name: String,
            description: String,
            operation: MavenCentralPortalTask.Operation,
        ) = project.tasks.register(name, MavenCentralPortalTask::class.java) {
            group = "release"
            this.description = description
            coordinatesFile.set(extension.mavenCoordinatesFile)
            releaseVersion.set(extension.releaseVersion)
            localRepository.set(extension.mavenLocalRepository)
            portalBaseUrl.set("https://central.sonatype.com/")
            username.set(project.providers.gradleProperty("mavenCentralUsername"))
            password.set(project.providers.gradleProperty("mavenCentralPassword"))
            this.operation.set(operation)
            receiptFile.set(project.layout.buildDirectory.file("release/maven-central/portal-${operation.wireValue}.json"))
            evidenceDirectory.set(project.layout.buildDirectory.dir("release/maven-central/portal-${operation.wireValue}-evidence"))
            outputs.upToDateWhen { false }
        }

        registerCentralPortalTask(
            "mavenCentralPortalPreflight",
            "Rejects duplicate or differing Portal deployments before any release write.",
            MavenCentralPortalTask.Operation.PREFLIGHT,
        )
        registerCentralPortalTask(
            "mavenCentralPortalVerify",
            "Recovers one exact Portal deployment and waits until automatic publication completes.",
            MavenCentralPortalTask.Operation.VERIFY,
        )

        fun registerNetworkTask(
            name: String,
            description: String,
            operation: ModrinthReleaseTask.Operation,
        ) = project.tasks.register(name, ModrinthReleaseTask::class.java) {
            group = "release"
            this.description = description
            dependsOn(manifest)
            manifestFile.set(extension.outputDirectory.file("manifest.json"))
            apiBaseUrl.set(extension.modrinthApiBaseUrl)
            this.operation.set(operation)
            token.set(project.providers.environmentVariable("MODRINTH_TOKEN"))
            receiptFile.set(project.layout.buildDirectory.file("release/modrinth-receipts/${operation.name.lowercase()}.json"))
            outputs.upToDateWhen { false }
        }

        val preflight =
            registerNetworkTask(
                "modrinthReleasePreflight",
                "Reads Modrinth state and rejects every conflicting v${project.version} target before writes.",
                ModrinthReleaseTask.Operation.PREFLIGHT,
            )
        val stage =
            registerNetworkTask(
                "modrinthReleaseStage",
                "Appends only missing exact listed Modrinth versions without replacing any existing release.",
                ModrinthReleaseTask.Operation.STAGE,
            )
        stage.configure { dependsOn(preflight) }
        val submit =
            registerNetworkTask(
                "modrinthReleaseSubmit",
                "Submits a completely staged Modrinth project for review without changing version metadata.",
                ModrinthReleaseTask.Operation.SUBMIT,
            )
        val finalizeProject =
            registerNetworkTask(
                "modrinthReleaseFinalizeProject",
                "Transitions the approved Modrinth project body after predecessor-release verification.",
                ModrinthReleaseTask.Operation.FINALIZE_PROJECT,
            )
        val verify =
            registerNetworkTask(
                "modrinthReleaseVerify",
                "Reads Modrinth state and proves every configured exact version is listed.",
                ModrinthReleaseTask.Operation.VERIFY,
            )
        submit.configure { mustRunAfter(stage) }
        finalizeProject.configure { mustRunAfter(submit) }
        verify.configure { mustRunAfter(finalizeProject) }

        project.afterEvaluate {
            val targets = extension.targets.toList()
            manifest.configure {
                gameVersions.set(targets.map(StrataReleaseExtension.Target::gameVersion))
                canonicalFileNames.set(targets.map(StrataReleaseExtension.Target::canonicalFileName))
                targets.forEach { target ->
                    artifactFiles.add(target.artifact)
                    orderedArtifactSha256.add(
                        target.artifact.map { artifact -> GenerateModrinthManifest.sha256(artifact.asFile) },
                    )
                    dependsOn(target.verificationTaskPath)
                }
            }
        }
    }
}
