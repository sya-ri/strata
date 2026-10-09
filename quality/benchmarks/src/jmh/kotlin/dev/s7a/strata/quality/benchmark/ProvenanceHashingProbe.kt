package dev.s7a.strata.quality.benchmark

import com.google.gson.JsonObject
import java.io.FilterInputStream
import java.io.InputStream
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.util.IdentityHashMap
import java.util.zip.ZipFile

/**
 * Observes actual entry-traversal reads outside timing through its real stream-opening callback.
 * Array identity counts describe observed read scratch, not every allocation or digest encoding.
 * Opaque loaded/preserved operations retain explicit gaps rather than inferred work counts.
 */
internal object ProvenanceHashingProbe {
    /**
     * Returns one detached observation; no stream, callback, class loader or scratch survives the call.
     */
    internal fun entries(loader: ClassLoader, row: JsonObject): JsonObject {
        val mode = enumValues<ProvenanceHashingCorpus.Mode>().single { it.name.contentEquals(row.get("mode").asString) }
        val path = Path.of(row.get("path").asString)
        val buffers = IdentityHashMap<ByteArray, Boolean>()
        var reads = 0L
        var bytes = 0L
        var opened = 0
        var closed = 0
        fun observe(input: InputStream): InputStream {
            opened += 1
            return object : FilterInputStream(input) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    buffers[buffer] = true
                    reads += 1
                    return `in`.read(buffer, offset, length).also { if (0 < it) bytes += it }
                }

                override fun read(buffer: ByteArray): Int = read(buffer, 0, buffer.size)

                override fun close() {
                    closed += 1
                    super.close()
                }
            }
        }
        val archive = if (mode == ProvenanceHashingCorpus.Mode.EntryArchive) ZipFile(path.toFile()) else null
        return archive.use {
            val names = archive?.entries()?.asSequence()?.filterNot { it.isDirectory }?.map { it.name }?.sorted()?.toList() ?: Files.walk(path).use { files ->
                files.filter(Files::isRegularFile).map { path.relativize(it).joinToString("/") }.filter { name ->
                    mode != ProvenanceHashingCorpus.Mode.ResourceSelection || name.startsWith("assets/") || name.startsWith("data/")
                }.sorted().toList()
            }
            val callback = Class.forName("kotlin.jvm.functions.Function1", false, loader)
            val opener = Proxy.newProxyInstance(loader, arrayOf(callback)) { _, method, arguments ->
                check(method.name.contentEquals("invoke")) { "Unexpected traversal callback method" }
                val name = checkNotNull(arguments).single() as String
                observe(if (archive == null) Files.newInputStream(path.resolve(name)) else archive.getInputStream(checkNotNull(archive.getEntry(name))))
            }
            val type = Class.forName("dev.s7a.strata.performance.EntryIdentity", false, loader)
            val method = type.declaredMethods.single { it.name.contentEquals("hashEntries") && it.parameterCount == 2 }.apply { isAccessible = true }
            val hash = try {
                method.invoke(type.getField("INSTANCE").get(null), names, opener) as String
            } catch (failure: InvocationTargetException) {
                throw checkNotNull(failure.cause)
            }
            check(hash.contentEquals(row.get("sha256").asString))
            check(opened == names.size && closed == opened && bytes == row.get("entry_bytes").asLong)
            JsonObject().apply {
                addProperty("scope", "Actual private entry traversal using real input streams; separate from public operation timing")
                addProperty("entries", opened)
                addProperty("bytes", bytes)
                addProperty("closed_streams", closed)
                addProperty("read_calls", reads)
                addProperty("observed_distinct_read_arrays", buffers.size)
                add("class_materializations", null)
                add("digest_encodings", null)
                addProperty("remaining_gap", "Read identities do not count every allocation, class materialization or digest encoding")
            }
        }
    }
}
