package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.component.TextAreaState
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.KeyCode
import dev.s7a.strata.input.TextInputEvent
import dev.s7a.strata.runtime.UiTree
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.TextWrap
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * Compares raw-converted and independently canonical public editors through complete retained frames and real input.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class MinecraftTextAreaNormalizationTest {
    @Test
    fun canonicalAndConvertedStatePathsKeepOldFramesAndMatchFullInputGeometryPixelsSemanticsAndScroll() {
        val size = IntSize(32, 35)
        MinecraftTextAreaFixture().use { fixture ->
            val canonical = TextAreaState("日🙂\n한A\ne\u0301", maxLength = 32)
            val converted = TextAreaState("日🙂\r\n한A\u2029e\u0301", maxLength = 32)
            val ownedScroll = converted.scrollState
            UiTree().use { left ->
                UiTree().use { right ->
                    left.update(fixture.description(canonical, size, wrap = TextWrap.Character))
                    right.update(fixture.description(converted, size, wrap = TextWrap.Character))
                    val oldLeft = fixture.frame(left, size)
                    val oldRight = fixture.frame(right, size)
                    val oldPixels = rasterizeHeadless(oldRight, size).copyArgb()
                    compare(fixture, left, right, canonical, converted, size)
                    converted.value = "日🙂\u0085한A\re\u0301"
                    assertEquals(oldRight, fixture.frame(right, size))
                    assertFailsWith<IllegalArgumentException> { converted.value = "日\r\n\uD800" }
                    assertEquals(oldRight, fixture.frame(right, size))
                    for (event in listOf(TextInputEvent.Preedit("🙂\r\n日", 4, emptyList(), -1), TextInputEvent.Character(0x1F642))) {
                        fixture.input(left, event, size)
                        fixture.input(right, event, size)
                        compare(fixture, left, right, canonical, converted, size)
                    }
                    for (key in listOf(KeyCode.Left, KeyCode.Backspace, KeyCode.Enter, KeyCode.Up, KeyCode.Home, KeyCode.Delete, KeyCode.End)) {
                        fixture.key(left, key, size)
                        fixture.key(right, key, size)
                        compare(fixture, left, right, canonical, converted, size)
                    }
                    canonical.value = "A\n🙂\n日"
                    converted.value = "A\r\n🙂\u2028日"
                    compare(fixture, left, right, canonical, converted, size)
                    assertEquals(oldLeft, oldRight)
                    assertEquals(oldPixels.toList(), rasterizeHeadless(oldRight, size).copyArgb().toList())
                    assertSame(ownedScroll, converted.scrollState)
                }
            }
            converted.observe {}.close()
            converted.value = "closed\r\neditor"
            assertEquals("closed\neditor", converted.value)
            assertSame(ownedScroll, converted.scrollState)
        }
    }

    private fun compare(
        fixture: MinecraftTextAreaFixture,
        left: UiTree,
        right: UiTree,
        canonical: TextAreaState,
        converted: TextAreaState,
        size: IntSize,
    ) {
        val expected = fixture.frame(left, size)
        val actual = fixture.frame(right, size)
        assertEquals(canonical.value, converted.value)
        assertEquals(expected, actual)
        assertEquals(left.semantics(), right.semantics())
        assertEquals(canonical.scrollState.metrics, converted.scrollState.metrics)
        assertEquals(rasterizeHeadless(expected, size).copyArgb().toList(), rasterizeHeadless(actual, size).copyArgb().toList())
    }
}
