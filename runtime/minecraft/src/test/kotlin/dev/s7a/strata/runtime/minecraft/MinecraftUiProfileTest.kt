package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.UiText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * Verifies complete-profile validation and callback-lifetime ownership.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class MinecraftUiProfileTest {
    @Test
    fun missingRequiredSlotsAreRejectedIndependently() {
        assertThrows(IllegalArgumentException::class.java) {
            createMinecraftUiProfile {
                completeGlyphs()
                completeButtons()
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            createMinecraftUiProfile {
                menuBackground(image(IntSize(16, 16)))
                for (codePoint in 0x21 until 0x7E) {
                    printableAsciiGlyph(codePoint, image(IntSize(8, 8)))
                }
                completeButtons()
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            createMinecraftUiProfile {
                completeMenuAndGlyphs()
                buttonNormal(buttonImage(), 1, MinecraftNineSliceCenterMode.Tiled)
                buttonHighlighted(buttonImage(), 1, MinecraftNineSliceCenterMode.Tiled)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            createMinecraftUiProfile {
                completeMenuAndGlyphs()
                buttonNormal(buttonImage(), 1, MinecraftNineSliceCenterMode.Tiled)
                buttonDisabled(buttonImage(), 1, MinecraftNineSliceCenterMode.Tiled)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            createMinecraftUiProfile {
                completeMenuAndGlyphs()
                buttonHighlighted(buttonImage(), 1, MinecraftNineSliceCenterMode.Tiled)
                buttonDisabled(buttonImage(), 1, MinecraftNineSliceCenterMode.Tiled)
            }
        }
    }

    @Test
    fun menuAndEachRequiredSlotRejectDuplicates() {
        assertThrows(IllegalArgumentException::class.java) {
            createMinecraftUiProfile {
                menuBackground(image(IntSize(16, 16)))
                menuBackground(image(IntSize(16, 16)))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            createMinecraftUiProfile {
                completeMenuAndGlyphs()
                printableAsciiGlyph(0x21, image(IntSize(8, 8)))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            createMinecraftUiProfile {
                completeMenuAndGlyphs()
                buttonNormal(buttonImage(), 1, MinecraftNineSliceCenterMode.Tiled)
                buttonNormal(buttonImage(), 1, MinecraftNineSliceCenterMode.Tiled)
                buttonHighlighted(buttonImage(), 1, MinecraftNineSliceCenterMode.Tiled)
                buttonDisabled(buttonImage(), 1, MinecraftNineSliceCenterMode.Tiled)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            createMinecraftUiProfile {
                completeMenuAndGlyphs()
                buttonNormal(buttonImage(), 1, MinecraftNineSliceCenterMode.Tiled)
                buttonHighlighted(buttonImage(), 1, MinecraftNineSliceCenterMode.Tiled)
                buttonHighlighted(buttonImage(), 1, MinecraftNineSliceCenterMode.Tiled)
                buttonDisabled(buttonImage(), 1, MinecraftNineSliceCenterMode.Tiled)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            createMinecraftUiProfile {
                completeMenuAndGlyphs()
                buttonNormal(buttonImage(), 1, MinecraftNineSliceCenterMode.Tiled)
                buttonHighlighted(buttonImage(), 1, MinecraftNineSliceCenterMode.Tiled)
                buttonDisabled(buttonImage(), 1, MinecraftNineSliceCenterMode.Tiled)
                buttonDisabled(buttonImage(), 1, MinecraftNineSliceCenterMode.Tiled)
            }
        }
    }

    @Test
    fun malformedMasksAndGlyphBoundsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            createMinecraftUiProfile {
                printableAsciiGlyph(0x21, createDrawImage(IntSize(7, 8), IntArray(56)))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            createMinecraftUiProfile {
                printableAsciiGlyph(0x20, image(IntSize(8, 8)))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            createMinecraftUiProfile {
                printableAsciiGlyph(0x7F, image(IntSize(8, 8)))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            createMinecraftUiProfile {
                printableAsciiGlyph(0x21, createDrawImage(IntSize(8, 8), IntArray(64) { 1 }))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            createMinecraftUiProfile {
                completeMenuAndGlyphs { codePoint ->
                    if (codePoint == 0x21) {
                        createDrawImage(IntSize(8, 8), IntArray(64) { index -> if (index == 8) 0 else 0x00FFFFFF })
                    } else {
                        image(IntSize(8, 8))
                    }
                }
            }
        }
    }

    @Test
    fun validTransparentAndOpaqueMaskPixelsAreAccepted() {
        val profile =
            completeProfile { codePoint ->
                when (codePoint) {
                    0x21 -> image(IntSize(8, 8))
                    0x22 -> maskAt(0)
                    0x23 -> maskAt(7)
                    else -> image(IntSize(8, 8))
                }
            }
        val empty = glyph(profile, 0x21)
        val left = glyph(profile, 0x22)
        val right = glyph(profile, 0x23)
        assertEquals(1, empty.advance)
        assertEquals(2, left.advance)
        assertEquals(9, right.advance)
        assertEquals(0xFF3F3F3F.toInt(), left.normalShadow.argbAt(0, 0))
        assertEquals(0xFFFFFFFF.toInt(), left.normalForeground.argbAt(0, 0))
        assertEquals(0xFF282828.toInt(), left.inactiveShadow.argbAt(0, 0))
        assertEquals(0xFFA0A0A0.toInt(), left.inactiveForeground.argbAt(0, 0))
        assertEquals(0x00FFFFFF, left.normalForeground.argbAt(1, 0))
        assertEquals(0xFFFFFFFF.toInt(), right.normalForeground.argbAt(7, 0))
    }

    @Test
    fun wrongMenuGlyphAndButtonSizesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            completeProfile(menu = image(IntSize(15, 16)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            completeProfile { codePoint ->
                if (codePoint == 0x21) image(IntSize(7, 8)) else image(IntSize(8, 8))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            completeProfile(button = image(IntSize(199, 20)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            completeProfile(button = image(IntSize(200, 19)))
        }
    }

    @Test
    fun zeroNegativeAndSourceAndDestinationCenterBordersAreValidated() {
        assertThrows(IllegalArgumentException::class.java) { completeProfile(border = 0) }
        assertThrows(IllegalArgumentException::class.java) { completeProfile(border = -1) }
        completeProfile(border = 74)
        assertThrows(IllegalArgumentException::class.java) { completeProfile(border = 75) }
        assertThrows(IllegalArgumentException::class.java) { completeProfile(border = 99) }
        assertThrows(IllegalArgumentException::class.java) { completeProfile(border = 100) }

        assertThrows(IllegalArgumentException::class.java) { completeProfile(normalBorder = 75) }
        assertThrows(IllegalArgumentException::class.java) { completeProfile(highlightedBorder = 75) }
        assertThrows(IllegalArgumentException::class.java) { completeProfile(disabledBorder = 75) }
    }

    @Test
    fun defaultFixtureUsesTheLockedMinecraftButtonPolicies() {
        val profile = MinecraftProfileFixture.create()
        val normal = button(profile, "normalButton")
        val highlighted = button(profile, "highlightedButton")
        val disabled = button(profile, "disabledButton")
        assertEquals(3, normal.border)
        assertEquals(3, highlighted.border)
        assertEquals(1, disabled.border)
        assertSame(MinecraftNineSliceCenterMode.Tiled, normal.centerMode)
        assertSame(MinecraftNineSliceCenterMode.Tiled, highlighted.centerMode)
        assertSame(MinecraftNineSliceCenterMode.Tiled, disabled.centerMode)
    }

    @Test
    fun builderIsClosedAfterSuccessAndExactFailure() {
        var successful: MinecraftUiProfileBuilder? = null
        createMinecraftUiProfile {
            successful = this
            completeProfileDeclarations()
        }
        assertThrows(IllegalStateException::class.java) {
            checkNotNull(successful).menuBackground(image(IntSize(16, 16)))
        }

        var escaped: MinecraftUiProfileBuilder? = null
        val failure = IllegalStateException("profile callback")
        val thrown =
            assertThrows(IllegalStateException::class.java) {
                createMinecraftUiProfile {
                    escaped = this
                    throw failure
                }
            }
        assertSame(failure, thrown)
        assertThrows(IllegalStateException::class.java) {
            checkNotNull(escaped).menuBackground(image(IntSize(16, 16)))
        }
    }

    @Test
    fun wrongThreadIsRejectedWhileBuilderCallbackIsActive() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var escaped: MinecraftUiProfileBuilder? = null
        val creation =
            FutureTask<Throwable?> {
                runCatching {
                    createMinecraftUiProfile {
                        escaped = this
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS)) { "Timed out waiting for wrong-thread probe." }
                        completeProfileDeclarations()
                    }
                }.exceptionOrNull()
            }
        val creationThread = Thread(creation)
        creationThread.start()
        var wrongThreadRunner: Thread? = null
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val wrongThread =
                FutureTask<Throwable?> {
                    runCatching {
                        checkNotNull(escaped).menuBackground(image(IntSize(16, 16)))
                    }.exceptionOrNull()
                }
            wrongThreadRunner = Thread(wrongThread)
            wrongThreadRunner.start()
            assertTrue(wrongThread.get(5, TimeUnit.SECONDS) is IllegalStateException)
            release.countDown()
            assertNull(creation.get(5, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            creationThread.join(5_000)
            wrongThreadRunner?.join(5_000)
        }
    }

    @Test
    fun completeProfileCanBeReusedConcurrentlyByIndependentHosts() {
        val profile = MinecraftProfileFixture.create()
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val tasks =
            List(2) {
                FutureTask<Boolean> {
                    var host: MinecraftUiHost? = null
                    try {
                        ready.countDown()
                        check(start.await(5, TimeUnit.SECONDS)) { "Timed out waiting to start host." }
                        host =
                            createMinecraftUiHost(
                                createMinecraftScreenDefinition(UiText.Literal("reuse")) { context ->
                                    context.menuBackground()
                                },
                                profile,
                            )
                        host.attach()
                        host.frame(IntSize(16, 16)).size == IntSize(16, 16)
                    } finally {
                        host?.close()
                    }
                }
            }
        val threads = tasks.map(::Thread)
        threads.forEach(Thread::start)
        try {
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            tasks.forEach { task -> assertTrue(task.get(5, TimeUnit.SECONDS)) }
        } finally {
            start.countDown()
            threads.forEach { thread -> thread.join(5_000) }
        }
    }

    private fun completeProfile(
        menu: DrawImage = image(IntSize(16, 16)),
        button: DrawImage = buttonImage(),
        border: Int = 1,
        normalBorder: Int = border,
        highlightedBorder: Int = border,
        disabledBorder: Int = border,
        glyphMask: (Int) -> DrawImage = { image(IntSize(8, 8)) },
    ): MinecraftUiProfile =
        createMinecraftUiProfile {
            completeMenuAndGlyphs(menu, glyphMask)
            buttonNormal(button, normalBorder, MinecraftNineSliceCenterMode.Tiled)
            buttonHighlighted(button, highlightedBorder, MinecraftNineSliceCenterMode.Tiled)
            buttonDisabled(button, disabledBorder, MinecraftNineSliceCenterMode.Tiled)
        }

    private fun MinecraftUiProfileBuilder.completeMenuAndGlyphs(
        menu: DrawImage = image(IntSize(16, 16)),
        glyphMask: (Int) -> DrawImage = { image(IntSize(8, 8)) },
    ) {
        menuBackground(menu)
        completeGlyphs(glyphMask)
    }

    private fun MinecraftUiProfileBuilder.completeGlyphs(
        glyphMask: (Int) -> DrawImage = { image(IntSize(8, 8)) },
    ) {
        for (codePoint in 0x21..0x7E) {
            printableAsciiGlyph(codePoint, glyphMask(codePoint))
        }
    }

    private fun MinecraftUiProfileBuilder.completeButtons(
        button: DrawImage = buttonImage(),
        border: Int = 1,
    ) {
        buttonNormal(button, border, MinecraftNineSliceCenterMode.Tiled)
        buttonHighlighted(button, border, MinecraftNineSliceCenterMode.Tiled)
        buttonDisabled(button, border, MinecraftNineSliceCenterMode.Tiled)
    }

    private fun MinecraftUiProfileBuilder.completeProfileDeclarations() {
        completeMenuAndGlyphs()
        val button = buttonImage()
        buttonNormal(button, 1, MinecraftNineSliceCenterMode.Tiled)
        buttonHighlighted(button, 1, MinecraftNineSliceCenterMode.Tiled)
        buttonDisabled(button, 1, MinecraftNineSliceCenterMode.Tiled)
    }

    private fun buttonImage(): DrawImage = image(IntSize(200, 20))

    private fun maskAt(x: Int): DrawImage =
        createDrawImage(
            IntSize(8, 8),
            IntArray(64) { index -> if (index == x) -1 else 0x00FFFFFF },
        )

    private fun glyph(
        profile: MinecraftUiProfile,
        codePoint: Int,
    ): MinecraftGlyphSnapshot {
        val field = profile.javaClass.getDeclaredField("glyphs")
        field.isAccessible = true
        val glyphs = field.get(profile)
        check(glyphs is Map<*, *>) { "Profile glyph storage must remain a map." }
        return checkNotNull(glyphs[codePoint] as? MinecraftGlyphSnapshot)
    }

    private fun button(
        profile: MinecraftUiProfile,
        fieldName: String,
    ): MinecraftButtonSpriteSnapshot {
        val field = profile.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        return checkNotNull(field.get(profile) as? MinecraftButtonSpriteSnapshot)
    }

    private fun image(size: IntSize): DrawImage = createDrawImage(size, IntArray(Math.multiplyExact(size.width, size.height)) { 0x00FFFFFF })
}
