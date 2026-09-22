package dev.s7a.strata.runtime.remote

import dev.s7a.strata.component.ImageSource
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.projection.ProjectionFields
import dev.s7a.strata.projection.ProjectionValue
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.resource.ResourceId
import java.nio.ByteBuffer

/**
 * Portable image sources retaining resource identifiers or bounded immutable straight-ARGB pixels.
 * Transport fragmentation is handled independently; pixel decoding never allocates from unchecked dimensions.
 */
public class RemoteImageCodec(
    private val maximumBytes: Int = RemoteLimits().messageBytes,
) {
    init {
        require(0 < maximumBytes) { "The image byte limit must be positive." }
    }

    /**
     * Serializes a structural resource reference or an immutable caller-owned pixel snapshot.
     */
    public fun encode(source: ImageSource): ProjectionValue =
        when (source) {
            is ImageSource.Resource -> {
                ProjectionValue.Sequence(
                    listOf(
                        ProjectionValue.Integer(Kind.Resource.ordinal.toLong()),
                        ProjectionValue.Text(source.id.namespace),
                        ProjectionValue.Text(source.id.path),
                    ),
                )
            }

            is ImageSource.Pixels -> {
                encodePixels(source.image)
            }
        }

    /**
     * Validates the complete image record before constructing any client-side image snapshot.
     */
    public fun decode(value: ProjectionValue): ImageSource {
        val fields = ProjectionFields(value)
        val result =
            when (Kind.entries[fields.int(Kind.entries.indices)]) {
                Kind.Resource -> {
                    ImageSource.Resource(ResourceId(fields.text(), fields.text()))
                }

                Kind.Pixels -> {
                    val size = IntSize(fields.int(0..Int.MAX_VALUE), fields.int(0..Int.MAX_VALUE))
                    val encoded = requireNotNull(fields.value() as? ProjectionValue.Bytes) { "Expected image bytes." }
                    val area = size.width.toLong() * size.height.toLong()
                    require(area <= maximumBytes / Int.SIZE_BYTES && encoded.size.toLong() == area * Int.SIZE_BYTES) { "Image dimensions or pixels exceed their bound." }
                    val pixels = IntArray(area.toInt())
                    ByteBuffer.wrap(encoded.toByteArray()).asIntBuffer().get(pixels)
                    ImageSource.Pixels(createDrawImage(size, pixels))
                }
            }
        fields.finish()
        return result
    }

    private fun encodePixels(image: DrawImage): ProjectionValue {
        val area = image.size.width.toLong() * image.size.height.toLong()
        require(area <= maximumBytes / Int.SIZE_BYTES) { "Image exceeds its byte bound." }
        val bytes = ByteBuffer.allocate(area.toInt() * Int.SIZE_BYTES)
        bytes.asIntBuffer().put(image.copyArgb())
        return ProjectionValue.Sequence(
            listOf(
                ProjectionValue.Integer(Kind.Pixels.ordinal.toLong()),
                ProjectionValue.Integer(image.size.width.toLong()),
                ProjectionValue.Integer(image.size.height.toLong()),
                ProjectionValue.Bytes(bytes.array()),
            ),
        )
    }

    private enum class Kind { Resource, Pixels }
}
