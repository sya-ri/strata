package dev.s7a.strata.integration.docs

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontBackendFactory
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Creates only original synthetic assets and a self-consistent declared manifest for hermetic loader and renderer tests.
 * No game asset or screenshot is copied, downloaded, or used to construct these fixtures.
 * The caller owns the temporary directory and may mutate the exposed inputs to exercise validation failures.
 */
internal class ShowcaseMinecraftAssetFixture(
    directory: Path,
) {
    /**
     * Synthetic client archive containing a 26.2 version declaration and original GUI/font resources.
     */
    val clientJar: Path = directory.resolve("client.jar")

    /**
     * Synthetic content-addressed asset index, deliberately named independently of its declared identifier.
     */
    val assetIndex: Path = directory.resolve("declared-assets.json")

    /**
     * Caller-owned object directory containing the original patterned font sheets.
     */
    val assetObjects: Path = directory.resolve("objects")

    /**
     * Manifest whose client and asset-index hashes describe the synthetic fixture files.
     */
    val versionManifest: Path = directory.resolve("version-manifest.json")

    /**
     * Separate fixture pack containing only an original Mod image with the documented source dimensions.
     */
    val testResources: Path = directory.resolve("fixtures")

    private val clientFiles = linkedMapOf<String, ByteArray>()
    private val objects = JsonObject()

    init {
        Files.createDirectories(directory)
        Files.createDirectories(assetObjects)
        Files.createDirectories(testResources)
        clientFiles["version.json"] = """{"id":"26.2","pack_version":{"resource_major":88,"resource_minor":0}}""".toByteArray()
        ShowcaseGuiAsset.entries.forEach { asset ->
            val path = "assets/" + asset.id.namespace + "/" + asset.id.path
            val color = if (asset == ShowcaseGuiAsset.MenuBackground) 0x40243444 else 0xFF243444.toInt()
            val bytes = ShowcaseFixturePng.create(asset.size) { _, _ -> color }
            if (asset == ShowcaseGuiAsset.CoalGenerator) {
                val target = testResources.resolve(path)
                Files.createDirectories(target.parent)
                Files.write(target, bytes)
            } else {
                clientFiles[path] = bytes
                metadata(asset.metadata, asset.size)?.let { document -> clientFiles[path + ".mcmeta"] = document.toString().toByteArray() }
            }
        }
        addFontObjects()
        Files.writeString(assetIndex, JsonObject().apply { add("objects", objects) }.toString())
        writeClient()
    }

    /**
     * Loads the synthetic original PNGs through the real CPU backend for renderer tests without Minecraft or OpenGL.
     */
    fun assets(): ShowcaseMinecraftAssets = ShowcaseMinecraftAssets(clientJar, assetIndex, assetObjects, versionManifest, testResources)

    /**
     * Loads the same files through an injected bounded decoder for native-independent ownership and failure tests.
     */
    fun assets(backendFactory: MinecraftFontBackendFactory): ShowcaseMinecraftAssets = ShowcaseMinecraftAssets(ShowcaseMinecraftInputs(clientJar, assetIndex, assetObjects, versionManifest, testResources), backendFactory)

    /**
     * Replaces one archive entry and refreshes the declared client hash, allowing schema failures to be tested independently.
     */
    fun replaceClient(
        path: String,
        bytes: ByteArray,
    ) {
        clientFiles[path] = bytes
        writeClient()
    }

    /**
     * Removes one archive entry while retaining a matching manifest for missing-resource tests.
     */
    fun removeClient(path: String) {
        clientFiles.remove(path)
        writeClient()
    }

    /**
     * Returns the physical object file of a declared test asset for content-integrity failure tests.
     */
    fun objectFile(path: String): Path {
        val hash = objects.getAsJsonObject(path).get("hash").asString
        return assetObjects.resolve(hash.take(2)).resolve(hash)
    }

    private fun writeClient() {
        ZipOutputStream(Files.newOutputStream(clientJar)).use { zip ->
            clientFiles.toSortedMap().forEach { (path, bytes) ->
                zip.putNextEntry(ZipEntry(path).apply { time = 0L })
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        val downloads = JsonObject().apply { add("client", declaration(clientJar)) }
        val index = declaration(assetIndex).apply { addProperty("id", "synthetic-index") }
        val manifest =
            JsonObject().apply {
                addProperty("id", "26.2")
                add("downloads", downloads)
                add("assetIndex", index)
            }
        Files.writeString(versionManifest, manifest.toString())
    }

    private fun declaration(path: Path): JsonObject =
        JsonObject().apply {
            val bytes = Files.readAllBytes(path)
            addProperty("sha1", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes)))
            addProperty("size", bytes.size)
        }

    private fun addFontObjects() {
        val ascii = (0x20..0x7F).map(Int::toChar).chunked(16).map { row -> row.joinToString("") }
        val japanese = "日本語と한글🙂"
        addObject(
            "minecraft/textures/font/showcase-ascii.png",
            ShowcaseFixturePng.create(IntSize(128, 48)) { x, y ->
                if (x % 8 < 5 && y % 8 < 7) -1 else 0x00FFFFFF
            },
        )
        addObject(
            "minecraft/textures/font/showcase-japanese.png",
            ShowcaseFixturePng.create(IntSize(japanese.codePointCount(0, japanese.length) * 16, 16)) { x, y ->
                if (x % 16 in 1..14 && y in 1..14 && (x + y) % 3 != 0) -1 else 0x00FFFFFF
            },
        )
        val providers =
            JsonArray().apply {
                add(
                    JsonObject().apply {
                        addProperty("type", "space")
                        add("advances", JsonObject().apply { addProperty(" ", 4) })
                    },
                )
                add(bitmap("minecraft:font/showcase-ascii.png", ascii, 8, 7))
                add(bitmap("minecraft:font/showcase-japanese.png", listOf(japanese), 8, 7))
            }
        clientFiles["assets/minecraft/font/default.json"] = JsonObject().apply { add("providers", providers) }.toString().toByteArray()
    }

    private fun addObject(
        path: String,
        bytes: ByteArray,
    ) {
        val hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes))
        val target = assetObjects.resolve(hash.take(2)).resolve(hash)
        Files.createDirectories(target.parent)
        Files.write(target, bytes)
        objects.add(
            path,
            JsonObject().apply {
                addProperty("hash", hash)
                addProperty("size", bytes.size)
            },
        )
    }

    private fun bitmap(
        path: String,
        chars: List<String>,
        height: Int,
        ascent: Int,
    ): JsonObject =
        JsonObject().apply {
            addProperty("type", "bitmap")
            addProperty("file", path)
            addProperty("height", height)
            addProperty("ascent", ascent)
            add("chars", JsonArray().apply { chars.forEach(::add) })
        }

    private fun metadata(
        value: ShowcaseGuiMetadata,
        size: IntSize,
    ): JsonObject? =
        when (value) {
            ShowcaseGuiMetadata.None -> {
                null
            }

            is ShowcaseGuiMetadata.NineSlice -> {
                val border =
                    JsonObject().apply {
                        addProperty("left", value.border.left)
                        addProperty("top", value.border.top)
                        addProperty("right", value.border.right)
                        addProperty("bottom", value.border.bottom)
                    }
                val scaling =
                    JsonObject().apply {
                        addProperty("type", "nine_slice")
                        addProperty("width", size.width)
                        addProperty("height", size.height)
                        add("border", border)
                        if (value.stretchInner) addProperty("stretch_inner", true)
                    }
                JsonObject().apply { add("gui", JsonObject().apply { add("scaling", scaling) }) }
            }

            is ShowcaseGuiMetadata.Animation -> {
                JsonObject().apply {
                    add(
                        "animation",
                        JsonObject().apply {
                            addProperty("width", value.frame.width)
                            addProperty("height", value.frame.height)
                            addProperty("frametime", value.ticks)
                        },
                    )
                }
            }
        }
}
