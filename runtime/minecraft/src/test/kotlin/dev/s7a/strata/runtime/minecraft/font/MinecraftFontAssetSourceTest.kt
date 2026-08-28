package dev.s7a.strata.runtime.minecraft.font

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.createDrawImage
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Verifies direct offline sources and copied snapshot ownership without relying on installed Minecraft assets.
 */
internal class MinecraftFontAssetSourceTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun memorySourcesCopyInputAndOutputBytesAndRejectUnsafePaths() {
        val original = byteArrayOf(1, 2, 3)
        val files = linkedMapOf("assets/test/font/example.ttf" to original)
        val source = MinecraftMemoryFontAssetSource("memory", files)
        original[0] = 9
        files.clear()
        val first = requireNotNull(source.read("assets/test/font/example.ttf"))
        assertArrayEquals(byteArrayOf(1, 2, 3), first)
        first[0] = 7
        assertArrayEquals(byteArrayOf(1, 2, 3), source.read("assets/test/font/example.ttf"))
        assertNull(source.read("assets/test/font/missing.ttf"))
        for (path in listOf("../escape", "/absolute", "assets\\test", "assets/./test", "assets//test", "C:/font")) {
            assertThrows(IllegalArgumentException::class.java) { source.read(path) }
            assertThrows(IllegalArgumentException::class.java) { MinecraftMemoryFontAssetSource("invalid", mapOf(path to original)) }
        }
    }

    @Test
    fun directoriesArchivesAndIndexedObjectsExposeIdenticalIndependentBytes() {
        val definition = FontTestResources.font("default", """{"type":"space","advances":{"日":8}}""")
        val pack = Files.createDirectories(directory.resolve("pack"))
        val definitionFile = pack.resolve(definition.first)
        Files.createDirectories(definitionFile.parent)
        Files.write(definitionFile, definition.second)
        val archive = directory.resolve("pack.zip")
        Files.write(archive, FontTestResources.archive(definition))
        val hash = MessageDigest.getInstance("SHA-1").digest(definition.second).joinToString("") { byte -> "%02x".format(byte) }
        val objects = Files.createDirectories(directory.resolve("objects"))
        val objectDirectory = Files.createDirectories(objects.resolve(hash.substring(0, 2)))
        Files.write(objectDirectory.resolve(hash), definition.second)
        val index = directory.resolve("index.json")
        Files.writeString(index, """{"objects":{"minecraft/font/default.json":{"hash":"$hash","size":${definition.second.size}}}}""")
        val sources =
            listOf(
                MinecraftDirectoryFontAssetSource(pack),
                MinecraftArchiveFontAssetSource(archive),
                MinecraftIndexedFontAssetSource(index, objects),
            )
        for (source in sources) {
            assertEquals(setOf(definition.first), source.paths())
            val bytes = requireNotNull(source.read(definition.first))
            assertArrayEquals(definition.second, bytes)
            bytes.fill(0)
            assertArrayEquals(definition.second, source.read(definition.first))
            assertNull(source.read("assets/test/missing"))
            val snapshot = MinecraftFontSnapshot.load(listOf(source), FontTestResources.compatibility)
            MinecraftFontEngine(snapshot, MinecraftFontBackendFactory { FontTestBackend() }).use { engine ->
                assertEquals(8.0f, engine.glyph(FontTestResources.defaultFont, '日'.code).advance)
            }
        }
        Files.delete(archive)
        assertThrows(IllegalArgumentException::class.java) { sources.first().read("../outside") }
    }

    @Test
    fun invalidArchivePathsAndObjectHashesFailBeforeSnapshotConstruction() {
        val archive = directory.resolve("invalid.zip")
        Files.write(archive, FontTestResources.archive("../outside" to byteArrayOf(1)))
        assertThrows(IllegalArgumentException::class.java) { MinecraftArchiveFontAssetSource(archive).paths() }
        val index = directory.resolve("index.json")
        Files.writeString(index, """{"objects":{"minecraft/font/default.json":{"hash":"../../outside"}}}""")
        assertThrows(IllegalArgumentException::class.java) { MinecraftIndexedFontAssetSource(index, directory) }
    }

    @Test
    fun engineNeverCallsSourcesAfterLoadingAndBackendCannotMutateSnapshotBytes() {
        val definition =
            FontTestResources.font(
                "default",
                """{"type":"bitmap","file":"test:font/example.png","height":8,"ascent":7,"chars":["日"]}""",
            )
        val files = linkedMapOf(definition, "assets/test/textures/font/example.png" to byteArrayOf(11))
        var released = false
        var reads = 0
        val source =
            object : MinecraftFontAssetSource {
                override val name = "callback"

                override fun paths(): Set<String> = files.keys.toSet()

                override fun read(path: String): ByteArray? {
                    check(released.not())
                    reads++
                    return files[path]?.copyOf()
                }
            }
        val snapshot = MinecraftFontSnapshot.load(listOf(source), FontTestResources.compatibility)
        val loadingReads = reads
        released = true
        files.clear()
        repeat(2) {
            val backend =
                FontTestBackend(decode = { bytes ->
                    assertArrayEquals(byteArrayOf(11), bytes)
                    bytes[0] = 99
                    createDrawImage(IntSize(1, 1), intArrayOf(-1))
                })
            MinecraftFontEngine(snapshot, MinecraftFontBackendFactory { backend }).use { engine ->
                assertEquals(9.0f, engine.glyph(FontTestResources.defaultFont, '日'.code).advance)
            }
        }
        assertEquals(loadingReads, reads)
    }
}
