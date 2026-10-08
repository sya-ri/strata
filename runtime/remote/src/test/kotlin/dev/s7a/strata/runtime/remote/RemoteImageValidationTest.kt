package dev.s7a.strata.runtime.remote

import dev.s7a.strata.component.ImageScale
import dev.s7a.strata.component.ImageSource
import dev.s7a.strata.component.NineSliceCenterMode
import dev.s7a.strata.component.Spacer
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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
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

    @Test
    fun unusedOversizedBackgroundConstructionFailsBeforeDeclarationsAndReleasesTheSession() {
        val source = oversizedSource()
        val expected = requireNotNull(runCatching { RemoteImageCodec().encode(source) }.exceptionOrNull())
        val constructors: List<(RemoteComponentRuntime, ImageSource) -> Modifier> = listOf(
            { runtime, value -> runtime.imageBackground(Modifier.Empty, value, ImageScale.Stretch) },
            { runtime, value -> runtime.imageBackground(Modifier.Empty, value, Insets(0, 0, 0, 0), NineSliceCenterMode.Tiled) },
        )
        constructors.forEach { construct ->
            val runtime = RemoteComponentRuntime()
            var contentFinished = false
            var snapshots = 0
            var closes = 0
            lateinit var server: RemoteServerSession
            server = RemoteServerSession(1, ProjectionValue.Absent, RemoteRegistry().also(RemoteBuiltins::register).types, send = { message ->
                when (message) {
                    is RemoteMessage.Snapshot -> snapshots++
                    is RemoteMessage.Close -> {
                        closes++
                        assertTrue(server.status is RemoteSessionStatus.Closed)
                        assertEquals(0, server.nodeCount)
                        assertTrue(server.requiredTypes.isEmpty())
                    }
                    else -> Unit
                }
            }) {
                construct(runtime, source)
                contentFinished = true
                runtime.evaluate { Spacer() }
            }
            val actual = assertThrows(IllegalArgumentException::class.java, server::tick)
            assertEquals(expected.javaClass, actual.javaClass)
            assertEquals(expected.message, actual.message)
            assertFalse(contentFinished)
            assertEquals(0, snapshots)
            assertEquals(1, closes)
            assertTrue(server.status is RemoteSessionStatus.Closed)
            server.close()
            assertEquals(1, closes)
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
