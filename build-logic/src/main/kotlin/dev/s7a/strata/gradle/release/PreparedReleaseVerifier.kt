package dev.s7a.strata.gradle.release

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.io.File
import java.net.URI

/**
 * Reconciles prepared distributions without configuring or building product projects.
 */
internal object PreparedReleaseVerifier {
    /**
     * Reads credentials only from the environment and writes redacted receipts outside the immutable bundle.
     */
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 2) { "Expected operation and prepared release directory." }
        val operation = Operation.entries.single { it.argument == args[0] }
        val root = File(args[1])
        val identity = JsonSlurper().parse(root.resolve("prepared.json")) as Map<*, *>
        val version = (identity["tag"] as String).removePrefix("v")
        val output = root.parentFile.resolve("receipts").also(File::mkdirs)
        val coordinates = MavenReleaseCoordinates.resolve(root.resolve("maven-coordinates.txt").readLines(), version)
        val files = root.resolve("maven-files.txt").readLines()
        val receipt =
            when (operation) {
                Operation.CENTRAL_PREFLIGHT, Operation.CENTRAL_VERIFY -> {
                    val verifier = MavenCentralReleaseVerifier(root.resolve("maven").toPath(), URI("https://repo1.maven.org/maven2/"), publicationFiles = files)
                    val result = if (operation == Operation.CENTRAL_PREFLIGHT) verifier.preflight(coordinates) else verifier.verify(coordinates)
                    if (operation == Operation.CENTRAL_VERIFY) {
                        verifier.stageCanonicalPublicationEvidence(coordinates, output.resolve("central").toPath())
                    }
                    mapOf("state" to result.state.wireValue, "coordinateCount" to result.coordinateCount)
                }

                Operation.PORTAL_PREFLIGHT, Operation.PORTAL_VERIFY -> {
                    val verifier =
                        MavenCentralPortalCoordinator(
                            portalBaseUri = URI("https://central.sonatype.com/"),
                            username = requireNotNull(System.getenv("ORG_GRADLE_PROJECT_mavenCentralUsername")),
                            password = requireNotNull(System.getenv("ORG_GRADLE_PROJECT_mavenCentralPassword")),
                            localRepository = root.resolve("maven").toPath(),
                            publicationFiles = files,
                        )
                    val evidence = output.resolve(operation.argument).toPath()
                    val result = if (operation == Operation.PORTAL_PREFLIGHT) verifier.preflight(coordinates, evidence) else verifier.verifyUntilPublished(coordinates, evidence)
                    mapOf("state" to result.state.wireValue, "deploymentId" to result.deploymentId)
                }

                Operation.MODRINTH_STAGE, Operation.MODRINTH_VERIFY -> {
                    val manifest = ModrinthManifest.read(root.resolve("modrinth/manifest.json"))
                    val client =
                        ModrinthApiClient(
                            "https://api.modrinth.com/v2",
                            requireNotNull(System.getenv("MODRINTH_TOKEN")),
                            "sya-ri/strata/$version (https://github.com/sya-ri/strata)",
                        )
                    val coordinator = ModrinthReleaseCoordinator(manifest, root.resolve("modrinth"), client, ModrinthReleaseCoordinator.ProjectPolicy.VERSIONS_ONLY)
                    val result = if (operation == Operation.MODRINTH_STAGE) coordinator.stage() else coordinator.verify()
                    mapOf(
                        "operation" to result.operation,
                        "projectId" to result.projectId,
                        "projectStatus" to result.projectStatus,
                        "absent" to result.absent,
                        "listed" to result.listed,
                    )
                }
            }
        output.resolve("${operation.argument}.json").writeText(JsonOutput.prettyPrint(JsonOutput.toJson(receipt)) + "\n")
    }

    /**
     * Explicit remote operations supported by the publication runner.
     */
    private enum class Operation(
        val argument: String,
    ) {
        CENTRAL_PREFLIGHT("central-preflight"),
        CENTRAL_VERIFY("central-verify"),
        PORTAL_PREFLIGHT("portal-preflight"),
        PORTAL_VERIFY("portal-verify"),
        MODRINTH_STAGE("modrinth-stage"),
        MODRINTH_VERIFY("modrinth-verify"),
    }
}
