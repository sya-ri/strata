package dev.s7a.strata.quality.benchmark

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.s7a.strata.performance.ArtifactIdentity
import dev.s7a.strata.performance.LoadedArtifactMetadata
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile

/**
 * Invokes the actual library archive through an isolated platform-parent loader.
 * The unchanged outer collector certifies every target helper's real archive and complete class tree.
 * Reflection crosses the two Kotlin/Gson namespaces without sharing target classes with the collector.
 */
@Suppress("TooManyFunctions") // Target invocation, validation and certification remain separate untimed boundaries.
internal class ProvenanceHashingTarget(private val archive: Path, controls: Collection<Path>) : AutoCloseable {
    private val loader = URLClassLoader("provenance-target", (listOf(archive) + controls).map { it.toUri().toURL() }.toTypedArray(), ClassLoader.getPlatformClassLoader())
    private val identity = resolve { helper(ArtifactIdentity::class.java.name) }
    private val metadata = resolve { helper(LoadedArtifactMetadata::class.java.name) }
    private val preserved = resolve { helper("dev.s7a.strata.performance.JvmEvidenceFiles") }
    private val foreignJson = resolve { Class.forName(JsonObject::class.java.name, false, loader) }
    private val capture = resolve { metadata.javaClass.getMethod("capture", ClassLoader::class.java, Map::class.java, Set::class.java) }
    private val verify = resolve { metadata.javaClass.getMethod("verifyComplete", foreignJson) }
    private val classTree = resolve {
        preserved.javaClass.declaredMethods.single {
            it.name.substringBefore('$').contentEquals("classTree") && it.parameterTypes.contentEquals(arrayOf(Path::class.java))
        }.apply { isAccessible = true }
    }
    private val directory = resolve { identity.javaClass.getMethod("tree", Path::class.java) }
    private val file = resolve { identity.javaClass.getMethod("file", Path::class.java) }
    private val application = resolve { identity.javaClass.getMethod("applicationTrees", List::class.java) }
    private val resources = resolve { identity.javaClass.getMethod("resourceTree", Path::class.java, String::class.java) }

    /**
     * Detached certification of the actual helper origins, representative bytes and every target class.
     */
    internal fun certify(): JsonObject {
        val representatives = mapOf("identity" to identity.javaClass.name, "metadata" to metadata.javaClass.name, "preserved" to preserved.javaClass.name)
        val report = LoadedArtifactMetadata.capture(loader, representatives, representatives.keys)
        LoadedArtifactMetadata.verifyComplete(report)
        return report
    }

    /**
     * Opens an immutable consumer input and validates every executable class without initialization.
     */
    internal fun prepare(row: JsonObject): Prepared = Prepared(row)

    /**
     * Probes actual entry reads independently of the sampled public operation.
     */
    internal fun probe(row: JsonObject): JsonObject = ProvenanceHashingProbe.entries(loader, row)

    override fun close() {
        loader.close()
    }

    private fun <T> resolve(operation: () -> T): T = try {
        operation()
    } catch (failure: Throwable) {
        runCatching(loader::close).exceptionOrNull()?.let(failure::addSuppressed)
        throw failure
    }

    private fun helper(name: String): Any {
        val type = Class.forName(name, false, loader)
        check(type.classLoader === loader) { "Target helper escaped its isolated loader: $name" }
        val source = Path.of(checkNotNull(type.protectionDomain.codeSource).location.toURI())
        check(Files.isSameFile(source, archive)) { "Target helper resolved from a different archive: $name" }
        return type.getField("INSTANCE").get(null)
    }

    private fun call(receiver: Any, method: Method, vararg arguments: Any): Any =
        try {
            method.invoke(receiver, *arguments) ?: Unit
        } catch (failure: InvocationTargetException) {
            throw checkNotNull(failure.cause)
        }

