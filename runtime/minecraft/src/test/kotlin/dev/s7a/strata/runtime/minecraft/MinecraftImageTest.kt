package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.component.Image
import dev.s7a.strata.component.ImageScale
import dev.s7a.strata.component.ImageSource
import dev.s7a.strata.component.NineSliceCenterMode
import dev.s7a.strata.component.PlayerSkinSource
import dev.s7a.strata.component.SlotBinding
import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.VirtualList
import dev.s7a.strata.component.VirtualListState
import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.Insets
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.imageBackground
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.spi.ComponentEvaluator
import dev.s7a.strata.spi.ComponentRuntime
import dev.s7a.strata.spi.ComponentRuntimeBridge
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * Verifies arbitrary resource-pack image components and backgrounds through the public common boundary.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class MinecraftImageTest {
    @Test
    fun assetIdentifiersAreStructuralAndValidateBothParts() {
        val first = ResourceId("example", "textures/gui/panel.png")
        val equal = ResourceId("example", "textures/gui/panel.png")
        val other = ResourceId("example", "textures/gui/other.png")

        assertEquals("example", first.namespace)
        assertEquals("textures/gui/panel.png", first.path)
        assertEquals(first, equal)
        assertEquals(first.hashCode(), equal.hashCode())
        assertNotSame(first, equal)
        assertEquals(false, first == other)
        assertThrows(IllegalArgumentException::class.java) { ResourceId("Example", "textures/gui/panel.png") }
        assertThrows(IllegalArgumentException::class.java) { ResourceId("example", "../panel.png") }
    }

    @Test
    fun imageMapsCompleteSourceToRequestedLogicalSize() {
        val source =
            createDrawImage(
                IntSize(2, 2),
                intArrayOf(
                    0xFFFF0000.toInt(),
                    0xFF00FF00.toInt(),
                    0xFF0000FF.toInt(),
                    0xFFFFFFFF.toInt(),
                ),
            )
        val host =
            createMinecraftUiHost(
                ScreenDefinition("Image") {
                    Image(ImageSource.Pixels(source), IntSize(4, 4))
                },
                MinecraftProfileFixture.create(),
            )
        try {
            host.attach()
            val frame = host.frame(IntSize(4, 4))
            val command = frame.drawCommands.single() as DrawCommand.SampledImage
            assertSame(source, command.image)
            assertEquals(FloatRect(0f, 0f, 2f, 2f), command.source)
            assertEquals(FloatRect(0f, 0f, 4f, 4f), command.destination)
            assertEquals(0f, command.alphaCutoff)
            val rendered = rasterizeHeadless(frame.drawCommands, IntSize(4, 4))
            assertEquals(0xFFFF0000.toInt(), rendered.argbAt(0, 0))
            assertEquals(0xFF00FF00.toInt(), rendered.argbAt(3, 0))
            assertEquals(0xFF0000FF.toInt(), rendered.argbAt(0, 3))
            assertEquals(0xFFFFFFFF.toInt(), rendered.argbAt(3, 3))
        } finally {
            host.close()
        }
    }

    @Test
    fun equalResourceImagesShareOneResolutionAcrossComponentsAndBackgrounds() {
        val firstId = ResourceId("example", "textures/gui/shared.png")
        val equalId = ResourceId("example", "textures/gui/shared.png")
        val firstSource = ImageSource.Resource(firstId)
        val equalSource = ImageSource.Resource(equalId)
        val platform =
            FakeImagePlatform { _, call ->
                solidImage(0xFF123400.toInt() + call)
            }
        val host =
            createMinecraftUiHost(
                ScreenDefinition("Shared resource image") {
                    Stack(modifier = Modifier.Empty.imageBackground(firstSource, ImageScale.Stretch)) {
                        Image(equalSource, IntSize(2, 2))
                        Image(firstSource, IntSize(2, 2))
                    }
                },
                MinecraftProfileFixture.create(),
                platform,
            )
        try {
            host.attach()
            val commands = host.frame(IntSize(2, 2)).drawCommands
            assertEquals(listOf(firstId), platform.imageCalls)
            assertEquals(3, commands.size)
            val background = commands[0] as DrawCommand.BlitImage
            val firstImage = commands[1] as DrawCommand.SampledImage
            val secondImage = commands[2] as DrawCommand.SampledImage
            assertSame(background.image, firstImage.image)
            assertSame(background.image, secondImage.image)
        } finally {
            host.close()
        }
        assertEquals(1, platform.closeCalls)
    }

    @Test
    fun retainedEvaluationsShareTheHostResourceResolverUntilTerminalClose() {
        val firstId = ResourceId("example", "textures/gui/deferred.png")
        val equalId = ResourceId("example", "textures/gui/deferred.png")
        val platform = FakeImagePlatform { _, call -> solidImage(0xFF102000.toInt() + call) }
        val state = VirtualListState<Int>()
        lateinit var evaluator: ComponentEvaluator
        var resourceImages: Any? = null
        val host =
            createMinecraftUiHost(
                ScreenDefinition("Deferred resource images") {
                    val runtime = activeComponentRuntime()
                    evaluator = runtime.retainEvaluator()
                    resourceImages = captureResourceImages(runtime)
                    Stack(modifier = Modifier.Empty.imageBackground(ImageSource.Resource(firstId))) {
                        VirtualList(listOf(0, 1), { item -> item }, state, IntSize(2, 4), rowHeight = 2) {
                            Image(ImageSource.Resource(equalId), IntSize(2, 2))
                        }
                    }
                },
                MinecraftProfileFixture.create(),
                platform,
            )
        try {
            host.attach()
            assertEquals(listOf(firstId), platform.imageCalls)
            val commands = host.frame(IntSize(2, 4)).drawCommands
            assertEquals(listOf(firstId), platform.imageCalls)
            val background = commands.filterIsInstance<DrawCommand.BlitImage>().single()
            val images = commands.filterIsInstance<DrawCommand.SampledImage>()
            assertEquals(2, images.size)
            assertTrue(images.all { command -> command.image === background.image })
        } finally {
            host.close()
        }

        assertResourceImagesReleased(checkNotNull(resourceImages))
        val released =
            assertThrows(IllegalStateException::class.java) {
                evaluator.evaluate { Image(ImageSource.Resource(firstId), IntSize(2, 2)) }
            }
        assertEquals("Minecraft resource image resolution is closed.", released.message)
        assertEquals(listOf(firstId), platform.imageCalls)
        assertEquals(1, platform.closeCalls)
    }

    @Test
    fun differentResourceIdentifiersResolveIndependentImages() {
        val firstId = ResourceId("example", "textures/gui/first.png")
        val secondId = ResourceId("example", "textures/gui/second.png")
        val firstImage = solidImage(0xFF102030.toInt())
        val secondImage = solidImage(0xFF405060.toInt())
        val platform =
            FakeImagePlatform { resource, _ ->
                when (resource) {
                    firstId -> firstImage
                    secondId -> secondImage
                    else -> error("Unexpected image resource: $resource")
                }
            }
        val host =
            createMinecraftUiHost(
                ScreenDefinition("Different resource images") {
                    Stack {
                        Image(ImageSource.Resource(firstId), IntSize(2, 2))
                        Image(ImageSource.Resource(secondId), IntSize(2, 2))
                    }
                },
                MinecraftProfileFixture.create(),
                platform,
            )
        try {
            host.attach()
            val commands = host.frame(IntSize(2, 2)).drawCommands.map { command -> command as DrawCommand.SampledImage }
            assertEquals(listOf(firstId, secondId), platform.imageCalls)
            assertEquals(2, commands.size)
            assertSame(firstImage, commands[0].image)
            assertSame(secondImage, commands[1].image)
            assertNotSame(commands[0].image, commands[1].image)
        } finally {
            host.close()
        }
    }

    @Test
    fun pixelImagesBypassPlatformResourceResolution() {
        val source = solidImage(0xFF778899.toInt())
        val platform = FakeImagePlatform { resource, _ -> error("Pixel images must not resolve a resource: $resource") }
        val host =
            createMinecraftUiHost(
                ScreenDefinition("Pixel images") {
                    Stack(modifier = Modifier.Empty.imageBackground(ImageSource.Pixels(source))) {
                        Image(ImageSource.Pixels(source), IntSize(2, 2))
                    }
                },
                MinecraftProfileFixture.create(),
                platform,
            )
        try {
            host.attach()
            val commands = host.frame(IntSize(2, 2)).drawCommands
            assertEquals(emptyList<ResourceId>(), platform.imageCalls)
            assertSame(source, (commands[0] as DrawCommand.BlitImage).image)
            assertSame(source, (commands[1] as DrawCommand.SampledImage).image)
        } finally {
            host.close()
        }
    }

    @Test
    fun failedResourceResolutionIsRetriedWithinTheSameEvaluation() {
        val id = ResourceId("example", "textures/gui/retry.png")
        val source = ImageSource.Resource(id)
        val expected = IllegalArgumentException("Injected image decoding failure.")
        val resolved = solidImage(0xFFABCDEF.toInt())
        val platform =
            FakeImagePlatform { _, call ->
                if (call == 1) throw expected
                resolved
            }
        var observed: Throwable? = null
        val host =
            createMinecraftUiHost(
                ScreenDefinition("Retry resource image") {
                    observed = runCatching { Modifier.Empty.imageBackground(source) }.exceptionOrNull()
                    Image(source, IntSize(2, 2))
                },
                MinecraftProfileFixture.create(),
                platform,
            )
        try {
            host.attach()
            assertSame(expected, observed)
            assertEquals(listOf(id, id), platform.imageCalls)
            val command = host.frame(IntSize(2, 2)).drawCommands.single() as DrawCommand.SampledImage
            assertSame(resolved, command.image)
        } finally {
            host.close()
        }
    }

    @Test
    fun resourceResolutionRejectsForeignThreadUseWithoutPublishingAnotherEntry() {
        val id = ResourceId("example", "textures/gui/owner.png")
        val source = ImageSource.Resource(id)
        val platform = FakeImagePlatform { _, _ -> solidImage(0xFF334455.toInt()) }
        var wrongThreadFailure: Throwable? = null
        var wrongThreadRunner: Thread? = null
        val host =
            createMinecraftUiHost(
                ScreenDefinition("Owner-thread resource image") {
                    val background = Modifier.Empty.imageBackground(source)
                    val runtime = activeComponentRuntime()
                    val task =
                        FutureTask<Throwable?> {
                            runCatching {
                                runtime.image(source, null, null, Modifier.Empty, null)
                            }.exceptionOrNull()
                        }
                    val runner = Thread(task)
                    wrongThreadRunner = runner
                    runner.start()
                    wrongThreadFailure = task.get(5, TimeUnit.SECONDS)
                    Stack(modifier = background) {
                        Image(source, IntSize(2, 2))
                    }
                },
                MinecraftProfileFixture.create(),
                platform,
            )
        try {
            host.attach()
            assertTrue(wrongThreadFailure is IllegalStateException)
            assertEquals(listOf(id), platform.imageCalls)
            val commands = host.frame(IntSize(2, 2)).drawCommands
            assertSame((commands[0] as DrawCommand.BlitImage).image, (commands[1] as DrawCommand.SampledImage).image)
        } finally {
            host.close()
            wrongThreadRunner?.join(5_000)
        }
    }

    @Test
    fun contentFailureClosesAndInvalidatesTheHostResourceResolver() {
        val id = ResourceId("example", "textures/gui/failure.png")
        val source = ImageSource.Resource(id)
        val expected = IllegalStateException("Injected content failure.")
        val platform = FakeImagePlatform { _, _ -> solidImage(0xFF667788.toInt()) }
        lateinit var evaluator: ComponentEvaluator
        var resourceImages: Any? = null
        val host =
            createMinecraftUiHost(
                ScreenDefinition("Failed resource image content") {
                    val runtime = activeComponentRuntime()
                    evaluator = runtime.retainEvaluator()
                    resourceImages = captureResourceImages(runtime)
                    Image(source, IntSize(2, 2))
                    throw expected
                },
                MinecraftProfileFixture.create(),
                platform,
            )

        assertSame(expected, assertThrows(IllegalStateException::class.java, host::attach))
        assertResourceImagesReleased(checkNotNull(resourceImages))
        val released =
            assertThrows(IllegalStateException::class.java) {
                evaluator.evaluate { Image(source, IntSize(2, 2)) }
            }
        assertEquals("Minecraft resource image resolution is closed.", released.message)
        assertEquals(listOf(id), platform.imageCalls)
        assertEquals(1, platform.closeCalls)
        host.close()
        assertEquals(1, platform.closeCalls)
    }

    @Test
    fun aNewHostResolvesTheSameResourceAgainAndMayObserveDifferentPixels() {
        val id = ResourceId("example", "textures/gui/host.png")
        val source = ImageSource.Resource(id)
        val profile = MinecraftProfileFixture.create()
        val firstResolved = solidImage(0xFF112233.toInt())
        val secondResolved = solidImage(0xFF445566.toInt())
        val firstPlatform = FakeImagePlatform { _, _ -> firstResolved }
        val firstHost = createMinecraftUiHost(ScreenDefinition("First image host") { Image(source) }, profile, firstPlatform)
        val firstCommand =
            try {
                firstHost.attach()
                firstHost.frame(IntSize(2, 2)).drawCommands.single() as DrawCommand.SampledImage
            } finally {
                firstHost.close()
            }

        val secondPlatform = FakeImagePlatform { _, _ -> secondResolved }
        val secondHost = createMinecraftUiHost(ScreenDefinition("Second image host") { Image(source) }, profile, secondPlatform)
        val secondCommand =
            try {
                secondHost.attach()
                secondHost.frame(IntSize(2, 2)).drawCommands.single() as DrawCommand.SampledImage
            } finally {
                secondHost.close()
            }

        assertEquals(listOf(id), firstPlatform.imageCalls)
        assertEquals(listOf(id), secondPlatform.imageCalls)
        assertEquals(1, firstPlatform.closeCalls)
        assertEquals(1, secondPlatform.closeCalls)
        assertSame(firstResolved, firstCommand.image)
        assertSame(secondResolved, secondCommand.image)
        assertNotSame(firstCommand.image, secondCommand.image)
    }

    @Test
    fun imageMapsOnlyTheRequestedSourceRegionAndValidatesBounds() {
        val source = createDrawImage(IntSize(3, 2), IntArray(6) { index -> 0xFF000000.toInt() or index })
        val host =
            createMinecraftUiHost(
                ScreenDefinition("Image region") {
                    Image(ImageSource.Pixels(source), IntRect(1, 0, 3, 2), IntSize(4, 2))
                },
                MinecraftProfileFixture.create(),
            )
        try {
            host.attach()
            val frame = host.frame(IntSize(4, 2))
            val command = frame.drawCommands.single() as DrawCommand.SampledImage
            assertSame(source, command.image)
            assertEquals(FloatRect(1f, 0f, 3f, 2f), command.source)
            assertEquals(FloatRect(0f, 0f, 4f, 2f), command.destination)
            assertEquals(0f, command.alphaCutoff)
            val rendered = rasterizeHeadless(frame.drawCommands, IntSize(4, 2))
            assertEquals(source.argbAt(1, 0), rendered.argbAt(0, 0))
            assertEquals(source.argbAt(2, 1), rendered.argbAt(3, 1))
        } finally {
            host.close()
        }

        listOf(
            IntRect(0, 0, 0, 1),
            IntRect(-1, 0, 1, 1),
            IntRect(0, 0, 4, 1),
            IntRect(0, 0, 1, 3),
        ).forEach { invalid ->
            val invalidHost =
                createMinecraftUiHost(
                    ScreenDefinition("Invalid image region") {
                        Image(ImageSource.Pixels(source), invalid)
                    },
                    MinecraftProfileFixture.create(),
                )
            assertThrows(IllegalArgumentException::class.java) { invalidHost.attach() }
            invalidHost.close()
        }
    }

    @Test
    fun backgroundSupportsStretchAndRowMajorTiles() {
        val source = createDrawImage(IntSize(2, 2), IntArray(4) { 0xFF123456.toInt() })
        val stretched = hostWithBackground(source, ImageScale.Stretch)
        val tiled = hostWithBackground(source, ImageScale.Tile)
        try {
            stretched.attach()
            val stretchCommand = stretched.frame(IntSize(5, 3)).drawCommands.single() as DrawCommand.BlitImage
            assertEquals(IntRect(0, 0, 5, 3), stretchCommand.destination)

            tiled.attach()
            val tileCommands = tiled.frame(IntSize(5, 3)).drawCommands.map { command -> command as DrawCommand.BlitImage }
            assertEquals(
                listOf(
                    IntRect(0, 0, 2, 2),
                    IntRect(2, 0, 4, 2),
                    IntRect(4, 0, 5, 2),
                    IntRect(0, 2, 2, 3),
                    IntRect(2, 2, 4, 3),
                    IntRect(4, 2, 5, 3),
                ),
                tileCommands.map { command -> command.destination },
            )
            assertEquals(
                listOf(
                    IntRect(0, 0, 2, 2),
                    IntRect(0, 0, 2, 2),
                    IntRect(0, 0, 1, 2),
                    IntRect(0, 0, 2, 1),
                    IntRect(0, 0, 2, 1),
                    IntRect(0, 0, 1, 1),
                ),
                tileCommands.map { command -> command.source },
            )
            tileCommands.forEach { command -> assertSame(source, command.image) }
        } finally {
            stretched.close()
            tiled.close()
        }
    }

    @Test
    fun nineSliceUsesNativeVerticalOrderAndClippedTiles() {
        val source =
            createDrawImage(
                IntSize(4, 4),
                IntArray(16) { index -> 0xFF000000.toInt() or index },
            )
        val host = hostWithNineSliceBackground(source, Insets.all(1), NineSliceCenterMode.Tiled)
        try {
            host.attach()
            val commands = host.frame(IntSize(4, 7)).drawCommands.map { command -> command as DrawCommand.BlitImage }
            assertEquals(
                listOf(
                    IntRect(0, 0, 4, 1),
                    IntRect(0, 1, 4, 3),
                    IntRect(0, 3, 4, 5),
                    IntRect(0, 5, 4, 6),
                    IntRect(0, 6, 4, 7),
                ),
                commands.map { command -> command.destination },
            )
            assertEquals(
                listOf(
                    IntRect(0, 0, 4, 1),
                    IntRect(0, 1, 4, 3),
                    IntRect(0, 1, 4, 3),
                    IntRect(0, 1, 4, 2),
                    IntRect(0, 3, 4, 4),
                ),
                commands.map { command -> command.source },
            )
            commands.forEach { command -> assertSame(source, command.image) }
            val rendered = rasterizeHeadless(commands, IntSize(4, 7))
            assertEquals(source.argbAt(2, 0), rendered.argbAt(2, 0))
            assertEquals(source.argbAt(2, 1), rendered.argbAt(2, 5))
            assertEquals(source.argbAt(2, 3), rendered.argbAt(2, 6))
        } finally {
            host.close()
        }
    }

    @Test
    fun nineSliceUsesRowMajorGridAndValidatesSourceCenters() {
        val source = createDrawImage(IntSize(3, 3), IntArray(9) { index -> 0xFF101010.toInt() + index })
        val host = hostWithNineSliceBackground(source, Insets.all(1), NineSliceCenterMode.Stretched)
        try {
            host.attach()
            val commands = host.frame(IntSize(5, 5)).drawCommands.map { command -> command as DrawCommand.BlitImage }
            assertEquals(9, commands.size)
            assertEquals(
                listOf(
                    IntRect(0, 0, 1, 1),
                    IntRect(1, 0, 4, 1),
                    IntRect(4, 0, 5, 1),
                    IntRect(0, 1, 1, 4),
                    IntRect(1, 1, 4, 4),
                    IntRect(4, 1, 5, 4),
                    IntRect(0, 4, 1, 5),
                    IntRect(1, 4, 4, 5),
                    IntRect(4, 4, 5, 5),
                ),
                commands.map { command -> command.destination },
            )
        } finally {
            host.close()
        }

        val invalid = hostWithNineSliceBackground(source, Insets(left = 1, right = 2), NineSliceCenterMode.Tiled)
        assertThrows(IllegalArgumentException::class.java) { invalid.attach() }
        invalid.close()
    }

    private fun hostWithBackground(
        source: DrawImage,
        scale: ImageScale,
    ): MinecraftUiHost =
        createMinecraftUiHost(
            ScreenDefinition("Background") {
                Stack(modifier = Modifier.Empty.imageBackground(ImageSource.Pixels(source), scale)) {}
            },
            MinecraftProfileFixture.create(),
        )

    private fun hostWithNineSliceBackground(
        source: DrawImage,
        border: Insets,
        centerMode: NineSliceCenterMode,
    ): MinecraftUiHost =
        createMinecraftUiHost(
            ScreenDefinition("Nine slice") {
                Stack(modifier = Modifier.Empty.imageBackground(ImageSource.Pixels(source), border, centerMode)) {}
            },
            MinecraftProfileFixture.create(),
        )

    private fun solidImage(color: Int): DrawImage = createDrawImage(IntSize(2, 2), IntArray(4) { color })

    private fun activeComponentRuntime(): ComponentRuntime {
        val activeField = ComponentRuntimeBridge::class.java.getDeclaredField("active")
        check(activeField.trySetAccessible()) { "The active component runtime is inaccessible." }
        val active = checkNotNull(activeField.get(ComponentRuntimeBridge) as? ThreadLocal<*>)
        return checkNotNull(active.get() as? ComponentRuntime)
    }

    private fun captureResourceImages(runtime: ComponentRuntime): Any {
        val field = runtime.javaClass.getDeclaredField("resourceImages")
        check(field.trySetAccessible()) { "The Minecraft resource image resolver is inaccessible." }
        return checkNotNull(field.get(runtime))
    }

    private fun assertResourceImagesReleased(resourceImages: Any) {
        val imagesField = resourceImages.javaClass.getDeclaredField("images")
        check(imagesField.trySetAccessible()) { "The Minecraft resource image cache is inaccessible." }
        assertTrue(checkNotNull(imagesField.get(resourceImages) as? Map<*, *>).isEmpty())
        val closedField = resourceImages.javaClass.getDeclaredField("closed")
        check(closedField.trySetAccessible()) { "The Minecraft resource image lifecycle is inaccessible." }
        assertEquals(true, closedField.get(resourceImages))
    }

    private class FakeImagePlatform(
        private val resolver: (ResourceId, Int) -> DrawImage,
    ) : MinecraftUiPlatform {
        private val ownerThread = Thread.currentThread()
        private var closed = false
        val imageCalls: MutableList<ResourceId> = ArrayList()
        var closeCalls: Int = 0
            private set

        override fun inventorySlot(binding: SlotBinding): MinecraftInventorySlotBinding = error("This Image fixture has no inventory Slots: $binding")

        override fun image(resource: ResourceId): DrawImage {
            requireUsable()
            imageCalls += resource
            return resolver(resource, imageCalls.size)
        }

        override fun playerSkin(source: PlayerSkinSource): MinecraftPlayerSkinBinding = error("This Image fixture has no player skins: $source")

        override fun refresh() {
            requireUsable()
        }

        override fun close() {
            checkOwner()
            if (closed) return
            closed = true
            closeCalls += 1
        }

        private fun requireUsable() {
            checkOwner()
            check(closed.not()) { "Image platform is closed." }
        }

        private fun checkOwner() {
            check(Thread.currentThread() === ownerThread) { "Image platform requires its creator thread." }
        }
    }
}
