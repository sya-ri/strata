@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.component.PlayerSkinSource
import dev.s7a.strata.component.SlotBinding
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.Text
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.element.Element
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.FocusEvent
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.KeyCode
import dev.s7a.strata.input.KeyboardEvent
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.input.TextInputEvent
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.initialFocus
import dev.s7a.strata.modifier.menuBackground
import dev.s7a.strata.modifier.onCapturedPointerEvent
import dev.s7a.strata.modifier.onCharacterInput
import dev.s7a.strata.modifier.onFocusChanged
import dev.s7a.strata.modifier.onKeyPress
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.runtime.minecraft.font.FontTestBackend
import dev.s7a.strata.runtime.minecraft.font.FontTestResources
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontBackendFactory
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.UiText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * Verifies fixed-viewport, lifecycle, input, threading, and failure delegation through the Minecraft host.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class MinecraftUiHostTest {
    @Test
    fun focusedInputIsFirstFrameGatedAndReacquiredAfterTransientDetach() {
        val focus = ArrayList<FocusEvent>()
        val keys = ArrayList<KeyboardEvent.Press>()
        val characters = ArrayList<TextInputEvent.Character>()
        val host =
            host {
                evaluateComponentTree {
                    Spacer(
                        modifier =
                            Modifier.Empty
                                .size(4, 3)
                                .initialFocus()
                                .onKeyPress { event ->
                                    keys += event
                                    InputResult.Consumed
                                }.onCharacterInput { event ->
                                    characters += event
                                    InputResult.Consumed
                                }.onFocusChanged { event -> focus += event },
                    )
                }
            }
        val key = KeyboardEvent.Press(KeyCode.Enter, 12)
        val character = TextInputEvent.Character('a'.code)

        host.attach()
        assertEquals(InputResult.Ignored, host.dispatchKeyboard(key))
        assertEquals(InputResult.Ignored, host.dispatchTextInput(character))
        host.frame(IntSize(4, 3))
        assertEquals(listOf(FocusEvent.Gained), focus)
        assertEquals(InputResult.Consumed, host.dispatchKeyboard(key))
        assertEquals(InputResult.Consumed, host.dispatchTextInput(character))
        assertEquals(listOf(key), keys)
        assertEquals(listOf(character), characters)

        host.detach()
        assertEquals(listOf(FocusEvent.Gained, FocusEvent.Lost), focus)
        host.attach()
        assertEquals(InputResult.Ignored, host.dispatchKeyboard(key))
        host.frame(IntSize(4, 3))
        assertEquals(listOf(FocusEvent.Gained, FocusEvent.Lost, FocusEvent.Gained), focus)
        host.close()
    }

    @Test
    fun nativeInputResetCancelsCaptureAndFocusWithoutDetachingTheHost() {
        val cancellations = ArrayList<PointerButton>()
        val focus = ArrayList<FocusEvent>()
        val host =
            host {
                evaluateComponentTree {
                    Spacer(
                        modifier =
                            Modifier.Empty
                                .size(4, 3)
                                .initialFocus()
                                .onFocusChanged(focus::add)
                                .onKeyPress { _ -> InputResult.Consumed }
                                .onCapturedPointerEvent(cancellations::add) { _, _ -> InputResult.Consumed },
                    )
                }
            }
        assertThrows(IllegalStateException::class.java) { host.resetInputState() }
        host.attach()
        host.resetInputState()
        val first = host.frame(IntSize(4, 3))
        val key = KeyboardEvent.Press(KeyCode.Enter, 12)
        host.dispatchPointer(PointerEvent.Press(IntOffset(1, 1), PointerButton.Primary))
        assertEquals(InputResult.Consumed, host.dispatchPointer(PointerEvent.Move(IntOffset(10, 10))))

        host.resetInputState()
        host.resetInputState()
        assertEquals(listOf(PointerButton.Primary), cancellations)
        assertEquals(listOf(FocusEvent.Gained, FocusEvent.Lost), focus)
        assertEquals(InputResult.Ignored, host.dispatchKeyboard(key))
        assertEquals(InputResult.Ignored, host.dispatchPointer(PointerEvent.Move(IntOffset(10, 10))))
        assertSame(first, host.frame(IntSize(4, 3)))

        host.dispatchPointer(PointerEvent.Press(IntOffset(1, 1), PointerButton.Primary))
        assertEquals(InputResult.Consumed, host.dispatchKeyboard(key))
        host.detach()
        assertThrows(IllegalStateException::class.java) { host.resetInputState() }
        host.close()
        assertEquals(listOf(PointerButton.Primary, PointerButton.Primary), cancellations)
    }

    @Test
    fun fixedViewportRetainedInvalidationAndTransientReattachRemainCoherent() {
        val probe = MinecraftHostProbe()
        var contentCalls = 0
        val host =
            host {
                contentCalls += 1
                probe.element()
            }

        assertThrows(IllegalStateException::class.java) { host.detach() }
        assertThrows(IllegalStateException::class.java) { host.frame(IntSize(4, 3)) }
        assertThrows(IllegalStateException::class.java) { host.dispatchPointer(PointerEvent.Move(IntOffset.Zero)) }
        host.attach()
        assertEquals(InputResult.Ignored, host.dispatchPointer(PointerEvent.Move(IntOffset.Zero)))

        val first = host.frame(IntSize(4, 3))
        val retained = probe.nodes.single()
        assertEquals(IntSize(4, 3), first.size)
        assertEquals(listOf(Constraints.fixed(4, 3)), probe.constraints)
        assertEquals(InputResult.Consumed, host.dispatchPointer(PointerEvent.Move(IntOffset.Zero)))
        assertThrows(UnsupportedOperationException::class.java) { (first.drawCommands as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (first.semantics as MutableList).clear() }

        retained.invalidatePaint()
        val paintsBeforeReattach = probe.paintCalls
        host.frame(IntSize(4, 3))
        assertEquals(paintsBeforeReattach + 1, probe.paintCalls)
        assertEquals(1, contentCalls)

        val constraintsBeforeReattach = probe.constraints.size
        val paintsAfterInvalidation = probe.paintCalls
        host.detach()
        host.attach()
        assertSame(retained, probe.nodes.single())
        assertEquals(
            listOf(MinecraftHostProbe.LifecycleStage.Attach),
            probe.lifecycle,
        )
        assertEquals(InputResult.Ignored, host.dispatchPointer(PointerEvent.Move(IntOffset.Zero)))
        host.frame(IntSize(4, 3))
        assertEquals(constraintsBeforeReattach, probe.constraints.size)
        assertEquals(paintsAfterInvalidation, probe.paintCalls)
        assertEquals(InputResult.Consumed, host.dispatchPointer(PointerEvent.Move(IntOffset.Zero)))

        host.close()
        host.close()
        assertEquals(
            listOf(
                MinecraftHostProbe.LifecycleStage.Attach,
                MinecraftHostProbe.LifecycleStage.Detach,
                MinecraftHostProbe.LifecycleStage.Dispose,
            ),
            probe.lifecycle,
        )
        assertThrows(IllegalStateException::class.java) { host.attach() }
        assertThrows(IllegalStateException::class.java) { host.detach() }
        assertThrows(IllegalStateException::class.java) { host.frame(IntSize(4, 3)) }
        assertThrows(IllegalStateException::class.java) { host.dispatchPointer(PointerEvent.Move(IntOffset.Zero)) }
    }

    @Test
    fun zeroAxesUseExactFixedConstraints() {
        val probe = MinecraftHostProbe()
        val host = host { probe.element() }
        host.attach()

        val zero = host.frame(IntSize.Zero)
        val zeroWidth = host.frame(IntSize(0, 3))
        val zeroHeight = host.frame(IntSize(4, 0))

        assertEquals(IntSize.Zero, zero.size)
        assertEquals(IntSize(0, 3), zeroWidth.size)
        assertEquals(IntSize(4, 0), zeroHeight.size)
        assertEquals(
            listOf(
                Constraints.fixed(0, 0),
                Constraints.fixed(0, 3),
                Constraints.fixed(4, 0),
            ),
            probe.constraints,
        )
        host.close()
    }

    @Test
    fun everyOperationRejectsTheWrongThreadIncludingRepeatedClose() {
        val host = host { MinecraftHostProbe().element() }
        assertTrue(wrongThread { host.attach() } is IllegalStateException)
        assertTrue(wrongThread { host.title } is IllegalStateException)
        assertTrue(wrongThread { host.pausesGame } is IllegalStateException)
        assertTrue(wrongThread { host.textInputFocus } is IllegalStateException)
        host.attach()
        assertTrue(wrongThread { host.detach() } is IllegalStateException)
        assertTrue(wrongThread { host.frame(IntSize(2, 1)) } is IllegalStateException)
        assertTrue(wrongThread { host.dispatchPointer(PointerEvent.Move(IntOffset.Zero)) } is IllegalStateException)
        assertTrue(wrongThread { host.dispatchKeyboard(KeyboardEvent.Press(KeyCode.Enter, 0)) } is IllegalStateException)
        assertTrue(wrongThread { host.dispatchTextInput(TextInputEvent.Character('a'.code)) } is IllegalStateException)
        assertTrue(wrongThread { host.resetInputState() } is IllegalStateException)
        assertTrue(wrongThread { host.close() } is IllegalStateException)
        host.close()
        assertTrue(wrongThread { host.close() } is IllegalStateException)
    }

    @Test
    fun evaluatorOwnershipClearsBeforeCloseAndAfterEvaluationOrFailure() {
        val beforeAttach = host { MinecraftHostProbe().element() }
        assertTrue(readPrivateField(beforeAttach, "evaluator") != null)
        beforeAttach.close()
        assertNull(readPrivateField(beforeAttach, "evaluator"))

        val afterAttach = host { MinecraftHostProbe().element() }
        afterAttach.attach()
        assertNull(readPrivateField(afterAttach, "evaluator"))
        afterAttach.close()

        val failure = IllegalStateException("content")
        val failed = host { throw failure }
        assertSame(failure, assertThrows(IllegalStateException::class.java) { failed.attach() })
        assertNull(readPrivateField(failed, "evaluator"))
        assertNull(readPrivateField(failed, "metadata"))
        failed.close()
    }

    @Test
    fun missingOrFailedFontFactoryLeavesTheDefinitionAndPlatformAvailableForRetry() {
        val profile = resourceFontProfile()
        var evaluations = 0
        val definition =
            ScreenDefinition("font factory") {
                evaluations++
                Text("日")
            }
        val expected = IllegalStateException("font factory")
        var platformCloses = 0
        val platform = fontPlatform { platformCloses++ }
        val missingBackend = assertThrows(IllegalArgumentException::class.java) { createMinecraftUiHost(definition, profile) }
        val missingPlatformBackend = assertThrows(IllegalArgumentException::class.java) { createMinecraftUiHost(definition, profile, platform) }
        assertEquals("Resource fonts require a CPU font backend factory.", missingBackend.message)
        assertEquals("Resource fonts require a CPU font backend factory.", missingPlatformBackend.message)
        assertEquals(0, evaluations)
        assertEquals(0, platformCloses)
        val failure =
            assertThrows(IllegalStateException::class.java) {
                createMinecraftUiHost(definition, profile, platform, MinecraftFontBackendFactory { throw expected })
            }
        assertSame(expected, failure)
        assertEquals(0, evaluations)
        assertEquals(0, platformCloses)

        val backend = resourceFontBackend()
        createMinecraftUiHost(definition, profile, platform, MinecraftFontBackendFactory { backend }).use { host ->
            host.attach()
            assertEquals(
                2,
                host
                    .frame(IntSize(5, 9))
                    .drawCommands
                    .filterIsInstance<DrawCommand.SampledImage>()
                    .size,
            )
        }
        assertEquals(1, evaluations)
        assertEquals(1, backend.decodeCalls)
        assertEquals(1, backend.closeCalls)
        assertEquals(1, platformCloses)
    }

    @Test
    fun contentFailureClosesTheFontBackendBeforePlatformAndPreservesCleanupFailures() {
        val expected = IllegalArgumentException("font content")
        val fontCleanup = IllegalStateException("font cleanup")
        val platformCleanup = IllegalStateException("platform cleanup")
        val backend = resourceFontBackend(closeFailure = fontCleanup)
        var platformCloses = 0
        val platform =
            fontPlatform {
                platformCloses++
                throw platformCleanup
            }
        val host =
            createMinecraftUiHost(
                ScreenDefinition("font content") {
                    Text("日")
                    throw expected
                },
                resourceFontProfile(),
                platform,
                MinecraftFontBackendFactory { backend },
            )
        try {
            val failure = assertThrows(IllegalArgumentException::class.java, host::attach)
            assertSame(expected, failure)
            assertEquals(listOf(fontCleanup, platformCleanup), failure.suppressed.toList())
            assertEquals(1, backend.decodeCalls)
            assertEquals(1, backend.closeCalls)
            assertEquals(1, platformCloses)
            assertNull(readPrivateField(host, "textRenderer"))
            assertNull(readPrivateField(host, "platform"))
        } finally {
            host.close()
        }
        assertEquals(1, backend.closeCalls)
        assertEquals(1, platformCloses)
    }

    @Test
    fun sharedFontSnapshotCreatesIndependentHostBackendsAndCaches() {
        val profile = resourceFontProfile()
        val backends = ArrayList<FontTestBackend>()
        val factory = MinecraftFontBackendFactory { resourceFontBackend().also(backends::add) }
        createMinecraftUiHost(ScreenDefinition("first") { Text("日") }, profile, factory).use { first ->
            createMinecraftUiHost(ScreenDefinition("second") { Text("한") }, profile, factory).use { second ->
                assertEquals(2, backends.size)
                first.attach()
                first.frame(IntSize(5, 9))
                assertEquals(listOf(1, 0), backends.map(FontTestBackend::decodeCalls))
                first.close()
                assertEquals(listOf(1, 0), backends.map(FontTestBackend::closeCalls))

                second.attach()
                assertEquals(
                    2,
                    second
                        .frame(IntSize(5, 9))
                        .drawCommands
                        .filterIsInstance<DrawCommand.SampledImage>()
                        .size,
                )
                assertEquals(listOf(1, 1), backends.map(FontTestBackend::decodeCalls))
                assertEquals(listOf(1, 0), backends.map(FontTestBackend::closeCalls))
            }
        }
        assertEquals(listOf(1, 1), backends.map(FontTestBackend::closeCalls))
    }

    @Test
    fun hostOperationsRejectReentryFromContentEvaluation() {
        lateinit var host: MinecraftUiHost
        var reentryFailures: List<Throwable?> = emptyList()
        host =
            host {
                reentryFailures =
                    listOf(
                        runCatching { host.title }.exceptionOrNull(),
                        runCatching { host.pausesGame }.exceptionOrNull(),
                        runCatching { host.textInputFocus }.exceptionOrNull(),
                        runCatching { host.attach() }.exceptionOrNull(),
                        runCatching { host.detach() }.exceptionOrNull(),
                        runCatching { host.frame(IntSize(2, 1)) }.exceptionOrNull(),
                        runCatching { host.dispatchPointer(PointerEvent.Move(IntOffset.Zero)) }.exceptionOrNull(),
                        runCatching { host.close() }.exceptionOrNull(),
                    )
                evaluateComponentTree { Stack(modifier = Modifier.Empty.menuBackground()) {} }
            }
        host.attach()
        assertEquals(8, reentryFailures.size)
        assertTrue(reentryFailures.all { failure -> failure is IllegalStateException })
        host.close()
    }

    @Test
    fun recursiveCloseIsNoOpWhileCleanupMetadataReadRejects() {
        lateinit var host: MinecraftUiHost
        var metadataFailure: Throwable? = null
        val probe =
            MinecraftHostProbe(
                detachAction = {
                    metadataFailure = runCatching { host.title }.exceptionOrNull()
                    host.close()
                },
            )
        host = host { probe.element() }
        host.attach()

        host.close()

        assertTrue(metadataFailure is IllegalStateException)
        assertEquals(
            listOf(
                MinecraftHostProbe.LifecycleStage.Attach,
                MinecraftHostProbe.LifecycleStage.Detach,
                MinecraftHostProbe.LifecycleStage.Dispose,
            ),
            probe.lifecycle,
        )
    }

    @Test
    fun contentAndFrameFailuresPreserveExactIdentityAndCleanupOrder() {
        val contentFailure = IllegalArgumentException("content")
        val failingContent = host { throw contentFailure }
        assertSame(contentFailure, assertThrows(IllegalArgumentException::class.java) { failingContent.attach() })
        failingContent.close()

        val paintFailure = IllegalStateException("paint")
        val detachFailure = IllegalStateException("detach")
        val disposeFailure = IllegalStateException("dispose")
        val probe = MinecraftHostProbe(paintFailure = paintFailure, detachFailure = detachFailure, disposeFailure = disposeFailure)
        val failingFrame = host { probe.element() }
        failingFrame.attach()

        val thrown = assertThrows(IllegalStateException::class.java) { failingFrame.frame(IntSize(2, 1)) }
        assertSame(paintFailure, thrown)
        assertEquals(listOf(detachFailure, disposeFailure), thrown.suppressed.toList())
        assertEquals(
            listOf(
                MinecraftHostProbe.LifecycleStage.Attach,
                MinecraftHostProbe.LifecycleStage.Detach,
                MinecraftHostProbe.LifecycleStage.Dispose,
            ),
            probe.lifecycle,
        )
        failingFrame.close()
        assertEquals(3, probe.lifecycle.size)
    }

    @Test
    fun inputAndCloseFailuresPreserveExactIdentityAndCleanupOrder() {
        val inputFailure = IllegalStateException("input")
        val inputDetachFailure = IllegalStateException("input-detach")
        val inputDisposeFailure = IllegalStateException("input-dispose")
        val inputProbe =
            MinecraftHostProbe(
                inputFailure = inputFailure,
                detachFailure = inputDetachFailure,
                disposeFailure = inputDisposeFailure,
            )
        val failingInput = host { inputProbe.element() }
        failingInput.attach()
        failingInput.frame(IntSize(2, 1))

        val inputThrown =
            assertThrows(IllegalStateException::class.java) {
                failingInput.dispatchPointer(PointerEvent.Move(IntOffset.Zero))
            }
        assertSame(inputFailure, inputThrown)
        assertEquals(listOf(inputDetachFailure, inputDisposeFailure), inputThrown.suppressed.toList())
        failingInput.close()

        val closeDetachFailure = IllegalStateException("close-detach")
        val closeDisposeFailure = IllegalStateException("close-dispose")
        val closeProbe = MinecraftHostProbe(detachFailure = closeDetachFailure, disposeFailure = closeDisposeFailure)
        val failingClose = host { closeProbe.element() }
        failingClose.attach()

        val closeThrown = assertThrows(IllegalStateException::class.java) { failingClose.close() }
        assertSame(closeDetachFailure, closeThrown)
        assertEquals(listOf(closeDisposeFailure), closeThrown.suppressed.toList())
        failingClose.close()
        assertEquals(
            listOf(
                MinecraftHostProbe.LifecycleStage.Attach,
                MinecraftHostProbe.LifecycleStage.Detach,
                MinecraftHostProbe.LifecycleStage.Dispose,
            ),
            closeProbe.lifecycle,
        )
    }

    private fun host(content: () -> Element): MinecraftUiHost =
        createMinecraftUiHost(
            ScreenDefinition(
                title = UiText.Literal("test"),
                pausesGame = false,
            ) { element(content()) },
            MinecraftProfileFixture.create(),
        )

    private fun resourceFontProfile(): MinecraftUiProfile =
        MinecraftProfileFixture.create(
            fontSnapshot =
                FontTestResources.snapshot(
                    FontTestResources.font(
                        "minecraft:default",
                        """{"type":"bitmap","file":"test:font/host.png","height":8,"ascent":7,"chars":["日한"]}""",
                    ),
                    "assets/test/textures/font/host.png" to byteArrayOf(1),
                ),
        )

    private fun resourceFontBackend(closeFailure: Throwable? = null): FontTestBackend =
        FontTestBackend(
            decode = { createDrawImage(IntSize(8, 8), IntArray(64) { -1 }) },
            release = { closeFailure?.let { throw it } },
        )

    private fun fontPlatform(release: () -> Unit): MinecraftUiPlatform =
        object : MinecraftUiPlatform {
            private var closed = false

            override fun inventorySlot(binding: SlotBinding): MinecraftInventorySlotBinding = error("The font host does not resolve inventory slots.")

            override fun image(resource: ResourceId): DrawImage = error("The font host does not resolve platform images.")

            override fun playerSkin(source: PlayerSkinSource): MinecraftPlayerSkinBinding = error("The font host does not resolve player skins.")

            override fun refresh() {
                check(closed.not())
            }

            override fun close() {
                if (closed) return
                closed = true
                release()
            }
        }

    private fun wrongThread(action: () -> Unit): Throwable? {
        val task = FutureTask<Throwable?> { runCatching(action).exceptionOrNull() }
        val thread = Thread(task)
        thread.start()
        return task.get(5, TimeUnit.SECONDS)
    }

    private fun readPrivateField(
        host: MinecraftUiHost,
        name: String,
    ): Any? {
        val field = host.javaClass.getDeclaredField(name)
        field.isAccessible = true
        return field.get(host)
    }
}
