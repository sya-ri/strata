package dev.s7a.strata.integration.docs

import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageTypeSpecifier
import javax.imageio.metadata.IIOMetadata
import javax.imageio.metadata.IIOMetadataNode
import javax.imageio.stream.MemoryCacheImageOutputStream

/**
 * Writes a full-frame GIF89a loop through the standard JDK writer with explicit hold times and a shared palette.
 * Encoding is synchronous, never changes inputs, closes its output stream, and disposes its writer on every path.
 */
internal object ReadmeGifEncoder {
    /**
     * Encodes the fixed storyboard; missing sequence support and writer failures propagate to the caller.
     * No wall clock, operating-system font, external executable, or temporary file participates.
     */
    fun encode(frames: List<ReadmeGifFrame>): ByteArray {
        val images = ReadmeGifPalette.convert(frames.map { it.image })
        val writer = ImageIO.getImageWritersByFormatName("gif").asSequence().first()
        val bytes = ByteArrayOutputStream()
        try {
            check(writer.canWriteSequence()) { "The JDK GIF writer must support animated sequences." }
            MemoryCacheImageOutputStream(bytes).use { output ->
                writer.output = output
                writer.prepareWriteSequence(null)
                images.forEachIndexed { index, image ->
                    val metadata = writer.getDefaultImageMetadata(ImageTypeSpecifier(image), writer.defaultWriteParam)
                    configure(metadata, frames[index].delayCentiseconds, index == 0)
                    writer.writeToSequence(IIOImage(image, null, metadata), writer.defaultWriteParam)
                }
                writer.endWriteSequence()
            }
        } finally {
            writer.dispose()
        }
        return bytes.toByteArray()
    }

    private fun configure(
        metadata: IIOMetadata,
        delay: Int,
        first: Boolean,
    ) {
        val root = metadata.getAsTree(FORMAT) as IIOMetadataNode
        val controls = root.getElementsByTagName("GraphicControlExtension")
        val control = controls.item(0) as? IIOMetadataNode ?: IIOMetadataNode("GraphicControlExtension").also(root::appendChild)
        control.setAttribute("disposalMethod", "doNotDispose")
        control.setAttribute("userInputFlag", "FALSE")
        control.setAttribute("transparentColorFlag", "FALSE")
        control.setAttribute("delayTime", delay.toString())
        control.setAttribute("transparentColorIndex", "0")
        if (first) {
            val extensions = IIOMetadataNode("ApplicationExtensions")
            val loop = IIOMetadataNode("ApplicationExtension")
            loop.setAttribute("applicationID", "NETSCAPE")
            loop.setAttribute("authenticationCode", "2.0")
            loop.userObject = byteArrayOf(1, 0, 0)
            extensions.appendChild(loop)
            root.appendChild(extensions)
        }
        metadata.setFromTree(FORMAT, root)
    }

    private const val FORMAT = "javax_imageio_gif_image_1.0"
}
