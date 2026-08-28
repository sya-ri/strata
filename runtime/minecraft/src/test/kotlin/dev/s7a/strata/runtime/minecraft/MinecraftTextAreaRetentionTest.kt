package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.component.TextAreaState
import dev.s7a.strata.component.TextStyle
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.KeyCode
import dev.s7a.strata.input.TextInputEvent
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.runtime.UiTree
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.TextWrap
import dev.s7a.strata.text.UiText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies independent retained ownership, precise presentation invalidation, and state/presentation reconciliation.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class MinecraftTextAreaRetentionTest {
    @Test
    fun repeatedDescriptionAndFailedConcurrentAttachmentDoNotStealCurrentCallbacks() {
        MinecraftTextAreaFixture(cacheEntries = 0).use { fixture ->
            val state = TextAreaState("日")
            val description = fixture.description(state)
            UiTree().use { first ->
                first.update(description)
                fixture.frame(first)
                val composed = fixture.input(first, TextInputEvent.Preedit("🙂", 2, listOf("🙂"), 0))
                val calls = fixture.glyphCalls
                first.update(description)
                assertEquals(composed, fixture.frame(first))
                assertEquals(calls, fixture.glyphCalls)
                UiTree().use { competing ->
                    assertThrows(IllegalStateException::class.java) { competing.update(description) }
                }
                assertEquals(composed, fixture.frame(first))
                fixture.input(first, TextInputEvent.Character('한'.code))
                assertEquals("日한", state.value)
            }
            UiTree().use { replacement ->
                replacement.update(description)
                val frame = fixture.frame(replacement)
                assertEquals(4, frame.filterIsInstance<DrawCommand.SampledImage>().size)
                fixture.input(replacement, TextInputEvent.Character('A'.code))
                assertEquals("日한A", state.value)
                state.value = "B"
                assertEquals(2, fixture.frame(replacement).filterIsInstance<DrawCommand.SampledImage>().size)
            }
            state.observe {}.close()
        }
    }

    @Test
    fun keyedStateTransferPreservesFocusAndReleasesOnlyThePreviousSubscription() {
        MinecraftTextAreaFixture().use { fixture ->
            val first = TextAreaState("日")
            val second = TextAreaState("한")
            UiTree().use { tree ->
                tree.update(fixture.description(first))
                fixture.frame(tree)
                fixture.input(tree, TextInputEvent.Preedit("🙂", 2, listOf("🙂"), 0))
                tree.update(fixture.description(second))
                val transferred = fixture.frame(tree)
                assertEquals(2, transferred.filterIsInstance<DrawCommand.SampledImage>().size)
                assertEquals(1, transferred.filterIsInstance<DrawCommand.FillRectangle>().size)
                first.observe {}.close()
                assertThrows(IllegalStateException::class.java) { second.observe {} }
                fixture.input(tree, TextInputEvent.Character('A'.code))
                assertEquals("한A", second.value)
                assertEquals("日", first.value)
                assertEquals(
                    UiText.Literal("한A"),
                    tree
                        .semantics()
                        .single()
                        .semantics.value,
                )
            }
            second.observe {}.close()
        }
    }

    @Test
    fun failedStateTransferReleasesTheOldEditorWithoutClosingAnotherStatesObserver() {
        MinecraftTextAreaFixture().use { fixture ->
            val first = TextAreaState("日")
            val occupied = TextAreaState("한")
            occupied.observe {}.use {
                UiTree().use { tree ->
                    tree.update(fixture.description(first))
                    fixture.frame(tree)
                    assertThrows(IllegalStateException::class.java) { tree.update(fixture.description(occupied)) }
                }
                first.observe {}.close()
                assertThrows(IllegalStateException::class.java) { occupied.observe {} }
            }
            occupied.observe {}.close()
        }
    }

    @Test
    fun frameOnlyChangesReuseLayoutWhileStyleFontAndReflowRebuildWithoutLosingComposition() {
        val size = IntSize(32, 35)
        MinecraftTextAreaFixture(cacheEntries = 0).use { fixture ->
            UiTree().use { tree ->
                val state = TextAreaState("日A\n한B")
                tree.update(fixture.description(state, size))
                fixture.frame(tree, size)
                val composed = fixture.input(tree, TextInputEvent.Preedit("🙂", 2, listOf("🙂"), 0), size)
                val semantics = tree.semantics().single().semantics
                val calls = fixture.glyphCalls
                tree.update(fixture.description(state, size, highlightedColor = 0xFF123456.toInt()))
                val newFrame = fixture.frame(tree, size)
                assertEquals(calls, fixture.glyphCalls)
                assertEquals(composed.filterIsInstance<DrawCommand.SampledImage>(), newFrame.filterIsInstance<DrawCommand.SampledImage>())
                assertEquals(0xFF123456.toInt(), rasterizeHeadless(newFrame, size).argbAt(0, 0))
                assertSame(semantics, tree.semantics().single().semantics)
                val variants =
                    listOf(
                        fixture.description(state, size, style = TextStyle.Normal),
                        fixture.description(state, size, font = ResourceId("test", "compact")),
                        fixture.description(state, size, wrap = TextWrap.Character, lineSpacing = 1),
                    )
                for (description in variants) {
                    val before = fixture.glyphCalls
                    tree.update(description)
                    val commands = fixture.frame(tree, size)
                    assertTrue(before < fixture.glyphCalls)
                    assertTrue(8 <= commands.filterIsInstance<DrawCommand.SampledImage>().size)
                    assertEquals(2, commands.filterIsInstance<DrawCommand.FillRectangle>().size)
                    assertEquals("日A\n한B", state.value)
                    assertEquals(semantics, tree.semantics().single().semantics)
                }
            }
        }
    }

    @Test
    fun horizontalPanClampsAfterShorteningWideningFontChangesAndWrappedModes() {
        val narrow = IntSize(25, 26)
        MinecraftTextAreaFixture().use { fixture ->
            val changes: List<(UiTree, TextAreaState) -> IntSize> =
                listOf(
                    { _, state -> narrow.also { state.value = "A" } },
                    { tree, state -> IntSize(40, 26).also { tree.update(fixture.description(state, it)) } },
                    { tree, state -> narrow.also { tree.update(fixture.description(state, it, font = ResourceId("test", "compact"))) } },
                    { tree, state -> narrow.also { tree.update(fixture.description(state, it, wrap = TextWrap.Character)) } },
                )
            for (change in changes) {
                UiTree().use { tree ->
                    val state = TextAreaState("AAAAAAAA")
                    tree.update(fixture.description(state, narrow))
                    fixture.frame(tree, narrow)
                    fixture.key(tree, KeyCode.Home, narrow)
                    val panned = fixture.key(tree, KeyCode.End, narrow)
                    assertTrue(panned.filterIsInstance<DrawCommand.SampledImage>().any { it.destination.left < 4f })
                    val size = change(tree, state)
                    val clamped = fixture.frame(tree, size)
                    assertEquals(4f, foreground(clamped).first().destination.left)
                }
            }
        }
    }

    @Test
    fun stateTransfersRecomputeFocusedPanAndResetUnfocusedPanWithoutMovingTheNewScrollState() {
        val size = IntSize(25, 26)
        MinecraftTextAreaFixture().use { fixture ->
            UiTree().use { tree ->
                val first = TextAreaState("AAAAAAAA")
                val second = TextAreaState("BBBBBBBBBBBB")
                tree.update(fixture.description(first, size))
                fixture.frame(tree, size)
                fixture.key(tree, KeyCode.Home, size)
                fixture.key(tree, KeyCode.End, size)
                tree.update(fixture.description(second, size))
                val focused = fixture.frame(tree, size)
                val caret = focused.filterIsInstance<DrawCommand.FillRectangle>().single().bounds
                assertTrue(4 <= caret.left && caret.right <= size.width - 4)
                tree.update(fixture.description(second, size, enabled = false))
                fixture.frame(tree, size)
                val third = TextAreaState("CCCCCCCC\nD\nE\nF")
                third.scrollState.scrollTo(9.0)
                tree.update(fixture.description(third, size, focused = false))
                val unfocused = fixture.frame(tree, size)
                assertEquals(9.0, third.scrollState.metrics.offset)
                assertEquals(4f, foreground(unfocused).first().destination.left)
                assertTrue(unfocused.none { it is DrawCommand.FillRectangle })
            }
        }
    }

    private fun foreground(commands: List<DrawCommand>): List<DrawCommand.SampledImage> = commands.filterIsInstance<DrawCommand.SampledImage>().filter { it.tint == ArgbColor(0xFFE0E0E0.toInt()) }
}
