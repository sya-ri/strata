package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.integration.docs.loadFonts
import dev.s7a.strata.performance.JvmPerformanceInputs
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontCompatibility
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontDiagnostic
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontEngine
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontGlyph
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontLoadLimits
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontOptions
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontSnapshot
import dev.s7a.strata.runtime.minecraft.font.MinecraftIndexedFontAssetSource
import dev.s7a.strata.runtime.minecraft.font.lwjgl.LwjglMinecraftFontBackendFactory
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Immutable filesystem descriptors shared by both runtime variants; owns no open stream or source.
 * The only object file is the consumed default font, so an unexpected unrelated read fails immediately.
 *
 * @property input exact index size and hash shape.
 * @property index frozen JSON file.
 * @property objects frozen content-addressed object root.
 * @property archive frozen lower-priority pack.
 * @property directory frozen highest-priority pack.
 */
public class IndexedFontAssetFiles private constructor(
    public val input: IndexedFontInput,
    public val index: Path,
    public val objects: Path,
    public val archive: Path,
    public val directory: Path,
) : AutoCloseable {
    /**
     * Constructs the actual indexed source under unchanged default finite ceilings.
     */
    public fun source(): MinecraftIndexedFontAssetSource = MinecraftIndexedFontAssetSource(index, objects, "Minecraft assets", limits)

    /**
     * Runs the actual compiled documentation example, including its constructor and complete snapshot load.
     */
    public fun loadExample(): MinecraftFontSnapshot = source().loadFonts(archive, directory, compatibility, options, limits)

    /**
     * Consumes IDs, complete diagnostics and deterministic detached glyphs after the engine closes.
     */
    public fun output(snapshot: MinecraftFontSnapshot): Output =
        MinecraftFontEngine(snapshot, LwjglMinecraftFontBackendFactory).use { engine ->
            Output(snapshot.fontIds.toList(), snapshot.diagnostics, listOf('A', 'B', 'D', 'I').map { engine.glyph(defaultFont, it.code) })
        }

    override fun close(): Unit = Unit

    /**
     * Values returned to JMH; contains no source, stream, engine or mutable JSON tree.
     *
     * @property fonts loaded font families in loader order.
     * @property diagnostics ordered complete loader diagnostics.
     * @property glyphs detached default-font probes in the declared A, B, D, I order.
     */
    public data class Output(
        public val fonts: List<ResourceId>,
        public val diagnostics: List<MinecraftFontDiagnostic>,
        public val glyphs: List<MinecraftFontGlyph>,
    )

    /**
     * Prepares common inputs once, independently of collection and runtime qualification.
     */
    public companion object {
        private val defaultFont = ResourceId("minecraft", "default")

        /**
         * Exact default-font path selected by the read operation and snapshot loader.
         */
        public val defaultPath: String = "assets/${defaultFont.namespace}/font/${defaultFont.path}.json"

        /**
         * Unmodified source and snapshot ceilings; no matrix size requires inflation.
         */
        public val limits: MinecraftFontLoadLimits = MinecraftFontLoadLimits()

        /**
         * Typed compatibility shared with the unchanged portable font fixtures.
         */
        public val compatibility: MinecraftFontCompatibility = FontPerformanceAssets.compatibility(FontWorkload.FreeTypeCached)

        /**
         * Identical provider options for the two qualifications and every collection.
         */
        public val options: MinecraftFontOptions = MinecraftFontOptions()
        private val indexedBytes = document("""{"A":7,"I":2}""")

        /**
         * Writes all nine deterministic cases and one complete external-input manifest to a new directory.
         * Invoke this compiled helper before collection; file creation never occurs in a timed interval.
         */
        @JvmStatic
        public fun main(args: Array<String>) {
            require(args.size == 1)
            println(prepare(Path.of(args.single())))
        }

        /**
         * Freezes exact bytes, index token counts, limits and SHA-256 input identities before either variant runs.
         * The supplied root must not exist; teardown of this shared input owner is the caller's responsibility.
         */
        @Suppress("LongMethod") // All frozen files and their input inventory are created by this one untimed owner.
        public fun prepare(root: Path): Path {
            require(root.isAbsolute && Files.exists(root).not())
            Files.createDirectories(root)
            val inputs = Properties()
            val counts = mutableListOf("input,records,indexBytes,jsonTokens,jsonDepth,maxDocumentBytes,maxInputBytes,maxSourceEntries")
            for (input in IndexedFontInput.entries) {
                val folder = Files.createDirectory(root.resolve(input.name))
                val objects = Files.createDirectory(folder.resolve("objects"))
                val hash = digest("SHA-1", indexedBytes)
                val objectPath = Files.createDirectories(objects.resolve(hash.take(2))).resolve(hash)
                Files.write(objectPath, indexedBytes)
                val records =
                    (0 until input.records).joinToString(",") { record ->
                        val path = if (record == 0) "minecraft/font/default.json" else "test/unused/$record.dat"
                        val selected = if (record == 0 || input.shared) hash else digest("SHA-1", path.toByteArray(Charsets.US_ASCII))
                        "\"$path\":{\"hash\":\"$selected\"}"
                    }
                val index = Files.writeString(folder.resolve("index.json"), "{\"objects\":{$records}}", Charsets.UTF_8)
                check(Files.size(index) <= limits.maxDocumentBytes && input.records <= limits.maxSourceEntries)
                val archive = folder.resolve("client.jar")
                ZipOutputStream(Files.newOutputStream(archive)).use { zip ->
                    for ((path, bytes) in linkedMapOf(defaultPath to document("""{"A":11,"B":3}"""), "assets/test/font/archive.json" to document("""{"C":4}"""))) {
                        zip.putNextEntry(ZipEntry(path).apply { time = 0 })
                        zip.write(bytes)
                        zip.closeEntry()
                    }
                }
                val directory = Files.createDirectory(folder.resolve("pack"))
                val custom = Files.createDirectories(directory.resolve("assets/minecraft/font")).resolve("default.json")
                Files.write(custom, document("""{"A":13,"D":5}"""))
                val extra = Files.createDirectories(directory.resolve("assets/test/font")).resolve("directory.json")
                Files.write(extra, document("""{"E":6}"""))
                for ((label, file) in linkedMapOf("index" to index, "object" to objectPath, "archive" to archive, "custom" to custom, "extra" to extra)) {
                    inputs.setProperty("indexed-${input.name}-$label", file.toString())
                }
                counts.add("${input.name},${input.records},${Files.size(index)},${5L * input.records + 5},${if (input.records == 0) 2 else 3},${limits.maxDocumentBytes},${limits.maxInputBytes},${limits.maxSourceEntries}")
            }
            val countFile = Files.write(root.resolve("counts.csv"), counts, Charsets.UTF_8)
            inputs.setProperty("indexed-counts", countFile.toString())
            val hashFile =
                Files.write(
                    root.resolve("sha256.csv"),
                    listOf("label,sha256") + inputs.stringPropertyNames().sorted().map { label -> "$label,${digest("SHA-256", Files.readAllBytes(Path.of(inputs.getProperty(label))))}" },
                    Charsets.UTF_8,
                )
            inputs.setProperty("indexed-sha256", hashFile.toString())
            val manifest = root.resolve("fixture-inputs.properties")
            Files.newBufferedWriter(manifest, Charsets.UTF_8).use { writer -> inputs.store(writer, "Common indexed-font inputs; freeze before both variants") }
            return manifest
        }

        /**
         * Resolves explicit common fixture files using the testkit's bounded manifest reader.
         * Sampling refuses missing preparation instead of silently creating different files per variant.
         */
        public fun open(input: IndexedFontInput): IndexedFontAssetFiles {
            val manifest = Path.of(checkNotNull(System.getProperty("strata.performance.fixtureInputs")))
            return open(input, JvmPerformanceInputs.read(manifest))
        }

        /**
         * Resolves one prepared descriptor for untimed controls without another input-generation path.
         */
        internal fun open(
            input: IndexedFontInput,
            files: Map<String, Path>,
        ): IndexedFontAssetFiles {
            val index = files.getValue("indexed-${input.name}-index")
            val objectPath = files.getValue("indexed-${input.name}-object")
            val archive = files.getValue("indexed-${input.name}-archive")
            val custom = files.getValue("indexed-${input.name}-custom")
            return IndexedFontAssetFiles(input, index, objectPath.parent.parent, archive, custom.parent.parent.parent.parent)
        }

        private fun document(advances: String): ByteArray = """{"providers":[{"type":"space","advances":$advances}]}""".toByteArray(Charsets.UTF_8)

        private fun digest(
            algorithm: String,
            bytes: ByteArray,
        ): String = HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes))
    }
}
