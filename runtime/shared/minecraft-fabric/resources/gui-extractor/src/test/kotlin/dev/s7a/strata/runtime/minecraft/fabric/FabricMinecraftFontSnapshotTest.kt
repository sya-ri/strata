package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontBackend
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontCompatibility
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontEngine
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontGlyph
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontLoadLimitException
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontLoadLimits
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
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Optional
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Verifies detached native font snapshots, pack precedence, and resource stream limits.
 */
internal class FabricMinecraftFontSnapshotTest {
    @Test
    fun nativeConvenienceReadsKeepObservationAndStreamOwnership(
        @TempDir directory: Path,
    ) {
        val text = "observed resource"
        val contents = text.toByteArray()
        val location = Identifier.parse("test:font/default.json")
        val pack = resourceFontPack(directory.resolve("convenience"), mapOf("assets/test/font/default.json" to contents))
        MultiPackResourceManager(PackType.CLIENT_RESOURCES, listOf(pack)).use { manager ->
            val reads = ArrayList<Identifier>()
            val streams = ArrayList<TrackedFontStream>()
            var checks = 0
            val observed = observedFontResources(manager, reads, transform = { resource -> trackedFontResource(resource, streams) }) { checks += 1 }

            observed.getResourceOrThrow(location).open().use { stream -> assertArrayEquals(contents, stream.readAllBytes()) }
            observed.open(location).use { stream -> assertArrayEquals(contents, stream.readAllBytes()) }
            observed.openAsReader(location).use { reader -> assertEquals(text, reader.readText()) }

            assertEquals(List(3) { location }, reads)
            assertEquals(3, checks)
            assertEquals(3, streams.size)
            assertTrue(streams.all(TrackedFontStream::closed))
        }
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
        MinecraftFontEngine(snapshot, { ResourceFontBackend() }).use { engine ->
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

    @Test
    fun suppliedByteLimitsCloseNativeStreamsAtAndAboveTheirInclusiveBoundary(
        @TempDir directory: Path,
    ) {
        val contents = """{"providers":[{"type":"space","advances":{"日":3}}]}""".toByteArray()
        val pack = resourceFontPack(directory.resolve("limits"), mapOf("assets/minecraft/font/default.json" to contents))
        MultiPackResourceManager(PackType.CLIENT_RESOURCES, listOf(pack)).use { manager ->
            val exact = MinecraftFontLoadLimits(maxDocumentBytes = contents.size, maxInputBytes = contents.size.toLong())
            val variants = listOf(exact, exact.copy(maxDocumentBytes = contents.size + 1, maxInputBytes = contents.size.toLong() + 1), exact.copy(maxDocumentBytes = contents.size - 1), exact.copy(maxInputBytes = contents.size.toLong() - 1))
            for (limits in variants) {
                val streams = ArrayList<TrackedFontStream>()
                val observed = observedFontResources(manager, ArrayList(), transform = { resource -> trackedFontResource(resource, streams) }) {}
                val snapshot = extractFabricMinecraftFontSnapshot(observed, MinecraftFontCompatibility(MinecraftTrueTypeRasterizer.FreeType, 84), MinecraftFontOptions(), limits)
                val accepted = contents.size <= limits.maxDocumentBytes && contents.size <= limits.maxInputBytes
                assertEquals(accepted, snapshot.diagnostics.isEmpty())
                val stream = streams.single()
                assertTrue(stream.closed)
                assertEquals(contents.size, stream.bytesRead)
                assertTrue(stream.bytesRead <= minOf(limits.maxDocumentBytes.toLong(), limits.maxInputBytes) + 1)
            }
        }
    }

    @Test
    fun pathLimitsRejectNativeEnumerationAndExactLookupsBeforeOpeningOversizedInputs(
        @TempDir directory: Path,
    ) {
        val documentPath = "assets/test/font/default.json"
        val providerPath = "a".repeat(48) + ".ttf"
        val contents = """{"providers":[{"type":"ttf","file":"test:$providerPath"}]}""".toByteArray()
        val pack = resourceFontPack(directory.resolve("paths"), mapOf(documentPath to contents))
        MultiPackResourceManager(PackType.CLIENT_RESOURCES, listOf(pack)).use { manager ->
            val streams = ArrayList<TrackedFontStream>()
            val reads = ArrayList<Identifier>()
            val observed = observedFontResources(manager, reads, transform = { resource -> trackedFontResource(resource, streams) }) {}
            val compatibility = MinecraftFontCompatibility(MinecraftTrueTypeRasterizer.FreeType, 84)
            assertThrows(MinecraftFontLoadLimitException::class.java) {
                extractFabricMinecraftFontSnapshot(observed, compatibility, MinecraftFontOptions(), MinecraftFontLoadLimits(maxPathLength = documentPath.length - 1))
            }
            assertTrue(streams.isEmpty())
            val snapshot = extractFabricMinecraftFontSnapshot(observed, compatibility, MinecraftFontOptions(), MinecraftFontLoadLimits(maxPathLength = documentPath.length))
            assertEquals(1, snapshot.diagnostics.size)
            assertTrue(reads.isEmpty())
            assertTrue(streams.single().closed)
        }
    }

    @Test
    fun nativeStackCopiesCheckLayerAndAggregateEntryLimitsBeforeOpeningStreams(
        @TempDir directory: Path,
    ) {
        val contents = """{"providers":[{"type":"space","advances":{"A":3}}]}""".toByteArray()
        val files = mapOf("assets/test/font/a.json" to contents, "assets/test/font/b.json" to contents)
        val lower = resourceFontPack(directory.resolve("entry-lower"), files)
        val higher = resourceFontPack(directory.resolve("entry-higher"), files)
        MultiPackResourceManager(PackType.CLIENT_RESOURCES, listOf(lower, higher)).use { manager ->
            val streams = ArrayList<TrackedFontStream>()
            val observed = observedFontResources(manager, ArrayList(), transform = { resource -> trackedFontResource(resource, streams) }) {}
            val compatibility = MinecraftFontCompatibility(MinecraftTrueTypeRasterizer.FreeType, 84)
            val exact = MinecraftFontLoadLimits(maxSources = 3, maxSourceEntries = 2, maxEntries = 4)
            for (limits in listOf(exact.copy(maxSources = 2), exact.copy(maxSourceEntries = 1), exact.copy(maxEntries = 3))) {
                assertThrows(MinecraftFontLoadLimitException::class.java) { extractFabricMinecraftFontSnapshot(observed, compatibility, MinecraftFontOptions(), limits) }
                assertTrue(streams.isEmpty())
            }
            for (limits in listOf(exact, exact.copy(maxSources = 4, maxSourceEntries = 3, maxEntries = 5))) {
                val snapshot = extractFabricMinecraftFontSnapshot(observed, compatibility, MinecraftFontOptions(), limits)
                assertEquals(setOf(ResourceId("test", "a"), ResourceId("test", "b")), snapshot.fontIds)
                assertTrue(snapshot.diagnostics.isEmpty())
                assertEquals(4, streams.size)
                assertTrue(streams.all(TrackedFontStream::closed))
                streams.clear()
            }
        }
    }

    private fun trackedFontResource(
        resource: Resource,
        streams: MutableList<TrackedFontStream>,
    ): Resource =
        Resource(resource.source()) {
            TrackedFontStream(resource.open()).also(streams::add)
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

    private class TrackedFontStream(
        input: InputStream,
    ) : FilterInputStream(input) {
        var bytesRead = 0
            private set
        var closed = false
            private set

        override fun read(): Int = super.read().also { value -> if (0 <= value) bytesRead++ }

        override fun read(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ): Int = super.read(bytes, offset, length).also { count -> if (0 < count) bytesRead += count }

        override fun close() {
            closed = true
            super.close()
        }
    }
}
