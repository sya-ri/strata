package dev.s7a.strata.performance

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import java.io.InputStream
import java.net.JarURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Bounded provenance from the actual loaded classes, without substituting standalone distribution JARs.
 * Missing locations remain explicit failures rather than inferred hashes.
 */
public object LoadedArtifactMetadata {
    // Evidence preparation must not scan accidentally selected oversized archives without a bound.
    private const val MAX_HASH_BYTES = 64L * 1024 * 1024
    private const val MAX_CLASS_TREE_BYTES = 64L * 1024 * 1024
    private const val MAX_CLASS_ENTRY_BYTES = 8L * 1024 * 1024
    private const val MAX_CLASS_ENTRIES = 16_384
    private const val CLASS_TREE_ALGORITHM = "sha256(path=sha256(resource-bytes)\\n)-utf8-v1"

    /**
     * Resolves registered representative classes without initialization through the measured host loader.
     * Class trees validate every resource against that class's actual archive origin.
     */
    public fun capture(
        loader: ClassLoader,
        representatives: Map<String, String>,
        classTreeModules: Set<String>,
    ): JsonObject =
        JsonObject().apply {
            addProperty(
                "scope",
                "Representative classes and every class resource in registered module code sources are resolved " +
                    "without class initialization through the measured host class loader. Hashes identify the exact " +
                    "local code-source file/JAR entry and class resource bytes at setup, " +
                    "not transformed in-memory bytecode. Fabric processed/nested wrappers are not substituted " +
                    "with standalone Maven JARs. Unavailable locations or hashes retain their failure reason.",
            )
            add(
                "modules",
                JsonArray().apply {
                    representatives.forEach { (module, className) -> add(captureClass(loader, module, className, module in classTreeModules)) }
                },
            )
        }

    /**
     * Requires every registered module and requested class tree to resolve before measurement.
     * An unavailable location is preparation failure, rather than an unsupported zero-cost host.
     */
    public fun verifyComplete(report: JsonObject) {
        val modules = report.getAsJsonArray("modules")
        require(modules.isEmpty.not()) { "No measured runtime modules were registered" }
        modules.forEach { entry ->
            val module = entry.asJsonObject
            check(ArtifactResolution.decode(module.get("status").asString) == ArtifactResolution.Resolved) { "Unresolved runtime module: $module" }
            listOf("codeSource", "classResource").forEach { location ->
                check(ArtifactResolution.decode(module.getAsJsonObject(location).get("status").asString) == ArtifactResolution.Available) { "Unavailable runtime location: $module" }
            }
            if (module.has("classTree")) {
                check(ArtifactResolution.decode(module.getAsJsonObject("classTree").get("status").asString) == ArtifactResolution.Available) { "Unavailable runtime class tree: $module" }
            }
        }
    }

    /**
     * Hashes the exact loaded representative class resources and their nested declaration closure.
     */
    public fun captureClassHashes(classes: List<Class<*>>): JsonObject =
        JsonObject().apply {
            classClosure(classes).forEach { type ->
                val name = "/${type.name.replace('.', '/')}.class"
                val resource = checkNotNull(type.getResource(name)) { "Missing compiled class resource: $name" }
                val hash =
                    resource
                        .openConnection()
                        .apply { useCaches = false }
                        .getInputStream()
                        .use(::sha256)
                        .first
                addProperty(type.name, hash)
            }
        }

    private fun classClosure(classes: List<Class<*>>): List<Class<*>> =
        classes
            .flatMap { type -> listOf(type) + classClosure(type.declaredClasses.toList()) }
            .distinctBy(Class<*>::getName)
            .sortedBy(Class<*>::getName)

    private fun captureClass(
        loader: ClassLoader,
        module: String,
        className: String,
        includeClassTree: Boolean,
    ): JsonObject =
        JsonObject().apply {
            addProperty("module", module)
            addProperty("representativeClass", className)
            runCatching {
                val type = Class.forName(className, false, loader)
                val codeSource = type.protectionDomain?.codeSource?.location
                addProperty("classLoader", type.classLoader?.toString())
                addProperty("packageImplementationVersion", type.`package`?.implementationVersion)
                add("codeSource", captureLocation { codeSource })
                add("classResource", captureLocation { type.getResource("/${className.replace('.', '/')}.class") })
                if (includeClassTree) {
                    add("classTree", captureClassTree(type, codeSource))
                }
                addProperty("status", "resolved")
            }.onFailure {
                addProperty("status", "unavailable")
                addProperty("reason", it.toString())
            }
        }

