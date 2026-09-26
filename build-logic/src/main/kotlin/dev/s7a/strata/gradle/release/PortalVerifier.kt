package dev.s7a.strata.gradle.release

import groovy.json.JsonOutput
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

/** Runs the controller's read-only verifier against the immutable product's staged inventory. */
internal object PortalVerifier {
    /** Credentials stay in the process environment; only verified receipt and artifact evidence are written. */
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 6) { "Expected operation, version, coordinates, files, repository, and output." }
        val operation = MavenCentralPortalTask.Operation.entries.single { it.wireValue == args[0] }
        val output = Path.of(args[5])
        val receipt = output.resolve("portal-${operation.wireValue}.json")
        Files.deleteIfExists(receipt)
        val evidence = output.resolve("portal-${operation.wireValue}-evidence")
        evidence.toFile().deleteRecursively()
        val coordinator =
            MavenCentralPortalCoordinator(
                portalBaseUri = URI("https://central.sonatype.com/"),
                username = requireNotNull(System.getenv("ORG_GRADLE_PROJECT_mavenCentralUsername")),
                password = requireNotNull(System.getenv("ORG_GRADLE_PROJECT_mavenCentralPassword")),
                localRepository = Path.of(args[4]),
                publicationFiles = args[3].takeIf(String::isNotEmpty)?.let { Files.readAllLines(Path.of(it)) },
            )
        val coordinates = MavenReleaseCoordinates.resolve(Files.readAllLines(Path.of(args[2])), args[1])
        val result =
            when (operation) {
                MavenCentralPortalTask.Operation.PREFLIGHT -> coordinator.preflight(coordinates, evidence)
                MavenCentralPortalTask.Operation.VERIFY -> coordinator.verifyUntilPublished(coordinates, evidence)
            }
        Files.createDirectories(output)
        Files.writeString(
            receipt,
            JsonOutput.prettyPrint(
                JsonOutput.toJson(
                    linkedMapOf(
                        "operation" to operation.wireValue,
                        "state" to result.state.wireValue,
                        "deploymentId" to result.deploymentId,
                        "deploymentState" to result.deploymentState?.wireValue,
                        "verifiedContentFileCount" to result.verifiedContentFileCount,
                        "verifiedChecksumCount" to result.verifiedChecksumCount,
                    ),
                ),
            ) + "\n",
        )
    }
}
