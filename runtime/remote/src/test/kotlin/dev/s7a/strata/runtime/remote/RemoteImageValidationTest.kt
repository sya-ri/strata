package dev.s7a.strata.runtime.remote

import dev.s7a.strata.component.ImageScale
import dev.s7a.strata.component.ImageSource
import dev.s7a.strata.component.NineSliceCenterMode
import dev.s7a.strata.geometry.Insets
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.projection.ProjectionAction
import dev.s7a.strata.projection.ProjectionBinding
import dev.s7a.strata.projection.ProjectionScope
import dev.s7a.strata.projection.ProjectionType
import dev.s7a.strata.projection.ProjectionValue
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.text.UiText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Preserves profile source admission timing and the separate standalone and negotiated image bounds.
 */
internal class RemoteImageValidationTest {
    @Test
    fun bothBackgroundOverloadsRejectAboveTheStandaloneBoundDuringConstruction() {
        val source = oversizedSource()
        val runtime = RemoteComponentRuntime()
        assertThrows(IllegalArgumentException::class.java) { RemoteImageCodec().encode(source) }
        assertThrows(IllegalArgumentException::class.java) { runtime.imageBackground(Modifier.Empty, source, ImageScale.Stretch) }
        assertThrows(IllegalArgumentException::class.java) { runtime.imageBackground(Modifier.Empty, source, Insets(0, 0, 0, 0), NineSliceCenterMode.Tiled) }
    }

    @Test
    fun largerNegotiatedLimitsDoNotExpandTheProfileSourceSchema() {
        val source = oversizedSource()
        val runtime = RemoteComponentRuntime()
        val element = runtime.image(source, null, null, Modifier.Empty, null)
        val scope = ImageScope(RemoteLimits().messageBytes + Int.SIZE_BYTES)
        assertThrows(IllegalArgumentException::class.java) { requireNotNull(element.projection).encode(scope) }
        assertEquals(0, scope.imageCalls)
    }

    @Test
    fun smallerNegotiatedLimitsStillRejectAtDeferredProjectionAfterValidConstruction() {
        val source = ImageSource.Pixels(createDrawImage(IntSize(6, 6), IntArray(36)))
        val runtime = RemoteComponentRuntime()
        val backgrounds = listOf(
            runtime.imageBackground(Modifier.Empty, source, ImageScale.Stretch),
            runtime.imageBackground(Modifier.Empty, source, Insets(0, 0, 0, 0), NineSliceCenterMode.Tiled),
        )
        backgrounds.forEach { background ->
            val scope = ImageScope(128)
            assertThrows(IllegalArgumentException::class.java) { requireNotNull(background.elements().single().projection).encode(scope) }
            assertEquals(1, scope.imageCalls)
        }
    }

    private fun oversizedSource(): ImageSource.Pixels {
        val area = RemoteLimits().messageBytes / Int.SIZE_BYTES + 1
        return ImageSource.Pixels(createDrawImage(IntSize(area, 1), IntArray(area)))
    }

    private class ImageScope(maximumBytes: Int) : ProjectionScope {
        private val codec = RemoteImageCodec(maximumBytes)
        var imageCalls = 0
            private set

        override fun image(image: DrawImage): ProjectionValue {
            imageCalls++
            return codec.encode(ImageSource.Pixels(image))
        }

        override fun text(text: UiText): ProjectionValue = error("Unused text")
        override fun requireType(type: ProjectionType): Unit = error("Unused type")
        override fun action(action: ProjectionAction<*>, key: ProjectionValue): Long = error("Unused action")
        override fun <T : Any> binding(binding: ProjectionBinding<T>): ProjectionValue = error("Unused binding")
    }
}
