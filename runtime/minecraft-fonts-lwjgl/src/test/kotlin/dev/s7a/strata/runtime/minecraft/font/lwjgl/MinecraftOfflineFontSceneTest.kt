package dev.s7a.strata.runtime.minecraft.font.lwjgl

import dev.s7a.strata.integration.minecraft.fabric.MinecraftFontParityFixture
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.runtime.minecraft.createMinecraftUiHost
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontCompatibility
import dev.s7a.strata.runtime.minecraft.font.MinecraftTrueTypeRasterizer
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.semantics.SemanticsRole
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/**
 * Generates the shared scene from resource bytes in one isolated CPU worker, without native game measurements or images.
 * The task supplies an explicit dependency and compatibility contract and recreates its owned artifacts on every invocation.
 * Generation records provenance but does not claim native pixel equality; the separate comparison gate establishes that result.
 */
@OptIn(InternalStrataRuntimeApi::class)
@Tag("font-offline-scene")
internal class MinecraftOfflineFontSceneTest {
    @Test
    fun `new offline hosts render the native oracle scene at every physical scale`() {
        val output = Path.of(property("strata.fontOfflineOutput"))
        Files.createDirectories(output)
        val metadataPath = output.resolve("font-offline.properties")
        Files.deleteIfExists(metadataPath)
        val compatibility =
            MinecraftFontCompatibility(
                rasterizer = MinecraftTrueTypeRasterizer.valueOf(property("strata.fontRasterizer")),
                packFormat = 0,
                providerFilters = property("strata.fontProviderFilters").toBooleanStrict(),
                packOverlays = property("strata.fontPackOverlays").toBooleanStrict(),
                minorPackFormats = property("strata.fontMinorPackFormats").toBooleanStrict(),
                interleavedShadows = property("strata.fontInterleavedShadows").toBooleanStrict(),
                fractionalUnihexAdvance = property("strata.fontFractionalUnihexAdvance").toBooleanStrict(),
                rejectMalformedOverlayMetadata = property("strata.fontRejectMalformedOverlayMetadata").toBooleanStrict(),
                bakedGlyphMetrics = property("strata.fontBakedGlyphMetrics").toBooleanStrict(),
                saturatingCeil = property("strata.fontSaturatingCeil").toBooleanStrict(),
                preparedTextBounds = property("strata.fontPreparedTextBounds").toBooleanStrict(),
            )
        val snapshot = MinecraftFontParityFixture.snapshot(compatibility)
        val profile = MinecraftFontParityFixture.profile(snapshot)
        val metadata = MinecraftFontParityFixture.evidenceMetadata(compatibility).toMutableMap()
        metadata["minecraft.version"] = property("strata.minecraftVersion")
        metadata["offline.input"] = "original-resource-files"
        metadata["offline.guiScales"] = "1,2,3"
        metadata["declared.lwjgl"] = property("strata.fontLwjglVersion")
        metadata["declared.icu"] = property("strata.fontIcuVersion")
        metadata["declared.gson"] = property("strata.fontGsonVersion")
        metadata["declared.coreClassifier"] = property("strata.fontCoreClassifier")
        metadata["declared.nativeClassifier"] = property("strata.fontNativeClassifier")
        createMinecraftUiHost(MinecraftFontParityFixture.definition(), profile, LwjglMinecraftFontBackendFactory).use { host ->
            host.attach()
            val frame = host.frame(MinecraftFontParityFixture.viewport)
            writeSampledCommands(frame.drawCommands, output)
            val rows = frame.semantics.filter { it.semantics.role == SemanticsRole.Text }
            assertEquals(MinecraftFontParityFixture.rows.size, rows.size)
            rows.forEachIndexed { index, row ->
                assertTrue(0 < row.bounds.width)
                metadata["layout.$index"] = "${row.bounds.left},${row.bounds.top},${row.bounds.width},${row.bounds.height}"
            }
        }
        for (scale in 1..3) {
            val image = MinecraftFontParityFixture.render(profile, scale)
            assertEquals(MinecraftFontParityFixture.viewport.width * scale, image.size.width)
            assertEquals(MinecraftFontParityFixture.viewport.height * scale, image.size.height)
            assertTrue(image.copyArgb().any { it != MinecraftFontParityFixture.background })
            val imagePath = output.resolve("font-offline-$scale.png")
            val bytes = image.encodePng()
            Files.write(imagePath, bytes)
            metadata["image.${imagePath.fileName}.sha256"] = MinecraftFontParityFixture.sha256(bytes)
        }
        Files.writeString(metadataPath, metadata.entries.joinToString("\n", postfix = "\n") { (key, value) -> "$key=$value" })
    }

    private fun writeSampledCommands(
        commands: List<DrawCommand>,
        output: Path,
    ) {
        val images = output.resolve("glyphs")
        Files.createDirectories(images)
        val written = mutableSetOf<String>()
        val rows =
            buildString {
                appendLine("command\tdestinationLeft\tdestinationTop\tdestinationRight\tdestinationBottom\tsourceLeft\tsourceTop\tsourceRight\tsourceBottom\ttintArgb\talphaCutoff\timageWidth\timageHeight\trawArgbBigEndianSha256\timage")
                commands.forEachIndexed { index, command ->
                    if (command is DrawCommand.SampledImage) {
                        val pixels = command.image.copyArgb()
                        val bytes = ByteBuffer.allocate(Math.multiplyExact(pixels.size, Int.SIZE_BYTES))
                        for (pixel in pixels) bytes.putInt(pixel)
                        val hash = MinecraftFontParityFixture.sha256(bytes.array())
                        val name = "${command.image.size.width}x${command.image.size.height}-$hash.png"
                        if (written.add(name)) writeImage(command.image, images.resolve(name))
                        val destination = command.destination
                        val source = command.source
                        appendLine(
                            listOf(
                                index,
                                destination.left,
                                destination.top,
                                destination.right,
                                destination.bottom,
                                source.left,
                                source.top,
                                source.right,
                                source.bottom,
                                command.tint.value
                                    .toUInt()
                                    .toString(16),
                                command.alphaCutoff,
                                command.image.size.width,
                                command.image.size.height,
                                hash,
                                "glyphs/$name",
                            ).joinToString("\t"),
                        )
                    }
                }
            }
        Files.writeString(output.resolve("sampled-commands.tsv"), rows)
    }

    private fun writeImage(
        image: DrawImage,
        path: Path,
    ) {
        val bitmap = BufferedImage(image.size.width, image.size.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until image.size.height) {
            for (x in 0 until image.size.width) bitmap.setRGB(x, y, image.argbAt(x, y))
        }
        check(ImageIO.write(bitmap, "png", path.toFile())) { "Could not encode offline source glyph $path." }
    }

    private fun property(name: String): String = checkNotNull(System.getProperty(name)) { "The isolated font scene worker requires $name." }
}
