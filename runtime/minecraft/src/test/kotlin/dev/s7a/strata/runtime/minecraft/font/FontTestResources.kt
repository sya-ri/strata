package dev.s7a.strata.runtime.minecraft.font

import dev.s7a.strata.resource.ResourceId
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
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

    /**
     * Encodes caller-selected PNG dimensions and filtered image bytes for allocation-boundary fixtures.
     * Supplying inconsistent dimensions deliberately creates malformed image data without allocating those dimensions.
     * Only small compressed output and the supplied bytes are retained; no image decoder is used.
     */
    fun png(
        width: Int,
        height: Int,
        filtered: ByteArray,
    ): ByteArray {
        val compressed = ByteArrayOutputStream()
        DeflaterOutputStream(compressed).use { output -> output.write(filtered) }
        val header = ByteArrayOutputStream()
        DataOutputStream(header).use { output ->
            output.writeInt(width)
            output.writeInt(height)
            output.write(byteArrayOf(8, 6, 0, 0, 0))
        }
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.write(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
            pngChunk(output, "IHDR", header.toByteArray())
            pngChunk(output, "IDAT", compressed.toByteArray())
            pngChunk(output, "IEND", byteArrayOf())
        }
        return bytes.toByteArray()
    }

    private fun pngChunk(
        output: DataOutputStream,
        type: String,
        bytes: ByteArray,
    ) {
        val name = type.toByteArray(Charsets.US_ASCII)
        val crc = CRC32()
        crc.update(name)
        crc.update(bytes)
        output.writeInt(bytes.size)
        output.write(name)
        output.write(bytes)
        output.writeInt(crc.value.toInt())
    }
}