    /**
     * Owns one input loader; validation and closure remain outside timed operations.
     */
    internal inner class Prepared(private val row: JsonObject) : AutoCloseable {
        private val mode = enumValues<ProvenanceHashingCorpus.Mode>().single { it.name.contentEquals(row.get("mode").asString) }
        private val path = Path.of(row.get("path").asString)
        private val inputLoader = URLClassLoader("provenance-input", arrayOf(path.toUri().toURL()), ClassLoader.getPlatformClassLoader())
        private val representative = row.get("representative").asString
        private val loadedClasses: List<Class<*>> = prepareClasses()

        private fun prepareClasses(): List<Class<*>> = try {
            if (mode in setOf(ProvenanceHashingCorpus.Mode.EntryArchive, ProvenanceHashingCorpus.Mode.LoadedClassTree, ProvenanceHashingCorpus.Mode.PreservedClassTree)) {
                JarFile(path.toFile()).use { input ->
                    input.entries().asSequence().filter { it.name.endsWith(".class") }.map { entry ->
                        Class.forName(entry.name.removeSuffix(".class").replace('/', '.'), false, inputLoader).also { type ->
                            check(type.classLoader === inputLoader && Files.isSameFile(Path.of(checkNotNull(type.protectionDomain.codeSource).location.toURI()), path))
                        }
                    }.toList()
                }
            } else emptyList()
        } catch (failure: Throwable) {
            runCatching(inputLoader::close).exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }

        /**
         * Computes the complete public consumer operation or actual preserved-evidence verification.
         */
        internal fun operation(): Any = when (mode) {
            ProvenanceHashingCorpus.Mode.EntryDirectory, ProvenanceHashingCorpus.Mode.EmptyDirectory -> call(identity, directory, path)
            ProvenanceHashingCorpus.Mode.SingleFile -> call(identity, file, path)
            ProvenanceHashingCorpus.Mode.EntryArchive -> call(identity, application, listOf(loadedClasses.single { it.name.contentEquals(representative) }))
            ProvenanceHashingCorpus.Mode.ResourceSelection -> call(identity, resources, path, row.get("marker").asString)
            ProvenanceHashingCorpus.Mode.LoadedClassTree, ProvenanceHashingCorpus.Mode.RejectedLoadedTree -> call(metadata, capture, inputLoader, mapOf("fixture" to representative), setOf("fixture"))
            ProvenanceHashingCorpus.Mode.PreservedClassTree, ProvenanceHashingCorpus.Mode.RejectedPreservedTree -> call(preserved, classTree, path)
        }

        /**
         * Checks an independent golden and actual entry/byte counters after the complete operation.
         */
        internal fun validate(result: Any): JsonObject {
            val tree = when (mode) {
                ProvenanceHashingCorpus.Mode.LoadedClassTree -> {
                    call(metadata, verify, result)
                    detach(result).getAsJsonArray("modules").single().asJsonObject.getAsJsonObject("classTree")
                }
                ProvenanceHashingCorpus.Mode.PreservedClassTree -> detach(result)
                else -> null
            }
            val hash = tree?.get("sha256")?.asString ?: when (mode) {
                ProvenanceHashingCorpus.Mode.EntryArchive -> (result as Map<*, *>).values.single() as String
                ProvenanceHashingCorpus.Mode.ResourceSelection -> (result as Map<*, *>)["sha256"] as String
                else -> result as String
            }
            check(hash.contentEquals(row.get("sha256").asString)) { "Complete target hash differs from independent golden: $row" }
            tree?.let {
                check(it.get("entryCount").asInt == row.get("entries").asInt && it.get("bytes").asLong == row.get("entry_bytes").asLong)
            }
            return JsonObject().apply {
                addProperty("sha256", hash)
                add("actual_tree", tree?.deepCopy())
                addProperty("entry_byte_observation", if (tree == null) "Independent input inventory; target stream probe required" else "Actual complete target class-tree report")
            }
        }

        /**
         * Proves rejected empty public inputs outside timing and retains their actual failure.
         */
        internal fun rejection(): JsonObject {
            var response: Any? = null
            val failure = runCatching {
                val result = operation()
                response = result
                if (mode == ProvenanceHashingCorpus.Mode.RejectedLoadedTree) call(metadata, verify, result)
            }.exceptionOrNull()
            val rejectedFailure = checkNotNull(failure)
            check(if (mode == ProvenanceHashingCorpus.Mode.RejectedLoadedTree) rejectedFailure is IllegalStateException else rejectedFailure is IllegalArgumentException) { "Empty input was not rejected by the actual consumer" }
            return JsonObject().apply {
                add("input", row.deepCopy())
                addProperty("failure_type", rejectedFailure.javaClass.name)
                addProperty("message", rejectedFailure.message)
                response?.let { add("actual_response", detach(it)) }
            }
        }

        override fun close() {
            inputLoader.close()
        }
    }

    private fun detach(value: Any): JsonObject = JsonParser.parseString(value.toString()).asJsonObject
}
