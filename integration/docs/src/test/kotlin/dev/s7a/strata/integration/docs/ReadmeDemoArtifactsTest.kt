package dev.s7a.strata.integration.docs

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.imageio.metadata.IIOMetadataNode
import javax.imageio.stream.MemoryCacheImageInputStream

/**
 * Verifies replay timing, exact source/pixel pairing, repeatability, and non-mutating stale-output detection.
 */
internal class ReadmeDemoArtifactsTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun storyboardRecreatesAnInfiniteThirtyFourSecondGifAndUnmodifiedScreenStills() {
        val root = repository()
        val assets = ReadmeDemoFixture.assets(temporary.resolve("assets"))
        val first = ReadmeDemoPipeline.prepare(root, assets, "test")
        val second = ReadmeDemoPipeline.prepare(root, assets, "test")
        first.files.forEach { (name, bytes) -> assertArrayEquals(bytes, second.files.getValue(name), name) }
        val gif = first.files.getValue("demo.gif")
        assertTrue(gif.size <= 5 * 1024 * 1024)
        verifyGif(gif)
        ReadmeDemoStage.entries.forEach { stage ->
            val stem = "${stage.ordinal + 1}-${stage.name.lowercase()}"
            val screen =
                first.files
                    .getValue("$stem-screen.png")
                    .inputStream()
                    .use(ImageIO::read)
            val composed =
                first.files
                    .getValue("$stem.png")
                    .inputStream()
                    .use(ImageIO::read)
            assertArrayEquals(
                screen.getRGB(0, 0, 512, 384, null, 0, 512),
                composed.getRGB(672, 96, 512, 384, null, 0, 512),
                "The full-color still must contain the original complete screen pixels.",
            )
            val source = ReadmeDemoSource.read(root, stage)
            val markdown = first.files.getValue("README.md").toString(Charsets.UTF_8)
            assertTrue(markdown.contains(source.lines.joinToString("\n")))
            assertTrue(source.full.contains(source.lines.first()))
        }
        val checkedRoot = temporary.resolve("checked")
        ReadmeDemoPipeline.write(checkedRoot.resolve("docs/readme-demo"), first)
        val readme = ReadmeDemoReadme.replace("before\n<!-- strata-readme-demo:start -->\n<!-- strata-readme-demo:end -->\nafter\n")
        Files.writeString(checkedRoot.resolve("README.md"), readme)
        ReadmeDemoPipeline.check(checkedRoot, first)
        val target = checkedRoot.resolve("docs/readme-demo/demo.gif")
        Files.write(target, byteArrayOf(1, 2, 3))
        assertThrows(IllegalArgumentException::class.java) { ReadmeDemoPipeline.check(checkedRoot, first) }
        assertArrayEquals(byteArrayOf(1, 2, 3), Files.readAllBytes(target))
        assertEquals(readme, Files.readString(checkedRoot.resolve("README.md")))
    }

    @Test
    fun sourceExtractionAndReadmeReplacementRejectAmbiguityAndPreserveSurroundingContent() {
        val sourceDirectory = temporary.resolve(ReadmeDemoSource.DIRECTORY)
        Files.createDirectories(sourceDirectory)
        val sourceFile = sourceDirectory.resolve("BasicPlayersExample.kt")
        Files.writeString(sourceFile, "fun example() {\n    // readme-demo:start\n    Column {}\n    // readme-demo:end\n}\n")
        assertEquals(listOf("Column {}"), ReadmeDemoSource.read(temporary, ReadmeDemoStage.Basic).lines)
        Files.writeString(sourceFile, "// readme-demo:start\n// readme-demo:start\n// readme-demo:end")
        assertThrows(IllegalArgumentException::class.java) { ReadmeDemoSource.read(temporary, ReadmeDemoStage.Basic) }
        val before = "unchanged before\n<!-- strata-readme-demo:start -->\nstale\n<!-- strata-readme-demo:end -->\nunchanged after"
        val after = ReadmeDemoReadme.replace(before)
        assertTrue(after.startsWith("unchanged before\n"))
        assertTrue(after.endsWith("\nunchanged after"))
        assertFalse(after.contains("stale"))
        assertThrows(IllegalArgumentException::class.java) { ReadmeDemoReadme.replace(before + "\n<!-- strata-readme-demo:start -->") }
        assertThrows(IllegalArgumentException::class.java) { ReadmeDemoReadme.replace("no anchors") }
    }

    private fun verifyGif(bytes: ByteArray) {
        val reader = ImageIO.getImageReadersByFormatName("gif").asSequence().first()
        try {
            MemoryCacheImageInputStream(bytes.inputStream()).use { input ->
                reader.input = input
                assertEquals(25, reader.getNumImages(true))
                val delays =
                    (0 until 25).map { index ->
                        val image = reader.read(index)
                        assertEquals(1200, image.width)
                        assertEquals(576, image.height)
                        val metadata = reader.getImageMetadata(index).getAsTree("javax_imageio_gif_image_1.0") as IIOMetadataNode
                        val control = metadata.getElementsByTagName("GraphicControlExtension").item(0) as IIOMetadataNode
                        if (index == 0) {
                            val loop = metadata.getElementsByTagName("ApplicationExtension").item(0) as IIOMetadataNode
                            assertEquals("NETSCAPE", loop.getAttribute("applicationID"))
                            assertArrayEquals(byteArrayOf(1, 0, 0), loop.userObject as ByteArray)
                        }
                        control.getAttribute("delayTime").toInt()
                    }
                assertEquals(listOf(300, 400, 400, 500, 400, 200) + List(5) { 40 } + listOf(200, 100, 200) + List(10) { 30 } + 200, delays)
                assertEquals(3400, delays.sum())
            }
        } finally {
            reader.dispose()
        }
    }

    private fun repository(): Path {
        val current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
        return if (Files.isDirectory(current.resolve("api"))) current else current.resolve("../..").normalize()
    }
}
