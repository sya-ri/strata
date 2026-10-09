package dev.s7a.strata.quality.benchmark

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.HexFormat
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider

/**
 * Constructs the complete immutable provenance corpus and independent goldens outside collection.
 * Construction owns its output directory and never runs a measured target or sample loop.
 */
@Suppress("TooManyFunctions") // Corpus construction and independent digest protocols have distinct boundaries.
internal object ProvenanceHashingCorpus {
    /**
     * Exact consumer entry points; rejected empty trees are untimed admission controls.
     */
    internal enum class Mode {
        EntryDirectory,
        EntryArchive,
        LoadedClassTree,
        PreservedClassTree,
        SingleFile,
        ResourceSelection,
        EmptyDirectory,
        RejectedLoadedTree,
        RejectedPreservedTree,
    }

    /**
     * Immutable input distributions shared by the two library variants.
     */
    internal enum class Shape {
        Short,
        Mixed,
        Chunks,
        Large,
        Empty,
    }

    /**
     * One fixed corpus identity; directory and archive operations remain independent rows.
     */
    internal data class Case(val mode: Mode, val entries: Int, val shape: Shape) {
        internal val id: String = "${mode.name}-${entries}-${shape.name}"
    }

    /**
     * Complete 55 measured rows plus three independently verified rejected controls.
     */
    internal val cases: List<Case> =
        buildList {
            listOf(Mode.EntryDirectory, Mode.EntryArchive, Mode.LoadedClassTree, Mode.PreservedClassTree).forEach { mode ->
                listOf(1, 32, 1024, 4096).forEach { count ->
                    listOf(Shape.Short, Shape.Mixed, Shape.Chunks).forEach { shape -> add(Case(mode, count, shape)) }
                }
                add(Case(mode, 1, Shape.Large))
            }
            add(Case(Mode.SingleFile, 1, Shape.Short))
            add(Case(Mode.SingleFile, 1, Shape.Large))
            add(Case(Mode.ResourceSelection, 32, Shape.Mixed))
            add(Case(Mode.EmptyDirectory, 0, Shape.Empty))
            add(Case(Mode.RejectedLoadedTree, 0, Shape.Empty))
            add(Case(Mode.RejectedPreservedTree, 0, Shape.Empty))
        }

    private val markerName = "FixtureProbe00000"

    /**
     * Accepts one fresh output directory and writes its complete input manifest.
     * A full JDK compiler is required; construction failures preserve the partial directory for diagnosis.
     */
    @JvmStatic
    public fun main(args: Array<String>) {
        require(args.size == 1)
        val root = Path.of(args.single()).toAbsolutePath().normalize()
        require(Files.exists(root).not()) { "Provenance corpus output must be fresh" }
        Files.createDirectories(root)
        val templates = HashMap<Int, ByteArray>()
        try {
            val rows = JsonArray()
            cases.forEach { rows.add(create(root, it, templates)) }
            val manifest = JsonObject().apply {
                addProperty("contract", "strata-provenance-corpus-v1")
                addProperty("measured_rows", 55)
                addProperty("rejected_controls", 3)
                add("cases", rows)
            }
            Files.writeString(root.resolve("manifest.json"), GsonBuilder().setPrettyPrinting().create().toJson(manifest))
        } finally {
            templates.clear()
        }
    }

    private fun create(root: Path, case: Case, templates: MutableMap<Int, ByteArray>): JsonObject {
        val directory = root.resolve(case.id)
        Files.createDirectories(directory)
        val classMode = case.mode in setOf(Mode.LoadedClassTree, Mode.PreservedClassTree)
        repeat(case.entries) { index ->
            val classEntry = classMode || case.mode == Mode.EntryArchive && index == 0
            val size = length(case.shape, index, classEntry)
            val name = if (classEntry) "fixture/FixtureProbe${index.toString().padStart(5, '0')}.class" else "assets/entry-${index.toString().padStart(5, '0')}.bin"
            val content = if (classEntry) rename(template(root, size, templates), index) else ByteArray(size) { (it * 31 + index).toByte() }
            val file = directory.resolve(name)
            Files.createDirectories(checkNotNull(file.parent))
            Files.write(file, content)
        }
        if (case.mode == Mode.ResourceSelection) {
            Files.writeString(directory.resolve("README.md"), "Excluded public consumer metadata")
            Files.createDirectories(directory.resolve("META-INF"))
            Files.writeString(directory.resolve("META-INF/excluded.json"), "{\"excluded\":true}")
        }
        val archiveMode = case.mode in setOf(Mode.EntryArchive, Mode.LoadedClassTree, Mode.PreservedClassTree, Mode.RejectedLoadedTree, Mode.RejectedPreservedTree)
        val selected = entries(directory).filter { case.mode != Mode.ResourceSelection || it.startsWith("assets/") || it.startsWith("data/") }
        val input = when {
            archiveMode -> archive(directory, root.resolve("${case.id}.jar"))
            case.mode == Mode.SingleFile -> directory.resolve(selected.single())
            else -> directory
        }
        val hash = when (case.mode) {
            Mode.SingleFile -> hash(directory.resolve(selected.single()))
            Mode.LoadedClassTree, Mode.PreservedClassTree -> classTree(directory, selected)
            Mode.EmptyDirectory, Mode.RejectedLoadedTree, Mode.RejectedPreservedTree -> null
            else -> framed(directory, selected)
        }
        return JsonObject().apply {
            addProperty("mode", case.mode.name)
            addProperty("entries", case.entries)
            addProperty("shape", case.shape.name)
            addProperty("path", input.toString())
            addProperty("sha256", hash)
            addProperty("entry_bytes", selected.sumOf { Files.size(directory.resolve(it)) })
            addProperty("marker", "assets/entry-00000.bin")
            addProperty("representative", "fixture.$markerName")
            addProperty("physical_sha256", if (Files.isRegularFile(input)) hash(input) else framed(directory, entries(directory)))
        }
    }

