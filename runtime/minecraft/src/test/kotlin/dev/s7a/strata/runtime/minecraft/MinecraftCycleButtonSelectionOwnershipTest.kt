@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.component.CycleButtonState
import dev.s7a.strata.component.NineSliceCenterMode
import dev.s7a.strata.element.Element
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.actionDispatcher
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.LifecycleNode
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.UiText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/**
 * Requires state-transfer invalidation, one current selection key and terminal reference release while a node stays reachable.
 */
internal class MinecraftCycleButtonSelectionOwnershipTest {
    @Test
    fun mutationBeforeAttachmentAndStateOrderTransferResolveTheCurrentIndex() {
        val first = CycleButtonState(listOf("A", "B", "C"))
        val previous = element(first)
        val node = previous.type.createErased(previous)
        val invalidations = ArrayList<DirtyMask>()
        val release = node.bindRuntime(callback = invalidations::add)
        try {
            first.value = "C"
            (node as LifecycleNode).attach()
            assertEquals(UiText.Literal("C"), label(node))
            assertEquals(2, field(node, "selectedIndex"))
            val replacement = CycleButtonState(listOf("C", "B", "A"), "C")
            val current = element(replacement)
            assertEquals(DirtyMask.of(DirtyPhase.Paint, DirtyPhase.Semantics), previous.type.updateErased(previous, current, node))
            assertEquals(null, field(node, "selectedValue"))
            assertEquals(UiText.Literal("C"), label(node))
            assertEquals(0, field(node, "selectedIndex"))
            invalidations.clear()
            first.value = "B"
            assertEquals(emptyList<DirtyMask>(), invalidations)
            replacement.value = "A"
            assertEquals(listOf(DirtyMask.of(DirtyPhase.Paint, DirtyPhase.Semantics)), invalidations)
            assertEquals(UiText.Literal("A"), label(node))
        } finally {
            (node as LifecycleNode).dispose()
            release()
        }
    }

    @Test
    fun replacingLabelsKeepsOnlyTheIndexAndDisposeClearsEveryOwnedReference() {
        val state = CycleButtonState(listOf("A", "B"), "B")
        val previous = element(state)
        val node = previous.type.createErased(previous)
        val release = node.bindRuntime { }
        try {
            (node as LifecycleNode).attach()
            assertEquals(UiText.Literal("B"), label(node))
            assertSame(state.value, field(node, "selectedValue"))
            val current = element(state, "New ")
            assertEquals(DirtyMask.of(DirtyPhase.Paint, DirtyPhase.Semantics), previous.type.updateErased(previous, current, node))
            assertEquals(UiText.Literal("New B"), label(node))
            (node as LifecycleNode).detach()
            assertEquals(null, field(node, "selectedValue"))
            label(node)
            (node as LifecycleNode).dispose()
            for (name in listOf("state", "labels", "selectedValue", "observer", "normalSprite", "highlightedSprite", "disabledSprite")) {
                assertEquals(null, field(node, name), name)
            }
            (node as LifecycleNode).dispose()
        } finally {
            release()
        }
    }

    private fun element(
        state: CycleButtonState<String>,
        prefix: String = "",
    ): Element {
        val pixels = createDrawImage(IntSize(8, 8), IntArray(64) { -1 })
        val glyph = MinecraftGlyphSnapshot.create(2, pixels, pixels, pixels, pixels, pixels, pixels, pixels, pixels, pixels)
        val labels =
            state.values.map {
                val text = UiText.Literal(prefix + it)
                MinecraftTextRun.createNormal(text) { glyph } to MinecraftTextRun.createInactive(text) { glyph }
            }
        val sprite = MinecraftButtonSpriteSnapshot.create(createDrawImage(IntSize(200, 20), IntArray(4_000) { -1 }), 3, NineSliceCenterMode.Tiled)
        return createMinecraftCycleButtonElement(sprite, sprite, sprite, state, labels, 80, true, Modifier.Empty.actionDispatcher(), Modifier.Empty, null)
    }

    private fun label(node: Any): UiText {
        val method = node.javaClass.getDeclaredMethod("currentLabel")
        method.isAccessible = true
        val pair = method.invoke(node) as Pair<*, *>
        return (pair.first as MinecraftTextRun).text
    }

    private fun field(
        instance: Any,
        name: String,
    ): Any? {
        val field = instance.javaClass.getDeclaredField(name)
        field.isAccessible = true
        return field.get(instance)
    }
}
