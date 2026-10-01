package dev.s7a.strata.performance

import java.net.JarURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

/**
 * Hashes actual loaded code and resources independently of file naming and advertised versions.
 */
public object ArtifactIdentity {
    /**
     * Records applicationTrees evidence from the actual local bytes.
     */
    public fun applicationTrees(representatives: List<Class<*>>): Map<String, String> =
        mutableMapOf<Path, String>().let { cache ->
            representatives
                .distinctBy(Class<*>::getName)
                .sortedBy(Class<*>::getName)
                .associate { type ->
                    val source = codeSource(type)
                    type.name to cache.getOrPut(source) { selectedCodeSourceSha256(source) }
                }
        }

    /**
     * Records fullCodeSource evidence from the actual local bytes.
     */
    public fun fullCodeSource(type: Class<*>): String = fullCodeSourceSha256(codeSource(type))

    /**
     * Records file evidence from the actual local bytes.
     */
    public fun file(path: Path): String {
        require(Files.isRegularFile(path)) { "Missing benchmark identity file: $path" }
        return Files.newInputStream(path).use(EntryIdentity::sha256)
    }

    /**
     * Records tree evidence from the actual local bytes.
     */
    public fun tree(path: Path): String {
        require(Files.isDirectory(path)) { "Missing benchmark identity directory: $path" }
        return EntryIdentity.directorySha256(path) { true }
    }

    /**
     * Records resourceTree evidence from the actual local bytes.
     */
    public fun resourceTree(
        root: Path,
        marker: String,
    ): Map<String, String> {
        val normalized = root.toAbsolutePath().normalize()
        val markerPath = normalized.resolve(marker).normalize()
        require(markerPath.startsWith(normalized) && Files.isRegularFile(markerPath)) {
            "Missing benchmark resource marker: $markerPath"
        }
        return mapOf(
            "marker_url" to markerPath.toUri().toString(),
            "sha256" to EntryIdentity.directorySha256(normalized, EntryIdentity::selectedResourceEntry),
        )
    }

    /**
     * Records loadedResourceTree evidence from the actual local bytes.
     */
    public fun loadedResourceTree(
        loader: ClassLoader,
        marker: String,
    ): Map<String, String> {
        val markerUrl = checkNotNull(loader.getResource(marker)) { "Missing loaded benchmark resource marker: $marker" }
        val sha256 =
            when (LocalResourceProtocol.decode(markerUrl.protocol)) {
                LocalResourceProtocol.File -> {
                    val markerPath = Path.of(markerUrl.toURI()).toAbsolutePath().normalize()
                    var root = markerPath
                    repeat(marker.split('/').size) { root = checkNotNull(root.parent) }
                    require(root.resolve(marker).normalize() == markerPath) { "Unexpected loaded resource root: $markerUrl" }
                    EntryIdentity.directorySha256(root, EntryIdentity::selectedResourceEntry)
                }

                LocalResourceProtocol.Jar -> {
                    val connection = markerUrl.openConnection() as? JarURLConnection
                    val jar = checkNotNull(connection?.jarFileURL) { "Missing loaded resource JAR: $markerUrl" }
                    require(LocalResourceProtocol.decode(jar.protocol) == LocalResourceProtocol.File && jar.host.isEmpty()) { "Non-local loaded resource JAR: $jar" }
                    EntryIdentity.zipSha256(Path.of(jar.toURI()).toAbsolutePath().normalize(), EntryIdentity::selectedResourceEntry)
                }

                LocalResourceProtocol.Other -> {
                    error("Unsupported loaded resource URL: $markerUrl")
                }
            }
        return mapOf("marker_url" to markerUrl.toExternalForm(), "sha256" to sha256)
    }

    private fun codeSource(type: Class<*>): Path {
        val location = checkNotNull(type.protectionDomain?.codeSource?.location) { "Missing code source for ${type.name}" }
        require(LocalResourceProtocol.decode(location.protocol) == LocalResourceProtocol.File && location.host.isEmpty()) { "Non-local code source for ${type.name}: $location" }
        return Path.of(URI(location.toExternalForm())).toAbsolutePath().normalize()
    }

    private fun selectedCodeSourceSha256(path: Path): String =
        when {
            Files.isDirectory(path) -> EntryIdentity.directorySha256(path, EntryIdentity::selectedEntry)
            Files.isRegularFile(path) -> EntryIdentity.zipSha256(path, EntryIdentity::selectedEntry)
            else -> error("Missing application code source: $path")
        }

    private fun fullCodeSourceSha256(path: Path): String =
        when {
            Files.isDirectory(path) -> EntryIdentity.directorySha256(path) { true }
            Files.isRegularFile(path) -> file(path)
            else -> error("Missing runtime code source: $path")
        }
}
