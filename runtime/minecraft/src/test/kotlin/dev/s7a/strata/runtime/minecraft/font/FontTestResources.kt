package dev.s7a.strata.runtime.minecraft.font

import dev.s7a.strata.resource.ResourceId
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Creates detached resource fixtures without loading game classes, native libraries, or desktop graphics.
 */
internal object FontTestResources {
    /**
     * Native modern capabilities used by synthetic provider fixtures.
     */
    val compatibility = MinecraftFontCompatibility(MinecraftTrueTypeRasterizer.FreeType, 84, fractionalUnihexAdvance = true, rejectMalformedOverlayMetadata = true)

    /**
     * Default font-family identifier used by the fixtures.
     */
    val defaultFont = ResourceId("minecraft", "default")

    /**
     * Encodes one font document at its canonical resource-pack path.
     */
    fun font(
        id: String,
        providers: String,
    ): Pair<String, ByteArray> {
        val identifier = FontJson.identifier(id)
        return "assets/${identifier.namespace}/font/${identifier.path}.json" to """{"providers":[$providers]}""".toByteArray()
    }

    /**
     * Creates a copied in-memory pack containing exactly the supplied paths.
     */
    fun source(
        vararg files: Pair<String, ByteArray>,
        name: String = "font-test",
    ): MinecraftMemoryFontAssetSource = MinecraftMemoryFontAssetSource(name, files.toMap())

    /**
     * Injects a deterministic read failure at one exact resource path while preserving the remaining copied source.
     *
     * @param source stable fixture to delegate unaffected reads to.
     * @param failedPath source-relative path whose read fails.
     * @param failure exact throwable raised for that path.
     * @return callback-lifetime fixture source; loading must not retain its source or throwable.
     */
    fun failingRead(
        source: MinecraftFontAssetSource,
        failedPath: String,
        failure: Throwable,
    ): MinecraftFontAssetSource {
        val failures = mapOf(failedPath to failure)
        return object : MinecraftFontAssetSource by source {
            override fun read(path: String): ByteArray? {
                failures[path]?.let { throw it }
                return source.read(path)
            }
        }
    }

    /**
     * Loads one immutable font state from a copied fixture source.
     */
    fun snapshot(
        vararg files: Pair<String, ByteArray>,
        options: MinecraftFontOptions = MinecraftFontOptions(),
        capabilities: MinecraftFontCompatibility = compatibility,
    ): MinecraftFontSnapshot = MinecraftFontSnapshot.load(listOf(source(*files)), capabilities, options)

    /**
     * Produces a complete ZIP archive containing the supplied copied entries.
     */
    fun archive(vararg files: Pair<String, ByteArray>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            for ((name, contents) in files) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(contents)
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }
}
