package dev.s7a.strata.runtime.minecraft.fabric

import com.google.gson.JsonParseException
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontBackend
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontBackendFactory
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontCompatibility
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontEngine
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontGlyph
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontOptions
import dev.s7a.strata.runtime.minecraft.font.MinecraftTrueTypeFace
import dev.s7a.strata.runtime.minecraft.font.MinecraftTrueTypeRasterizer
import dev.s7a.strata.runtime.minecraft.font.MinecraftTrueTypeSettings
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.server.packs.PackLocationInfo
import net.minecraft.server.packs.PackType
import net.minecraft.server.packs.PathPackResources
import net.minecraft.server.packs.repository.PackSource
import net.minecraft.server.packs.resources.MultiPackResourceManager
import net.minecraft.server.packs.resources.Resource
import net.minecraft.server.packs.resources.ResourceManager
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Optional
import java.util.function.Predicate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Verifies strict selection and decoding of the supported bitmap-font provider graph.
 */
internal class FabricMinecraftFontContractTest {
    @Test
    fun acceptsAccentedHeightTwelveBeforeTheAsciiProvider() {
        val reads = ArrayList<Identifier>()
        val documents = validDocuments()
        assertDoesNotThrow {
            validateMinecraftRegularFontContract(asciiImage()) { identifier ->
                reads.add(identifier)
                documents.getValue(identifier)
            }
        }
        assertEquals(listOf(defaultIdentifier, spaceIdentifier, includeDefaultIdentifier), reads)
    }

    @Test
    fun incompleteAsciiCoverageDoesNotSkipLaterFallbackProviders() {
        val readFailure = IOException("unifont must be visited")
        val documents =
            validDocuments() +
                (includeDefaultIdentifier to validDocuments().getValue(includeDefaultIdentifier).replace("\\u0021", "\\u0000"))

        val escaped =
            assertThrows(IOException::class.java) {
                validateMinecraftRegularFontContract(asciiImage()) { identifier ->
                    if (identifier == unifontIdentifier) throw readFailure
                    documents.getValue(identifier)
                }
            }
        assertSame(readFailure, escaped)
    }

    @Test
    fun validatesAsciiMetricsRowsAndCellMappingExactly() {
        val valid = validDocuments()
        val include = valid.getValue(includeDefaultIdentifier)
        assertDoesNotThrow {
            validateMinecraftRegularFontContract(asciiImage(), reader(valid))
        }
        listOf(
            include.replace("\"file\":\"minecraft:font/ascii.png\",", "\"file\":\"minecraft:font/ascii.png\",\"height\":7,"),
            include.replace("\"ascent\":7,", ""),
            include.replace("\"chars\":[$asciiRows]", "\"chars\":[${asciiRows.substringBeforeLast(',')}]"),
            include.replace("\\u000f", ""),
            include.replace("\\u0021", "\\u0022"),
        ).forEach { invalidInclude ->
            assertInvalid(valid + (includeDefaultIdentifier to invalidInclude))
        }
    }

    @Test
    fun validatesSpaceAndProviderIdentityBeforeCompletion() {
        val valid = validDocuments()
        val space = valid.getValue(spaceIdentifier)
        val include = valid.getValue(includeDefaultIdentifier)
        val incompleteRows = asciiRows.replace("\\u0021", "\\u0000")
        val duplicateAscii =
            """{"providers":[{"type":"bitmap","file":"minecraft:font/accented.png","height":12,"ascent":10,"chars":["\u00c0"]},{"type":"bitmap","file":"minecraft:font/ascii.png","ascent":7,"chars":[$incompleteRows]},{"type":"bitmap","file":"minecraft:font/ascii.png","ascent":7,"chars":[$asciiRows]}]}"""
        listOf(
            valid + (spaceIdentifier to space.replace("\" \":4", "\" \":5")),
            valid + (spaceIdentifier to space.replace("\" \":4,", "")),
            valid +
                (
                    spaceIdentifier to
                        """{"providers":[{"type":"space","advances":{" ":4}},{"type":"space","advances":{" ":4}}]}"""
                ),
            valid + (includeDefaultIdentifier to duplicateAscii),
            valid + (includeDefaultIdentifier to include.replace("\"chars\":[$asciiRows]", "\"chars\":[$incompleteRows]")),
        ).forEach(::assertInvalid)
    }

    @Test
    fun rejectsMalformedFiltersAndReferenceCycles() {
        val valid = validDocuments()
        val root = valid.getValue(defaultIdentifier)
        listOf(
            root.replace("{\"uniform\":false}", "false"),
            root.replace("{\"uniform\":false}", "{\"jp\":false}"),
            root.replace("{\"uniform\":false}", "{\"uniform\":false,\"jp\":false}"),
            root.replace("{\"uniform\":false}", "{\"uniform\":\"false\"}"),
        ).forEach { invalidRoot -> assertInvalid(valid + (defaultIdentifier to invalidRoot)) }
        assertInvalid(
            valid +
                (
                    defaultIdentifier to
                        """{"providers":[{"type":"reference","id":"minecraft:default"}]}"""
                ),
        )
    }

