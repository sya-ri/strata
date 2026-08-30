package dev.s7a.strata.runtime.minecraft.canvas

import dev.s7a.strata.component.Canvas
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.PlatformDrawCommand
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.FrameTime
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.minecraft.MinecraftProfileFixture
import dev.s7a.strata.runtime.minecraft.createMinecraftUiHost
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.runtime.spi.RuntimeUiFrame
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * Verifies immutable native presentation receipts and explicit portable capture without any live GPU resolution.
 * Deterministic mock capture inputs test the common contract; real adapter pixel correctness requires loaded GPU tests.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class NativeCanvasPresentationTest {
    @Test
    fun uncommittedCanvasRejectsCaptureEvenWithPortableCommandsAndOtherMatchingSnapshots() {
        NativeCanvasFixture().use { fixture ->
            val first = fixture.tree()
            val second = fixture.tree()
            val unavailable = fixture.producers[1]
            unavailable.available = false
            val background = DrawCommand.FillRectangle(IntRect(0, 0, 2, 2), ArgbColor(0xFF000000.toInt()))
            val commands = listOf(background) + fixture.frame(first) + fixture.frame(second)
            val incomplete = fixture.device.prepare(commands, FrameTime(1L), 1)
            assertEquals(2, incomplete.drawCommands.size)
            assertEquals(background, incomplete.drawCommands.first())
            assertTrue(incomplete.drawCommands.last() is DrawCommand.Platform)
            assertThrows(IllegalStateException::class.java) { incomplete.capture() }
            fixture.submit(incomplete)

            unavailable.available = true
            val complete = fixture.device.prepare(commands, FrameTime(2L), 1)
            assertEquals(3, complete.capture().size)
            assertThrows(IllegalStateException::class.java) { incomplete.capture() }
            assertEquals(2, unavailable.captureCalls)
            fixture.device.cancel(complete)
        }
    }

    @Test
    fun portableFramesIncludingAnEmptyFrameCaptureWithoutInventingNativeReadiness() {
        NativeCanvasFixture().use { fixture ->
            val frames: List<List<DrawCommand>> =
                listOf(
                    emptyList(),
                    listOf(DrawCommand.FillRectangle(IntRect(0, 0, 1, 1), ArgbColor(0xFF123456.toInt()))),
                )
            frames.forEach { commands ->
                val presentation = fixture.device.prepare(commands, FrameTime(1L), 1)
                assertEquals(commands, presentation.capture())
                fixture.device.cancel(presentation)
            }
            assertEquals(0, fixture.driver.allocationAttempts)
            assertTrue(fixture.driver.fences.isEmpty())
        }
    }

    @Test
    fun captureKeepsDistinctPhysicalTexelsWithinOneLogicalDestinationPixel() {
        val colors = intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFFFFFF00.toInt())
        val snapshot = createDrawImage(IntSize(2, 2), colors)
        val token = NativeCanvasToken(1L, 1L, 1L, snapshot.size)
        val presentation =
            NativeCanvasPresentation(
                1L,
                1L,
                listOf(DrawCommand.Platform(token, IntRect(0, 0, 1, 1))),
                listOf(NativeCanvasSnapshot(token, snapshot)),
            )

        val captured = presentation.capture()
        assertArrayEquals(colors, rasterizeHeadless(captured, IntSize(1, 1), scale = 2).copyArgb())
        assertTrue(presentation.drawCommands.single() is DrawCommand.Platform)
    }

    @Test
    fun capturePreservesPortableNativeClipAndOverlayOrderWithKnownStraightAlphaPixels() {
        NativeCanvasFixture().use { fixture ->
            val tree = fixture.tree()
            fixture.producers.single().color = 0x80FF0000.toInt()
            val native = fixture.frame(tree).single()
            val background = DrawCommand.FillRectangle(IntRect(0, 0, 2, 2), ArgbColor(0xFF000000.toInt()))
            val clip = DrawCommand.PushClip(IntRect(0, 0, 1, 2))
            val overlay = DrawCommand.FillRectangle(IntRect(0, 1, 1, 2), ArgbColor(0xFF00FF00.toInt()))
            val commands = listOf(background, clip, native, DrawCommand.PopClip, overlay)
            val presentation = fixture.device.prepare(commands, FrameTime(9L), scale = 2)
            val capture = presentation.capture()
            assertEquals(background, capture[0])
            assertEquals(clip, capture[1])
            val pixels = capture[2] as DrawCommand.BlitImagePixels
            assertEquals(IntSize(4, 4), pixels.image.size)
            assertEquals(IntRect(0, 0, 4, 4), pixels.source)
            assertEquals(IntRect(0, 0, 2, 2), pixels.destination)
            assertEquals(DrawCommand.PopClip, capture[3])
            assertEquals(overlay, capture[4])
            val image = rasterizeHeadless(capture, IntSize(2, 2))
            assertEquals(0xFF800000.toInt(), image.argbAt(0, 0))
            assertEquals(0xFF000000.toInt(), image.argbAt(1, 0))
            assertEquals(0xFF00FF00.toInt(), image.argbAt(0, 1))
            assertEquals(0xFF000000.toInt(), image.argbAt(1, 1))
            assertEquals(native, commands[2])
            assertThrows(IllegalArgumentException::class.java) { rasterizeHeadless(commands, IntSize(2, 2)) }
            assertThrows(IllegalArgumentException::class.java) { rasterizeHeadless(presentation.drawCommands, IntSize(2, 2)) }
            assertEquals(1, fixture.producers.single().captureCalls)
            fixture.device.cancel(presentation)
        }
    }

    @Test
    fun retainedOldFramesAndPresentationsDoNotOwnAnyLiveTargetAfterCleanup() {
        NativeCanvasFixture().use { fixture ->
            val source = fixture.source()
            createMinecraftUiHost(
                ScreenDefinition("Retained native Canvas frame") { Canvas(source, IntSize(2, 2)) },
                MinecraftProfileFixture.create(),
            ).use { host ->
                host.attach()
                val original: RuntimeUiFrame = host.frame(IntSize(2, 2))
                val request = (original.drawCommands.single() as DrawCommand.Platform).command
                val semantics = original.semantics
                val mutableCommands = original.drawCommands.toMutableList()
                val presentation = fixture.device.prepare(mutableCommands, FrameTime(1L), 1)
                mutableCommands.clear()
                val nativeToken = token(presentation)
                val image = (presentation.capture().single() as DrawCommand.BlitImagePixels).image
                fixture.submit(presentation)
                host.close()
                assertEquals(1, fixture.device.retainedTargetCount())
                assertEquals(
                    0,
                    fixture.driver.targets
                        .single()
                        .closeCalls,
                )
                assertEquals(0, fixture.producers.single().closeCalls)
                fixture.driver.signalAll()
                fixture.device.poll()
                assertEquals(0, fixture.device.retainedTargetCount())
                assertEquals(
                    1,
                    fixture.driver.targets
                        .single()
                        .closeCalls,
                )
                assertEquals(1, fixture.producers.single().closeCalls)
                assertEquals(IntSize(2, 2), original.size)
                assertSame(semantics, original.semantics)
                assertSame(request, (original.drawCommands.single() as DrawCommand.Platform).command)
                assertEquals(1, presentation.drawCommands.size)
                assertSame(image, (presentation.capture().single() as DrawCommand.BlitImagePixels).image)
                assertEquals(0xFF336699.toInt(), image.argbAt(0, 0))
                listOf(nativeToken, request).forEach { payload ->
                    val fields = payload.javaClass.declaredFields.filter { Modifier.isStatic(it.modifiers).not() }
                    assertTrue(fields.all { Modifier.isFinal(it.modifiers) })
                    assertTrue(fields.all { it.type == Long::class.javaPrimitiveType || it.type == IntSize::class.java })
                }
                assertThrows(UnsupportedOperationException::class.java) { (original.drawCommands as MutableList).clear() }
                assertThrows(UnsupportedOperationException::class.java) { (presentation.drawCommands as MutableList).clear() }
                assertThrows(UnsupportedOperationException::class.java) { (presentation.capture() as MutableList).clear() }
                assertThrows(IllegalStateException::class.java) { fixture.device.target(presentation, nativeToken) }
            }
        }
    }

    @Test
    fun missingSnapshotFailsExplicitlyWithoutRecapturingOrLookingUpLiveTargets() {
        NativeCanvasFixture().use { fixture ->
            val tree = fixture.tree()
            val producer = fixture.producers.single()
            producer.snapshotMode = NativeCanvasFixture.SnapshotMode.Missing
            val presentation = fixture.prepare(tree)
            assertEquals(1, presentation.drawCommands.size)
            assertThrows(IllegalStateException::class.java) { presentation.capture() }
            assertThrows(IllegalArgumentException::class.java) { rasterizeHeadless(presentation.drawCommands, IntSize(2, 2)) }
            assertEquals(1, producer.captureCalls)
            assertEquals(
                1,
                producer.captures
                    .single()
                    .renderedTargets.size,
            )
            assertEquals(2, fixture.driver.fences.size)
            fixture.device.cancel(presentation)
        }
    }

    @Test
    fun snapshotCaptureIsThreadSafeWhileNativeResolutionStillRequiresTheDeviceThread() {
        NativeCanvasFixture().use { fixture ->
            val tree = fixture.tree()
            val presentation = fixture.prepare(tree)
            fixture.device.queue(presentation)
            val task =
                FutureTask {
                    val commands = presentation.capture()
                    val failure = runCatching { fixture.device.target(presentation, token(presentation)) }.exceptionOrNull()
                    commands to failure
                }
            Thread(task).start()
            val (commands, failure) = task.get(5, TimeUnit.SECONDS)
            assertEquals(0xFF336699.toInt(), rasterizeHeadless(commands, IntSize(2, 2)).argbAt(0, 0))
            assertTrue(failure is IllegalStateException)
            assertEquals(1, fixture.producers.single().captureCalls)
            fixture.device.consumed()
        }
    }

    @Test
    fun mismatchedGenerationExtentOrDuplicateReceiptCannotProducePortableCommands() {
        NativeCanvasFixture().use { fixture ->
            val tree = fixture.tree()
            val presentation = fixture.prepare(tree)
            val nativeToken = token(presentation)
            val pixels =
                presentation
                    .capture()
                    .filterIsInstance<DrawCommand.BlitImagePixels>()
                    .single()
                    .image
            val differentGeneration =
                NativeCanvasToken(nativeToken.deviceId, nativeToken.attachmentId, nativeToken.generation + 1, nativeToken.physicalSize)
            val copiedIdentity =
                NativeCanvasToken(nativeToken.deviceId, nativeToken.attachmentId, nativeToken.generation, nativeToken.physicalSize)
            val invalidExtent = createDrawImage(IntSize(1, 1), intArrayOf(0xFFFF0000.toInt()))
            val valid = NativeCanvasSnapshot(nativeToken, pixels)
            val invalidReceipts =
                listOf(
                    emptyList(),
                    listOf(NativeCanvasSnapshot(differentGeneration, pixels)),
                    listOf(NativeCanvasSnapshot(copiedIdentity, pixels)),
                    listOf(NativeCanvasSnapshot(nativeToken, invalidExtent)),
                    listOf(valid, valid),
                )
            invalidReceipts.forEach { receipts ->
                val invalid = NativeCanvasPresentation(presentation.deviceId, presentation.batchId, presentation.drawCommands, receipts)
                assertThrows(IllegalStateException::class.java) { invalid.capture() }
            }
            assertSame(
                pixels,
                presentation
                    .capture()
                    .filterIsInstance<DrawCommand.BlitImagePixels>()
                    .single()
                    .image,
            )
            fixture.device.cancel(presentation)
        }
    }

    @Test
    fun anotherPlatformPayloadFailsTheWholeCaptureWithoutReturningAPortablePrefix() {
        NativeCanvasFixture().use { fixture ->
            val tree = fixture.tree()
            val presentation = fixture.prepare(tree)
            val nativeToken = token(presentation)
            val pixels =
                presentation
                    .capture()
                    .filterIsInstance<DrawCommand.BlitImagePixels>()
                    .single()
                    .image
            val mixed =
                NativeCanvasPresentation(
                    presentation.deviceId,
                    presentation.batchId,
                    presentation.drawCommands + DrawCommand.Platform(OtherPlatformCommand, IntRect(0, 0, 1, 1)),
                    listOf(NativeCanvasSnapshot(nativeToken, pixels)),
                )
            assertThrows(IllegalStateException::class.java) { mixed.capture() }
            assertSame(
                pixels,
                presentation
                    .capture()
                    .filterIsInstance<DrawCommand.BlitImagePixels>()
                    .single()
                    .image,
            )
            fixture.device.cancel(presentation)
        }
    }

    @Test
    fun producerSnapshotExtentFailureCannotRelabelPreviouslyCommittedPixels() {
        NativeCanvasFixture().use { fixture ->
            val tree = fixture.tree()
            val producer = fixture.producers.single()
            val first = fixture.prepare(tree)
            fixture.submit(first)
            val committedToken = token(first)
            val committedImage =
                first
                    .capture()
                    .filterIsInstance<DrawCommand.BlitImagePixels>()
                    .single()
                    .image
            producer.snapshotMode = NativeCanvasFixture.SnapshotMode.WrongExtent
            assertThrows(IllegalStateException::class.java) { fixture.prepare(tree) }
            assertEquals(0, producer.captures.last().closeCalls)
            producer.available = false
            val preserved = fixture.prepare(tree)
            assertSame(committedToken, token(preserved))
            assertSame(
                committedImage,
                preserved
                    .capture()
                    .filterIsInstance<DrawCommand.BlitImagePixels>()
                    .single()
                    .image,
            )
            assertEquals(0xFF336699.toInt(), committedImage.argbAt(0, 0))
            fixture.device.cancel(preserved)
        }
    }

    private fun token(presentation: NativeCanvasPresentation): NativeCanvasToken =
        presentation.drawCommands
            .filterIsInstance<DrawCommand.Platform>()
            .single()
            .command as NativeCanvasToken

    private data object OtherPlatformCommand : PlatformDrawCommand
}
