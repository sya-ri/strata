package dev.s7a.strata.runtime.remote

import dev.s7a.strata.component.ImageSource
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.projection.ProjectionValue
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.text.UiText
import dev.s7a.strata.text.UiTextArgument
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Portable text and image schemas preserve client resource resolution and reject oversized allocation requests.
 */
internal class RemoteResourceTest {
    @Test
    fun preservesNestedTranslationArgumentsFallbackAndFonts() {
        val text =
            UiText.WithFont(
                UiText.concat(
                    UiText.Literal("Hello "),
                    UiText.Translated("example.count", listOf(UiTextArgument.IntValue(4), UiTextArgument.Text(UiText.Literal("world"))), "%s %s"),
                ),
                ResourceId("example", "font"),
            )
        val wire = RemoteValueCodec()
        assertEquals(text, RemoteTextCodec.decode(wire.decode(wire.encode(RemoteTextCodec.encode(text)))))
    }

    @Test
    fun imagesPreservePixelsAndResourceReferences() {
        val codec = RemoteImageCodec(16)
        val pixels = ImageSource.Pixels(createDrawImage(IntSize(2, 2), intArrayOf(0, -1, 0x7F00FF00, -65536)))
        assertEquals(pixels, codec.decode(codec.encode(pixels)))
        val resource = ImageSource.Resource(ResourceId("example", "textures/widget.png"))
        assertEquals(resource, codec.decode(codec.encode(resource)))
        assertThrows(IllegalArgumentException::class.java) { RemoteImageCodec(4).encode(pixels) }
    }

    @Test
    fun rejectsOverflowingImageDimensionsBeforeAllocatingPixels() {
        val record =
            ProjectionValue.Sequence(
                listOf(ProjectionValue.Integer(1), ProjectionValue.Integer(Int.MAX_VALUE.toLong()), ProjectionValue.Integer(Int.MAX_VALUE.toLong()), ProjectionValue.Bytes(byteArrayOf())),
            )
        assertThrows(IllegalArgumentException::class.java) { RemoteImageCodec().decode(record) }
    }
}