    @Test
    fun rejectsMalformedOrUnrepresentableProviderGraphs() {
        val valid = validDocuments()
        val include = includeDefaultIdentifier
        val variants =
            listOf(
                valid + (defaultIdentifier to "{"),
                valid + (defaultIdentifier to valid.getValue(defaultIdentifier).replace("\"providers\"", "\"providers\":[] , \"ignored\"")),
                valid + (defaultIdentifier to valid.getValue(defaultIdentifier).replace("\"type\":\"reference\"", "\"type\":1")),
                valid + (defaultIdentifier to valid.getValue(defaultIdentifier).replace("\"uniform\":false", "\"uniform\":true")),
                valid + (include to valid.getValue(include).replace("\"height\":12", "\"height\":8.5")),
                valid + (include to valid.getValue(include).replace("\"chars\":[\"\\u00c0\"]", "\"chars\":[\"A\"]")),
                valid + (include to valid.getValue(include).replace("\"ascent\":7", "\"ascent\":\"7\"")),
                valid + (include to valid.getValue(include).replace("\"type\":\"bitmap\"", "\"type\":\"ttf\"")),
                valid + (defaultIdentifier to valid.getValue(defaultIdentifier).replace("minecraft:include/space", "Bad:ID")),
                valid + (include to valid.getValue(include).replace("minecraft:font/ascii.png", "Bad:ID")),
                valid + (include to valid.getValue(include).replace("\"chars\":[$asciiRows]", "\"chars\":[1]")),
            )

        variants.forEach { documents ->
            assertInvalid(documents)
        }
    }

    @Test
    fun wrapsMalformedJsonAndPreservesDocumentReadFailures() {
        val malformed = validDocuments() + (defaultIdentifier to "{")
        val malformedFailure =
            assertThrows(IllegalArgumentException::class.java) {
                validateMinecraftRegularFontContract(asciiImage(), reader(malformed))
            }
        assertTrue(malformedFailure.cause is JsonParseException)

        val readFailure = IOException("read")
        val escaped =
            assertThrows(IOException::class.java) {
                validateMinecraftRegularFontContract(asciiImage()) { throw readFailure }
            }
        assertSame(readFailure, escaped)
    }

    @Test
    fun capturesFontStacksAndExactProviderAssetsWithoutRootEnumeration(
        @TempDir directory: Path,
    ) {
        val lower = resourceFontPack(directory.resolve("lower"), resourceFontFiles())
        val higher =
            resourceFontPack(
                directory.resolve("higher"),
                mapOf(
                    "assets/minecraft/font/default.json" to """{"providers":[{"type":"space","advances":{"Z":9}}]}""".toByteArray(),
                    "assets/test/font/shared.json" to """{"providers":[{"type":"space","advances":{"Q":4}}]}""".toByteArray(),
                    "assets/test/textures/custom/atlas.png" to byteArrayOf(2),
                    "assets/test/font/nested/custom.ttf" to byteArrayOf(4),
                ),
            )
        val reads = ArrayList<Identifier>()
        var readingAllowed = true
        val snapshot =
            MultiPackResourceManager(PackType.CLIENT_RESOURCES, listOf(lower, higher)).use { manager ->
                val observed = observedFontResources(manager, reads) { assertTrue(readingAllowed) }
                extractFabricMinecraftFontSnapshot(observed, MinecraftFontCompatibility(MinecraftTrueTypeRasterizer.FreeType, 84), MinecraftFontOptions())
            }
        readingAllowed = false
        assertEquals(setOf(ResourceId("minecraft", "default"), ResourceId("test", "shared")), snapshot.fontIds)
        assertEquals(
            setOf(
                Identifier.parse("test:textures/custom/atlas.png"),
                Identifier.parse("test:outside/glyphs.zip"),
                Identifier.parse("test:font/nested/custom.ttf"),
            ),
            reads.toSet(),
        )
        MinecraftFontEngine(snapshot, MinecraftFontBackendFactory { ResourceFontBackend() }).use { engine ->
            val font = ResourceId("minecraft", "default")
            assertEquals(9.0f, engine.glyph(font, 'A'.code).advance)
            assertEquals(2.0f, engine.glyph(font, 'L'.code).advance)
            assertEquals(9.0f, engine.glyph(font, 'Z'.code).advance)
            assertEquals(4.0f, engine.glyph(font, 'Q'.code).advance)
            assertEquals(5.0f, engine.glyph(font, '한'.code).advance)
            assertEquals(7.5f, engine.glyph(font, 'T'.code).advance)
            assertTrue(engine.diagnostics.isEmpty())
        }
    }

    private fun observedFontResources(
        manager: ResourceManager,
        reads: MutableList<Identifier>,
        checkReading: () -> Unit,
    ): ResourceManager =
        object : ResourceManager by manager {
            override fun listResourceStacks(
                path: String,
                predicate: Predicate<Identifier>,
            ): Map<Identifier, List<Resource>> {
                checkReading()
                assertEquals("font", path)
                return manager.listResourceStacks(path, predicate)
            }

