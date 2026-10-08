@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.remote

import dev.s7a.strata.component.ImageScale
import dev.s7a.strata.component.ImageSource
import dev.s7a.strata.component.NineSliceCenterMode
import dev.s7a.strata.component.PlayerSkinSource
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
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.UiText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Independent pixel/wire, identity, admission, current-retention, owner and terminal image projection contracts.
 */
internal class RemoteImageOwnershipTest {
    @Test
    fun pixelRecordsPreserveIndependentBigEndianBytesAndDefensiveStorage() {
        for (size in listOf(IntSize(0, 3), IntSize(2, 2), IntSize(256, 256), IntSize(512, 512), IntSize(1024, 1024), IntSize(4096, 511))) {
            val pixels = IntArray(size.width * size.height) { it * 8191 }
            val image = createDrawImage(size, pixels)
            val expected = ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use { output -> pixels.forEach(output::writeInt) } }.toByteArray()
            val source = ImageSource.Pixels(image)
            val first = RemoteImageCodec().encode(source)
            pixels.fill(-1)
            val fields = first as ProjectionValue.Sequence
            assertEquals(size.width.toLong(), (fields.values[1] as ProjectionValue.Integer).value)
            assertEquals(size.height.toLong(), (fields.values[2] as ProjectionValue.Integer).value)
            val encoded = fields.values[3] as ProjectionValue.Bytes
            assertTrue(expected.contentEquals(encoded.toByteArray()))
            encoded.toByteArray().fill(0)
            image.copyArgb().fill(0)
            assertTrue(expected.contentEquals(encoded.toByteArray()))
            val wire = RemoteValueCodec()
            assertEquals(source, RemoteImageCodec().decode(wire.decode(wire.encode(first))))
            assertEquals(first, RemoteImageCodec().encode(source))
        }
    }

    @Test
    fun currentIdentityReusesOneDetachedRecordAndEqualReplacementGetsIndependentStorage() {
        val image = image(1)
        val equal = image(1)
        RemoteServerImages(RemoteLimits()).use { images ->
            images.begin()
            val first = images.project(image)
            assertSame(first, images.project(image))
            images.commit()
            images.begin()
            assertSame(first, images.project(image))
            val next = images.project(equal)
            assertEquals(first, next)
            assertNotSame(first, next)
            images.commit()
            assertEquals(2, retained(images).first().size)
            images.begin()
            assertSame(next, images.project(equal))
            images.commit()
            assertEquals(1, retained(images).first().size)
            assertTrue(retained(images).first().containsKey(image).not())
            images.begin()
            images.commit()
            assertTrue(retained(images).all { it.isEmpty() })
            images.begin()
            assertNotSame(next, images.project(equal))
            images.commit()
        }
    }

    @Test
    fun unionChargesRawAndEncodedPixelsAndUsesUncachedFallbackWithoutEvictingCurrentHits() {
        val limits = RemoteLimits(frameBytes = 64, messageBytes = 64, pendingBytes = 64, treeNodes = 4, collectionEntries = 4)
        val first = image(1)
        val second = image(2)
        val third = image(3)
        RemoteServerImages(limits).use { images ->
            images.begin()
            val a = images.project(first)
            images.project(second)
            val uncached = images.project(third)
            assertNotSame(uncached, images.project(third))
            assertEquals(2, retained(images).last().size)
            assertEquals(64L, field(images, "retainedBytes"))
            images.commit()
            images.begin()
            assertSame(a, images.project(first))
            images.project(third)
            images.commit()
            assertEquals(1, retained(images).first().size)
            assertTrue(retained(images).all { map -> map.containsKey(third).not() })
        }
    }

    @Test
    fun emptyImageMembershipStillHasAFiniteEntryBoundAndChurnRetainsOnlyCurrentInputs() {
        val limits = RemoteLimits(treeNodes = 2, collectionEntries = 2)
        RemoteServerImages(limits).use { images ->
            val zero = createDrawImage(IntSize(0, 0), intArrayOf())
            images.begin()
            images.project(zero)
            images.project(createDrawImage(IntSize(0, 1), intArrayOf()))
            val extra = createDrawImage(IntSize(1, 0), intArrayOf())
            val first = images.project(extra)
            assertNotSame(first, images.project(extra))
            images.commit()
            assertEquals(2, retained(images).first().size)
            for (value in 0 until 64) {
                val current = image(value)
                images.begin()
                images.project(current)
                images.commit()
                assertTrue(retained(images).first().size <= 1)
                assertTrue(retained(images).first().keys.all { it === current })
            }
        }
    }

    @Test
    fun pendingFailureCloseAndWrongOwnerCannotRetainOrReopenImages() {
        val images = RemoteServerImages(RemoteLimits())
        images.begin()
        images.project(image(1))
        assertThrows(IllegalStateException::class.java, images::begin)
        val executor = Executors.newSingleThreadExecutor()
        try {
            assertTrue(executor.submit<Boolean> { runCatching { images.project(image(2)) }.exceptionOrNull() is IllegalStateException }.get())
            assertEquals(1, retained(images).last().size)
        } finally {
            executor.shutdownNow()
            check(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
        images.close()
        images.close()
        assertTrue(retained(images).all { it.isEmpty() })
        assertEquals(0L, field(images, "retainedBytes"))
        assertThrows(IllegalStateException::class.java, images::begin)
        assertThrows(IllegalStateException::class.java) { images.project(image(3)) }
        assertThrows(IllegalStateException::class.java, images::commit)
    }

    @Test
    fun projectionAndSendFailuresClearImagesBeforeTerminalNotification() {
        for (failInProjection in listOf(false, true)) {
            val image = image(7)
            val failure = IllegalStateException("image owner failure")
            lateinit var server: RemoteServerSession
            var terminal = 0
            val root = RemoteProfileElement(RemoteProfileComponent.Image, Modifier.Empty, null) { scope ->
                val pixels = scope.image(image)
                if (failInProjection) throw failure
                RemoteProperties.record(pixels, ProjectionValue.Absent, ProjectionValue.Absent)
            }
            server = RemoteServerSession(1, ProjectionValue.Absent, setOf(RemoteProfileComponent.Image.type), send = { message ->
                when (message) {
                    is RemoteMessage.Snapshot -> throw failure
                    is RemoteMessage.Close -> {
                        terminal++
                        assertTrue(retained(field(server, "images") as RemoteServerImages).all { it.isEmpty() })
                    }
                    else -> Unit
                }
            }) { root }
            assertSame(failure, assertThrows(IllegalStateException::class.java, server::tick))
            assertEquals(1, terminal)
            assertTrue(server.status is RemoteSessionStatus.Closed)
            server.close()
            assertEquals(1, terminal)
        }
    }

    @Test
    fun independentSessionsAndStandardPixelImageSkinAndModifierPathsShareOnlyWithinOneOwner() {
        val pixels = createDrawImage(IntSize(64, 64), IntArray(64 * 64) { 9 })
        val runtime = RemoteComponentRuntime()
        var encoded: ProjectionValue? = null
        var scopeCalls = 0
        val scope = object : ProjectionScope {
            override fun image(image: DrawImage): ProjectionValue {
                assertSame(pixels, image)
                scopeCalls++
                return RemoteImageCodec().encode(ImageSource.Pixels(image)).also { encoded = it }
            }
            override fun text(text: UiText): ProjectionValue = error("Unused text")
            override fun requireType(type: ProjectionType): Unit = error("Unused type")
            override fun action(action: ProjectionAction<*>, key: ProjectionValue): Long = error("Unused action")
            override fun <T : Any> binding(binding: ProjectionBinding<T>): ProjectionValue = error("Unused binding")
        }
        val source = ImageSource.Pixels(pixels)
        val image = runtime.image(source, null, null, Modifier.Empty, null)
        val record = requireNotNull(image.projection).encode(scope) as ProjectionValue.Sequence
        assertSame(encoded, record.values.first())
        val skin = runtime.playerHead(PlayerSkinSource.Pixels(pixels), 8, true, null, null, Modifier.Empty, null)
        requireNotNull(skin.projection).encode(scope)
        val background = runtime.imageBackground(Modifier.Empty, source, ImageScale.Stretch)
        background.elements().forEach { requireNotNull(it.projection).encode(scope) }
        val nineSlice = runtime.imageBackground(Modifier.Empty, source, Insets(0, 0, 0, 0), NineSliceCenterMode.Tiled)
        nineSlice.elements().forEach { requireNotNull(it.projection).encode(scope) }
        assertEquals(4, scopeCalls)
        val left = RemoteServerImages(RemoteLimits())
        val right = RemoteServerImages(RemoteLimits())
        try {
            left.begin()
            right.begin()
            val a = left.project(pixels)
            val b = right.project(pixels)
            assertEquals(a, b)
            assertNotSame(a, b)
            left.commit()
            right.commit()
            left.close()
            right.begin()
            assertSame(b, right.project(pixels))
            right.commit()
        } finally {
            left.close()
            right.close()
        }
    }

    @Test
    fun perImageBoundsRejectBeforeAdmissionAndEveryInvalidDecodeKeepsCodecUsable() {
        val limits = RemoteLimits(frameBytes = 64, messageBytes = 64, pendingBytes = 64, treeNodes = 4, collectionEntries = 4)
        RemoteServerImages(limits).use { images ->
            images.begin()
            val accepted = images.project(image(1))
            val large = createDrawImage(IntSize(5, 5), IntArray(25))
            assertThrows(IllegalArgumentException::class.java) { images.project(large) }
            assertEquals(1, retained(images).last().size)
            assertSame(accepted, images.project(imageKey(images)))
            images.commit()
        }
        val codec = RemoteImageCodec(16)
        val record = codec.encode(ImageSource.Pixels(image(4))) as ProjectionValue.Sequence
        val fields = record.values
        val invalid = listOf(
            ProjectionValue.Sequence(fields.dropLast(1) + ProjectionValue.Bytes(byteArrayOf())),
            ProjectionValue.Sequence(listOf(fields[0], ProjectionValue.Integer(Int.MAX_VALUE.toLong()), ProjectionValue.Integer(Int.MAX_VALUE.toLong()), fields[3])),
            ProjectionValue.Sequence(fields + ProjectionValue.Absent),
        )
        invalid.forEach {
            assertThrows(IllegalArgumentException::class.java) { codec.decode(it) }
            assertEquals(ImageSource.Pixels(image(4)), codec.decode(record))
        }
        assertThrows(IllegalArgumentException::class.java) { RemoteImageCodec(15).encode(ImageSource.Pixels(image(4))) }
    }

    private fun imageKey(images: RemoteServerImages): DrawImage = retained(images).last().keys.single() as DrawImage

    private fun image(value: Int): DrawImage = createDrawImage(IntSize(2, 2), IntArray(4) { value })

    private fun retained(images: RemoteServerImages): List<Map<*, *>> = listOf("current", "pending").map { field(images, it) as Map<*, *> }

    private fun field(owner: Any, name: String): Any = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
}
