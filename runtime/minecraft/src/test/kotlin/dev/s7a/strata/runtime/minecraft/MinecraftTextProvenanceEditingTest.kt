@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.component.TextAreaState
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.KeyCode
import dev.s7a.strata.input.KeyboardModifiers
import dev.s7a.strata.input.TextInputEvent
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.runtime.UiTree
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.TextWrap
import dev.s7a.strata.text.UiText
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/**
 * Public profile-backed TextArea editing, preedit, font replacement and scrolling with independent scalar expectations.
 * The existing fixture supplies fixed three- and two-pixel font advances and rejects display shaping for editable text.
 */
internal class MinecraftTextProvenanceEditingTest {
    @Test
    fun supplementaryNavigationInsertionAndPreeditKeepCaretAndUnderlineFontGeometry() {
        MinecraftTextAreaFixture(cacheEntries = 0).use { fixture ->
            UiTree().use { tree ->
                val state = TextAreaState("A🙂B")
                tree.update(fixture.description(state))
                fixture.frame(tree)
                caret(fixture.key(tree, KeyCode.Home, modifiers = KeyboardModifiers(control = true)), 4, 4)
                caret(fixture.key(tree, KeyCode.Right), 7, 4)
                caret(fixture.key(tree, KeyCode.Right), 10, 4)
                caret(fixture.key(tree, KeyCode.Left), 7, 4)
                val committed = fixture.input(tree, TextInputEvent.Character('日'.code))
                assertEquals("A日🙂B", state.value)
                assertEquals(
                    UiText.Literal("A日🙂B"),
                    tree
                        .semantics()
                        .single()
                        .semantics.value,
                )
                val oldPixels = rasterizeHeadless(committed, IntSize(32, 26)).copyArgb()
                val preedit = fixture.input(tree, TextInputEvent.Preedit("한🙂", 1, listOf("한", "🙂"), 0))
                assertEquals("A日🙂B", state.value)
                assertEquals(listOf(IntRect(10, 12, 13, 13)), preedit.filterIsInstance<DrawCommand.FillRectangle>().filter { it.bounds.height == 1 }.map { it.bounds })
                caret(preedit, 13, 4)
                tree.update(fixture.description(state, font = ResourceId("test", "compact")))
                val compact = fixture.frame(tree)
                assertEquals(listOf(IntRect(8, 12, 10, 13)), compact.filterIsInstance<DrawCommand.FillRectangle>().filter { it.bounds.height == 1 }.map { it.bounds })
                caret(compact, 10, 4)
                fixture.input(tree, TextInputEvent.Preedit("", 0, emptyList(), -1))
                assertEquals("A日🙂B", state.value)
                assertArrayEquals(oldPixels, rasterizeHeadless(committed, IntSize(32, 26)).copyArgb())
                val calls = fixture.glyphCalls
                val clean = fixture.frame(tree)
                assertEquals(clean, fixture.frame(tree))
                assertEquals(calls, fixture.glyphCalls)
            }
        }
    }

    @Test
    fun wrappingReflowAffinityAndScrollPreserveCompleteOriginalSemantics() {
        MinecraftTextAreaFixture().use { fixture ->
            UiTree().use { tree ->
                val state = TextAreaState("AB🙂한CD\n日本語\nאבג\nالعربية")
                val narrow = IntSize(15, 35)
                tree.update(fixture.description(state, narrow, wrap = TextWrap.Character))
                fixture.frame(tree, narrow)
                fixture.key(tree, KeyCode.Home, narrow, KeyboardModifiers(control = true))
                caret(fixture.key(tree, KeyCode.End, narrow), 10, 4)
                caret(fixture.key(tree, KeyCode.Right, narrow), 4, 13)
                caret(fixture.key(tree, KeyCode.Left, narrow), 10, 4)
                val old = fixture.frame(tree, narrow)
                val pixels = rasterizeHeadless(old, narrow).copyArgb()
                val wide = IntSize(32, 35)
                tree.update(fixture.description(state, wide, wrap = TextWrap.Character))
                fixture.frame(tree, wide)
                state.scrollState.scrollTo(9.0)
                val scrolled = fixture.frame(tree, wide)
                assertEquals(
                    UiText.Literal(state.value),
                    tree
                        .semantics()
                        .single()
                        .semantics.value,
                )
                assertFalse(scrolled == old)
                assertArrayEquals(pixels, rasterizeHeadless(old, narrow).copyArgb())
                state.scrollState.scrollTo(0.0)
                fixture.key(tree, KeyCode.Home, wide, KeyboardModifiers(control = true))
                fixture.input(tree, TextInputEvent.Character('日'.code), wide)
                assertEquals("日AB🙂한CD\n日本語\nאבג\nالعربية", state.value)
            }
        }
    }

    @Test
    fun closedTreeReleasesCurrentStateWhileOldFramesAndNewIndependentOwnerRemainUsable() {
        MinecraftTextAreaFixture().use { firstFixture ->
            MinecraftTextAreaFixture().use { secondFixture ->
                val state = TextAreaState("A🙂B")
                val independentState = TextAreaState("A🙂B")
                val first = UiTree()
                val second = UiTree()
                try {
                    first.update(firstFixture.description(state))
                    second.update(secondFixture.description(independentState))
                    val old = firstFixture.frame(first)
                    val oldPixels = rasterizeHeadless(old, IntSize(32, 26)).copyArgb()
                    val independent = secondFixture.frame(second)
                    firstFixture.input(first, TextInputEvent.Preedit("日🙂", 3, listOf("日", "🙂"), 1))
                    first.close()
                    state.observe {}.close()
                    assertEquals(independent, secondFixture.frame(second))
                    UiTree().use { replacement ->
                        replacement.update(firstFixture.description(state))
                        firstFixture.frame(replacement)
                        firstFixture.input(replacement, TextInputEvent.Character('日'.code))
                        assertEquals("A🙂B日", state.value)
                    }
                    state.observe {}.close()
                    second.close()
                    firstFixture.close()
                    secondFixture.close()
                    assertArrayEquals(oldPixels, rasterizeHeadless(old, IntSize(32, 26)).copyArgb())
                    assertEquals("A🙂B", independentState.value)
                } finally {
                    first.close()
                    second.close()
                }
            }
        }
    }

    private fun caret(
        commands: List<DrawCommand>,
        left: Int,
        top: Int,
    ) {
        assertEquals(IntRect(left, top, left + 1, top + 9), commands.filterIsInstance<DrawCommand.FillRectangle>().last().bounds)
    }
}
