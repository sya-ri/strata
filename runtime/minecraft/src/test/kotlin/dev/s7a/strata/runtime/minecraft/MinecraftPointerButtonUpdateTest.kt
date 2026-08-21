package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.element.Element
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.LifecycleNode
import dev.s7a.strata.node.PointerInputNode
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.UiText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies retained pointer-button mask classification, callback replacement, and hover retention.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class MinecraftPointerButtonUpdateTest {
    @Test
    fun equalAndCallbackOnlyUpdatesAreCleanAndUseTheReplacementCallback() {
        var oldCalls = 0
        var newCalls = 0
        val coordinator = MinecraftButtonHoverCoordinator.create()
        val assets = assets()
        val previous = button(assets, coordinator, onPress = { oldCalls += 1 })
        val current = button(assets, coordinator, onPress = { newCalls += 1 })
        val node = previous.type.createErased(previous)
        val release = node.bindRuntime { }
        try {
            assertEquals(DirtyMask.None, previous.type.updateErased(previous, previous, node))
            assertEquals(DirtyMask.None, previous.type.updateErased(previous, current, node))
            val result =
                (node as PointerInputNode).onPointerEvent(
                    PointerEvent.Press(IntOffset.Zero, PointerButton.Primary),
                    IntOffset.Zero,
                )
            assertEquals(InputResult.Consumed, result)
            assertEquals(0, oldCalls)
            assertEquals(1, newCalls)
        } finally {
            release()
            (node as LifecycleNode).dispose()
            coordinator.abandon()
        }
    }

    @Test
    fun spriteAndSameLabelGlyphLayerChangesInvalidatePaintOnly() {
        val coordinator = MinecraftButtonHoverCoordinator.create()
        val base = assets()
        val previous = button(base, coordinator)
        try {
            val changedSprite =
                base.copy(
                    normalSprite = sprite(0xFF505050.toInt()),
                )
            assertEquals(
                DirtyMask.of(DirtyPhase.Paint),
                updateMask(previous, button(changedSprite, coordinator)),
            )

            val glyphPrevious = button(base, coordinator)
            val changedGlyph =
                base.copy(
                    normalGlyph = glyph(0xFF505050.toInt()),
                )
            assertEquals(
                DirtyMask.of(DirtyPhase.Paint),
                updateMask(glyphPrevious, button(changedGlyph, coordinator)),
            )
        } finally {
            coordinator.abandon()
        }
    }

    @Test
    fun labelAndEnabledChangesInvalidatePaintAndSemantics() {
        val coordinator = MinecraftButtonHoverCoordinator.create()
        val assets = assets()
        val previous = button(assets, coordinator)
        try {
            val labelChanged = button(assets, coordinator, label = "B")
            assertEquals(
                DirtyMask.of(DirtyPhase.Paint) + DirtyMask.of(DirtyPhase.Semantics),
                updateMask(previous, labelChanged),
            )
            val disabled = button(assets, coordinator, enabled = false)
            assertEquals(
                DirtyMask.of(DirtyPhase.Paint) + DirtyMask.of(DirtyPhase.Semantics),
                updateMask(previous, disabled),
            )
        } finally {
            coordinator.abandon()
        }
    }

    @Test
    fun equalUpdatePreservesHoveredIdentityAndCoordinatorSwapClearsItWithPaintOnly() {
        val oldCoordinator = MinecraftButtonHoverCoordinator.create()
        val newCoordinator = MinecraftButtonHoverCoordinator.create()
        val base = assets()
        val previous = button(base, oldCoordinator)
        val node = previous.type.createErased(previous)
        val invalidations = ArrayList<DirtyMask>()
        val release = node.bindRuntime(invalidations::add)
        val target = node as MinecraftButtonHoverCoordinator.Target
        try {
            oldCoordinator.beginMove()
            oldCoordinator.offer(target)
            oldCoordinator.finishMove()
            invalidations.clear()

            val equal = button(base, oldCoordinator)
            assertEquals(DirtyMask.None, previous.type.updateErased(previous, equal, node))
            assertTrue(invalidations.isEmpty())

            val swapped = button(base, newCoordinator)
            assertEquals(
                DirtyMask.of(DirtyPhase.Paint),
                previous.type.updateErased(equal, swapped, node),
            )
            assertTrue(invalidations.isEmpty())
        } finally {
            release()
            (node as LifecycleNode).dispose()
            oldCoordinator.abandon()
            newCoordinator.abandon()
        }
    }

    @Test
    fun hoveredNodeForgetDoesNotInvalidateAndDisposeReleasesCallbackAndAssets() {
        val coordinator = MinecraftButtonHoverCoordinator.create()
        var calls = 0
        val element = button(assets(), coordinator, onPress = { calls += 1 })
        val node = element.type.createErased(element)
        val release = node.bindRuntime { }
        val target = node as MinecraftButtonHoverCoordinator.Target
        try {
            coordinator.beginMove()
            coordinator.offer(target)
            coordinator.finishMove()
            assertTrue(readPrivateField(node, "hovered") as Boolean)

            coordinator.forget(target)
            coordinator.beginMove()
            coordinator.finishMove()
            assertTrue(readPrivateField(node, "hovered") as Boolean)

            (node as LifecycleNode).dispose()
            assertEquals(null, readPrivateField(node, "onPress"))
            assertEquals(null, readPrivateField(node, "coordinator"))
            assertEquals(null, readPrivateField(node, "normalSprite"))
            assertEquals(0, calls)
            (node as LifecycleNode).dispose()
        } finally {
            release()
            coordinator.abandon()
        }
    }

    @Test
    fun terminalHostClearsCoordinatorAndEvaluatorOwnership() {
        var callbackCalls = 0
        val host =
            createMinecraftUiHost(
                createMinecraftScreenDefinition(UiText.Literal("button")) { context ->
                    context.pointerButton(UiText.Literal("A")) { callbackCalls += 1 }
                },
                MinecraftProfileFixture.create(),
            )
        try {
            host.attach()
            host.frame(IntSize(150, 20))
        } finally {
            host.close()
        }
        assertEquals(null, readPrivateField(host, "coordinator"))
        assertEquals(null, readPrivateField(host, "evaluator"))
        assertEquals(null, readPrivateField(host, "metadata"))
        assertEquals(0, callbackCalls)

        val primary = IllegalStateException("button callback")
        val failingHost =
            createMinecraftUiHost(
                createMinecraftScreenDefinition(UiText.Literal("button")) { context ->
                    context.pointerButton(UiText.Literal("A")) { throw primary }
                },
                MinecraftProfileFixture.create(),
            )
        try {
            failingHost.attach()
            failingHost.frame(IntSize(150, 20))
            val failure =
                assertThrows(IllegalStateException::class.java) {
                    failingHost.dispatchPointer(
                        PointerEvent.Press(
                            IntOffset(1, 1),
                            PointerButton.Primary,
                        ),
                    )
                }
            assertSame(primary, failure)
            assertEquals(null, readPrivateField(failingHost, "coordinator"))
            assertEquals(null, readPrivateField(failingHost, "evaluator"))
            assertEquals(null, readPrivateField(failingHost, "metadata"))
        } finally {
            failingHost.close()
        }
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
            MinecraftButtonSpriteSnapshot.create(image(0xFF404040.toInt(), IntSize(200, 20)), 1, MinecraftNineSliceCenterMode.Tiled),
            normalGlyph,
            inactiveGlyph,
        )
    }

    private fun button(
        assets: Assets,
        coordinator: MinecraftButtonHoverCoordinator,
        label: String = "A",
        enabled: Boolean = true,
        onPress: () -> Unit = {},
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
            enabled,
            onPress,
            coordinator,
            Modifier.Empty,
            null,
        )
    }

    private fun sprite(color: Int): MinecraftButtonSpriteSnapshot = MinecraftButtonSpriteSnapshot.create(image(color, IntSize(200, 20)), 3, MinecraftNineSliceCenterMode.Tiled)

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
        return MinecraftGlyphSnapshot.create(2, normal, normal, inactive, inactive)
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