    private fun captureClassTree(
        representative: Class<*>,
        codeSource: URL?,
    ): JsonObject =
        JsonObject().apply {
            add("sha256", JsonNull.INSTANCE)
            runCatching {
                require(codeSource != null) { "The loaded class did not expose a code source" }
                val archivePath = requireLocalFile(codeSource)
                val entries = ClassArchiveInventory.entries(archivePath, MAX_CLASS_ENTRIES, MAX_CLASS_ENTRY_BYTES)
                val classLoader = checkNotNull(representative.classLoader) { "The Strata class has no class loader" }
                val treeDigest = MessageDigest.getInstance("SHA-256")
                var totalBytes = 0L
                entries.forEach { entry ->
                    val resource = checkNotNull(classLoader.getResource(entry)) { "Missing classpath resource: $entry" }
                    requireLocalClassResource(resource, entry, archivePath)
                    val connection = resource.openConnection().apply { useCaches = false }
                    val (entryHash, entryBytes) =
                        connection.getInputStream().use { input -> sha256(input, MAX_CLASS_ENTRY_BYTES) }
                    require(0 < entryBytes) { "Empty classpath resource: $entry" }
                    totalBytes = Math.addExact(totalBytes, entryBytes)
                    require(totalBytes <= MAX_CLASS_TREE_BYTES) { "The class tree exceeds the metadata byte limit" }
                    treeDigest.update(entry.toByteArray(Charsets.UTF_8))
                    treeDigest.update('='.code.toByte())
                    treeDigest.update(entryHash.toByteArray(Charsets.US_ASCII))
                    treeDigest.update('\n'.code.toByte())
                }
                addProperty("algorithm", CLASS_TREE_ALGORITHM)
                addProperty("entryCount", entries.size)
                addProperty("bytes", totalBytes)
                addProperty("sha256", HexFormat.of().formatHex(treeDigest.digest()))
                addProperty("status", "available")
            }.onFailure {
                addProperty("status", "unavailable")
                addProperty("reason", it.toString())
            }
        }

    private fun requireLocalClassResource(
        location: URL,
        expectedEntry: String,
        expectedArchive: Path,
    ) {
        val connection = location.openConnection().apply { useCaches = false }
        require(LocalResourceProtocol.decode(location.protocol) == LocalResourceProtocol.Jar && connection is JarURLConnection) {
            "The class resource URL is not backed by a standard local JAR"
        }
        require(connection.entryName == expectedEntry) {
            "The classpath resource resolved a different entry: ${connection.entryName}"
        }
        val resolvedArchive = requireLocalFile(connection.jarFileURL)
        require(Files.isSameFile(expectedArchive, resolvedArchive)) {
            "The classpath resource resolved from a different code source: ${connection.jarFileURL}"
        }
    }

    private fun captureLocation(resolve: () -> URL?): JsonObject =
        JsonObject().apply {
            add("sha256", JsonNull.INSTANCE)
            runCatching {
                val location = resolve()
                addProperty("url", location?.toExternalForm())
                require(location != null) { "The loaded class did not expose this location" }
                var inputLocation = location
                val connection = location.openConnection().apply { useCaches = false }
                when (LocalResourceProtocol.decode(location.protocol)) {
                    LocalResourceProtocol.File -> {
                        requireLocalFile(location)
                        addProperty("kind", "file")
                    }

                    LocalResourceProtocol.Jar -> {
                        require(connection is JarURLConnection) { "The jar URL has no standard entry metadata" }
                        requireLocalFile(connection.jarFileURL)
                        addProperty("containerUrl", connection.jarFileURL.toExternalForm())
                        addProperty("entryName", connection.entryName)
                        if (connection.entryName == null) {
                            inputLocation = connection.jarFileURL
                            addProperty("kind", "jar_root")
                        } else {
                            // The entry is read directly; nested JAR evidence does not hash the outer archive.
                            addProperty("kind", "jar_entry")
                        }
                    }

                    LocalResourceProtocol.Other -> {
                        error("Unsupported local artifact protocol: ${location.protocol}")
                    }
                }
                addProperty("hashedUrl", inputLocation.toExternalForm())
                val inputConnection =
                    if (inputLocation == location) connection else inputLocation.openConnection().apply { useCaches = false }
                val (hash, bytes) = inputConnection.getInputStream().use(::sha256)
                addProperty("sha256", hash)
                addProperty("bytes", bytes)
                addProperty("status", "available")
            }.onFailure {
                addProperty("status", "unavailable")
                addProperty("reason", it.toString())
            }
        }

    private fun requireLocalFile(location: URL): Path {
        require(LocalResourceProtocol.decode(location.protocol) == LocalResourceProtocol.File && location.host.isEmpty()) { "Only local file-backed artifacts are read" }
        val path = Path.of(location.toURI())
        require(Files.isRegularFile(path)) { "The location is not a regular file: $location" }
        require(Files.size(path) <= MAX_HASH_BYTES) { "The artifact exceeds the metadata hash limit: $location" }
        return path
    }

    private fun sha256(
        input: InputStream,
        maxBytes: Long = MAX_HASH_BYTES,
    ): Pair<String, Long> {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        var bytes = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            bytes += count
            require(bytes <= maxBytes) { "The artifact entry exceeds the metadata hash limit" }
            digest.update(buffer, 0, count)
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } to bytes
    }
}