            override fun getResource(location: Identifier): Optional<Resource> {
                checkReading()
                reads.add(location)
                return manager.getResource(location)
            }
        }

    private fun resourceFontPack(
        directory: Path,
        files: Map<String, ByteArray>,
    ): PathPackResources {
        for ((path, bytes) in files) {
            val target = directory.resolve(path)
            Files.createDirectories(target.parent)
            Files.write(target, bytes)
        }
        val location = PackLocationInfo(directory.fileName.toString(), Component.literal("Font fixture"), PackSource.DEFAULT, Optional.empty())
        return PathPackResources(location, directory)
    }

    private fun resourceFontFiles(): Map<String, ByteArray> =
        mapOf(
            "assets/minecraft/font/default.json" to
                """{"providers":[{"type":"reference","id":"test:shared"},{"type":"space","advances":{"L":2}}]}""".toByteArray(),
            "assets/test/font/shared.json" to
                """
                {"providers":[
                    {"type":"bitmap","file":"test:custom/atlas.png","ascent":7,"chars":["A"]},
                    {"type":"unihex","hex_file":"test:outside/glyphs.zip"},
                    {"type":"ttf","file":"test:nested/custom.ttf"}
                ]}
                """.trimIndent().toByteArray(),
            "assets/test/textures/custom/atlas.png" to byteArrayOf(1),
            "assets/test/font/nested/custom.ttf" to byteArrayOf(3),
            "assets/test/outside/glyphs.zip" to unihexArchive(),
        )

    private fun unihexArchive(): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("custom.hex"))
            zip.write("D55C:${"FF".repeat(16)}\n".toByteArray())
            zip.closeEntry()
        }
        return bytes.toByteArray()
    }

    private class ResourceFontBackend : MinecraftFontBackend {
        override fun decodePng(bytes: ByteArray): DrawImage {
            assertArrayEquals(byteArrayOf(2), bytes)
            return createDrawImage(IntSize(1, 1), intArrayOf(0xFFFFFFFF.toInt()))
        }

        override fun openTrueType(
            bytes: ByteArray,
            settings: MinecraftTrueTypeSettings,
        ): MinecraftTrueTypeFace {
            assertArrayEquals(byteArrayOf(4), bytes)
            val glyphs = mapOf('T'.code to MinecraftFontGlyph(7.5f, 0.0f, 0.0f, 0.0f, 0.0f, null))
            return object : MinecraftTrueTypeFace {
                override fun glyph(codePoint: Int): MinecraftFontGlyph? = glyphs[codePoint]

                override fun close() = Unit
            }
        }

        override fun close() = Unit
    }

    private fun validDocuments(): Map<Identifier, String> =
        mapOf(
            defaultIdentifier to
                """{"providers":[{"type":"reference","id":"minecraft:include/space"},{"type":"reference","id":"minecraft:include/default","filter":{"uniform":false}},{"type":"reference","id":"minecraft:include/unifont"}]}""",
            spaceIdentifier to """{"providers":[{"type":"space","advances":{" ":4,"\u200c":0}}]}""",
            includeDefaultIdentifier to
                """{"providers":[{"type":"bitmap","file":"minecraft:font/accented.png","height":12,"ascent":10,"chars":["\u00c0"]},{"type":"bitmap","file":"minecraft:font/ascii.png","ascent":7,"chars":[$asciiRows]}]}""",
            unifontIdentifier to
                """{"providers":[{"type":"unihex","hex_file":"minecraft:font/unifont_jp_patch.hex","filter":{"jp":true}},{"type":"unihex","hex_file":"minecraft:font/unifont_all_no_pua.hex"}]}""",
        )

    private fun reader(documents: Map<Identifier, String>): (Identifier) -> String = { identifier -> documents.getValue(identifier) }

    private fun assertInvalid(documents: Map<Identifier, String>) {
        assertThrows(IllegalArgumentException::class.java) {
            validateMinecraftRegularFontContract(asciiImage(), reader(documents))
        }
    }

    private fun asciiImage(): DrawImage = createDrawImage(IntSize(128, 128), IntArray(128 * 128) { 0x00FFFFFF })

    private companion object {
        private val defaultIdentifier: Identifier = Identifier.parse("minecraft:font/default.json")
        private val spaceIdentifier: Identifier = Identifier.parse("minecraft:font/include/space.json")
        private val includeDefaultIdentifier: Identifier = Identifier.parse("minecraft:font/include/default.json")
        private val unifontIdentifier: Identifier = Identifier.parse("minecraft:font/include/unifont.json")
        private val asciiRows: String =
            (0 until 16).joinToString(",") { row ->
                val encoded = (0 until 16).joinToString("") { column -> "\\u%04x".format(row * 16 + column) }
                "\"$encoded\""
            }
    }
}
