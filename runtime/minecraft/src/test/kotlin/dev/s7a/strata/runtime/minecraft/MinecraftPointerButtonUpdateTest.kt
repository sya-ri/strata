@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.component.Button
import dev.s7a.strata.component.Image
import dev.s7a.strata.component.NineSliceCenterMode
import dev.s7a.strata.component.Text
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.element.Element
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.LifecycleNode
import dev.s7a.strata.node.PointerHoverNode
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.UiText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies retained pointer-button mask classification, hover state, and asset release.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class MinecraftPointerButtonUpdateTest {
    @Test
    fun equalUpdatesAreClean() {
        val assets = assets()
        val previous = button(assets)
        val current = button(assets)
        val node = previous.type.createErased(previous)
        val release = node.bindRuntime { }
        try {
            assertEquals(DirtyMask.None, previous.type.updateErased(previous, previous, node))
            assertEquals(DirtyMask.None, previous.type.updateErased(previous, current, node))
        } finally {
            release()
            (node as LifecycleNode).dispose()
        }
    }

    @Test
    fun spriteAndSameLabelGlyphLayerChangesInvalidatePaintOnly() {
        val base = assets()
        val previous = button(base)
        val changedSprite =
            base.copy(
                normalSprite = sprite(0xFF505050.toInt()),
            )
        assertEquals(
            DirtyMask.of(DirtyPhase.Paint),
            updateMask(previous, button(changedSprite)),
        )

        val glyphPrevious = button(base)
        val changedGlyph =
            base.copy(
                normalGlyph = glyph(0xFF505050.toInt()),
            )
        assertEquals(
            DirtyMask.of(DirtyPhase.Paint),
            updateMask(glyphPrevious, button(changedGlyph)),
        )
    }

    @Test
    fun labelAndEnabledChangesInvalidatePaintAndSemantics() {
        val assets = assets()
        val previous = button(assets)
        val labelChanged = button(assets, label = "B")
        assertEquals(
            DirtyMask.of(DirtyPhase.Paint) + DirtyMask.of(DirtyPhase.Semantics),
            updateMask(previous, labelChanged),
        )
        val disabled = button(assets, enabled = false)
        assertEquals(
            DirtyMask.of(DirtyPhase.Paint) + DirtyMask.of(DirtyPhase.Semantics),
            updateMask(previous, disabled),
        )
    }

    @Test
    fun equalUpdatePreservesHoverAndDisablingClearsIt() {
        val base = assets()
        val previous = button(base)
        val node = previous.type.createErased(previous)
        val invalidations = ArrayList<DirtyMask>()
        val release = node.bindRuntime(invalidations::add)
        val hover = node as PointerHoverNode
        try {
            hover.onPointerHover(true)
            invalidations.clear()

            val equal = button(base)
            assertEquals(DirtyMask.None, previous.type.updateErased(previous, equal, node))
            assertTrue(invalidations.isEmpty())
            assertTrue(readPrivateField(node, "hovered") as Boolean)

            val disabled = button(base, enabled = false)
            assertEquals(
                DirtyMask.of(DirtyPhase.Paint) + DirtyMask.of(DirtyPhase.Semantics),
                previous.type.updateErased(equal, disabled, node),
            )
            assertEquals(false, readPrivateField(node, "hovered"))
            assertTrue(invalidations.isEmpty())
        } finally {
            release()
            (node as LifecycleNode).dispose()
        }
    }

    @Test
    fun disposeReleasesAssetsAndHoverState() {
        val element = button(assets())
        val node = element.type.createErased(element)
        val release = node.bindRuntime { }
        try {
            (node as PointerHoverNode).onPointerHover(true)
            assertTrue(readPrivateField(node, "hovered") as Boolean)
            (node as LifecycleNode).dispose()
            assertEquals(null, readPrivateField(node, "normalSprite"))
            assertEquals(false, readPrivateField(node, "hovered"))
            (node as LifecycleNode).dispose()
        } finally {
            release()
        }
    }

    @Test
    fun terminalHostClearsEvaluatorAndMetadataOwnership() {
        val host =
            createMinecraftUiHost(
                ScreenDefinition(UiText.Literal("button")) {
                    Button("A")
                },
                MinecraftProfileFixture.create(),
            )
        try {
            host.attach()
            host.frame(IntSize(150, 20))
        } finally {
            host.close()
        }
        assertEquals(null, readPrivateField(host, "evaluator"))
        assertEquals(null, readPrivateField(host, "metadata"))
    }

    private class Assets(
        val normalSprite: MinecraftButtonSpriteSnapshot,
        val highlightedSprite: MinecraftButtonSpriteSnapshot,
        val disabledSprite: MinecraftButtonSpriteSnapshot,
        val normalGlyph: MinecraftGlyphSnapshot,
        val inactiveGlyph: MinecraftGlyphSnapshot,
    ) {
        fun copy(
            normalSprite: MinecraftButtonSpriteSnapshot = this.normalSprite,
            normalGlyph: MinecraftGlyphSnapshot = this.normalGlyph,
        ): Assets = Assets(normalSprite, highlightedSprite, disabledSprite, normalGlyph, inactiveGlyph)
    }

    private fun assets(): Assets {
        val normalGlyph = glyph(0xFF202020.toInt())
        val inactiveGlyph = glyph(0xFFA0A0A0.toInt())
        return Assets(
            sprite(0xFF202020.toInt()),
            sprite(0xFF303030.toInt()),
            MinecraftButtonSpriteSnapshot.create(image(0xFF404040.toInt(), IntSize(200, 20)), 1, NineSliceCenterMode.Tiled),
            normalGlyph,
            inactiveGlyph,
        )
    }

    private fun button(
        assets: Assets,
        label: String = "A",
        enabled: Boolean = true,
    ): Element {
        val literal = UiText.Literal(label)
        val normalText = MinecraftTextRun.createNormal(literal) { assets.normalGlyph }
        val inactiveText = MinecraftTextRun.createInactive(literal) { assets.inactiveGlyph }
        return createMinecraftPointerButtonElement(
            assets.normalSprite,
            assets.highlightedSprite,
            assets.disabledSprite,
            normalText,
            inactiveText,
            literal,
            150,
            enabled,
            Modifier.Empty,
            null,
        )
    }

    private fun sprite(color: Int): MinecraftButtonSpriteSnapshot = MinecraftButtonSpriteSnapshot.create(image(color, IntSize(200, 20)), 3, NineSliceCenterMode.Tiled)

    private fun updateMask(
        previous: Element,
        current: Element,
    ): DirtyMask {
        val node = previous.type.createErased(previous)
        val release = node.bindRuntime { }
        return try {
            previous.type.updateErased(previous, current, node)
        } finally {
            release()
            (node as LifecycleNode).dispose()
        }
    }

    private fun glyph(color: Int): MinecraftGlyphSnapshot {
        val normal = image(color, IntSize(8, 8))
        val inactive = image(0xFFA0A0A0.toInt(), IntSize(8, 8))
        return MinecraftGlyphSnapshot.create(2, normal, normal, inactive, inactive, normal, normal, inactive, inactive, normal)
    }

    private fun image(
        color: Int,
        size: IntSize,
    ): DrawImage = createDrawImage(size, IntArray(Math.multiplyExact(size.width, size.height)) { color })

    private fun readPrivateField(
        instance: Any,
        name: String,
    ): Any? {
        val field = instance.javaClass.getDeclaredField(name)
        field.isAccessible = true
        return field.get(instance)
    }
}