    private fun length(shape: Shape, index: Int, classEntry: Boolean): Int =
        when (shape) {
            Shape.Short -> if (classEntry) 0 else listOf(0, 1, 7)[index % 3]
            Shape.Mixed -> if (classEntry) listOf(0, 8191, 8192, 8193, 32769)[index % 5] else listOf(0, 1, 7, 8191, 8192, 8193, 32769)[index % 7]
            Shape.Chunks -> listOf(8191, 8192, 8193)[index % 3]
            Shape.Large -> if (classEntry) 32769 else 64 * 1024 * 1024
            Shape.Empty -> error("Empty inputs contain no entries")
        }

    private fun template(root: Path, target: Int, templates: MutableMap<Int, ByteArray>): ByteArray =
        templates.getOrPut(target) {
            val compiler = checkNotNull(ToolProvider.getSystemJavaCompiler()) { "Provenance construction requires a full JDK" }
            val directory = root.resolve("compiled-template-$target")
            Files.createDirectories(directory)
            val source = directory.resolve("$markerName.java")
            fun compile(padding: Int): ByteArray {
                Files.writeString(source, "package fixture; public final class $markerName { public static final String PAD = \"${"a".repeat(padding)}\"; }")
                check(compiler.run(null, null, null, "-d", directory.toString(), source.toString()) == 0) { "Fixture compilation failed" }
                return Files.readAllBytes(directory.resolve("fixture/$markerName.class"))
            }
            val minimal = compile(0)
            if (target == 0) minimal else compile(target - minimal.size).also { check(it.size == target) }
        }

    private fun rename(template: ByteArray, index: Int): ByteArray {
        val from = markerName.toByteArray(Charsets.US_ASCII)
        val to = "FixtureProbe${index.toString().padStart(5, '0')}".toByteArray(Charsets.US_ASCII)
        check(from.size == to.size)
        val bytes = template.copyOf()
        var replacements = 0
        for (offset in 0..(bytes.size - from.size)) {
            if (from.indices.all { bytes[offset + it] == from[it] }) {
                to.copyInto(bytes, offset)
                replacements += 1
            }
        }
        check(0 < replacements)
        return bytes
    }

    private fun entries(directory: Path): List<String> =
        Files.walk(directory).use { files ->
            files.filter(Files::isRegularFile).map { directory.relativize(it).joinToString("/") }.sorted().toList()
        }

    private fun archive(directory: Path, path: Path): Path {
        JarOutputStream(Files.newOutputStream(path)).use { output ->
            entries(directory).forEach { name ->
                output.putNextEntry(JarEntry(name).apply { time = 0 })
                Files.newInputStream(directory.resolve(name)).use { it.transferTo(output) }
                output.closeEntry()
            }
        }
        return path
    }

    private fun framed(directory: Path, entries: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        entries.forEach { name ->
            val encoded = name.toByteArray(Charsets.UTF_8)
            digest.update("${encoded.size}:".toByteArray(Charsets.US_ASCII))
            digest.update(encoded)
            digest.update(0.toByte())
            Files.newInputStream(directory.resolve(name)).use { input -> DigestInputStream(input, digest).transferTo(OutputStream.nullOutputStream()) }
            digest.update(255.toByte())
        }
        return HexFormat.of().formatHex(digest.digest())
    }

    private fun classTree(directory: Path, entries: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        entries.forEach { name -> digest.update("$name=${hash(directory.resolve(name))}\n".toByteArray(Charsets.UTF_8)) }
        return HexFormat.of().formatHex(digest.digest())
    }

    private fun hash(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input -> DigestInputStream(input, digest).transferTo(OutputStream.nullOutputStream()) }
        return HexFormat.of().formatHex(digest.digest())
    }
}
