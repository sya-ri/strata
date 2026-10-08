@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.component.CheckboxState
import dev.s7a.strata.component.CycleButtonState
import dev.s7a.strata.component.NineSliceCenterMode
import dev.s7a.strata.component.SliderState
import dev.s7a.strata.element.Element
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.actionDispatcher
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.FocusTargetNode
import dev.s7a.strata.node.LifecycleNode
import dev.s7a.strata.node.Node
import dev.s7a.strata.node.PointerHoverNode
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.UiText
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Classifies complete immutable presentation changes independently of the retained paint cache.
 */
internal class MinecraftStatefulControlUpdateTest {
    @Test
    fun freshEquivalentDescriptionsDoNotInvalidatePaintOrMeasure() {
        for (kind in Kind.entries) {
            val input = Input()
            withNode(kind, input) { previous, node, _ ->
                val fresh = input.element(kind)
                val dirty = previous.type.updateErased(previous, fresh, node)
                assertFalse(DirtyPhase.Paint in dirty, kind.name)
                assertFalse(DirtyPhase.Measure in dirty, kind.name)
                assertFalse(DirtyPhase.Paint in previous.type.updateErased(fresh, fresh, node), kind.name)
            }
        }
    }

    @Test
    fun everySpriteAndBothGlyphStylesInvalidatePaint() {
        for (kind in Kind.entries) {
            val input = Input()
            if (kind == Kind.Checkbox) {
                for (index in input.checkboxSprites.indices) {
                    val sprites = input.checkboxSprites.toMutableList()
                    sprites[index] = image(20, 20)
                    assertPaint(kind, input, input.copy(checkboxSprites = sprites))
                }
            } else {
                val spriteCount = if (kind == Kind.Cycle) 3 else 2
                for (index in 0 until spriteCount) {
                    val sprites = input.buttonSprites.toMutableList()
                    sprites[index] = MinecraftButtonSpriteSnapshot.create(image(200, 20), 1, NineSliceCenterMode.Tiled)
                    assertPaint(kind, input, input.copy(buttonSprites = sprites))
                }
            }
            assertPaint(kind, input, input.copy(normalGlyph = glyph()))
            assertPaint(kind, input, input.copy(inactiveGlyph = glyph()))
            assertPaint(kind, input, input.copy(normalAdvance = 2))
            assertPaint(kind, input, input.copy(inactiveAdvance = 2))
        }
        val slider = Input()
        for (index in slider.handles.indices) {
            val handles = slider.handles.toMutableList()
            handles[index] = image(8, 20)
            assertPaint(Kind.Slider, slider, slider.copy(handles = handles))
        }
    }

    @Test
    fun nativeWidthAndTextPolicyChangesInvalidateBothStylesForEveryControl() {
        for (kind in Kind.entries) {
            val input = Input()
            assertPaint(kind, input, input.copy(normalTransform = { run -> copyRun(run, nativeWidth = -1) }))
            assertPaint(kind, input, input.copy(inactiveTransform = { run -> copyRun(run, nativeWidth = -1) }))
            assertPaint(kind, input, input.copy(normalTransform = { run -> copyRun(run, changePolicy = true) }))
            assertPaint(kind, input, input.copy(inactiveTransform = { run -> copyRun(run, changePolicy = true) }))
        }
    }

    @Test
    fun everyOrderedCycleLabelIncludingAnUnselectedOptionInvalidatesPaint() {
        val input = Input()
        for (index in input.labels.indices) {
            val labels = input.labels.toMutableList()
            labels[index] = "B"
            assertPaint(Kind.Cycle, input, input.copy(labels = labels))
        }
        assertPaint(Kind.Cycle, input, input.copy(labels = input.labels.reversed()))
        assertPaint(Kind.Cycle, input, input.copy(cycle = CycleButtonState(listOf(2, 1, 0))))
    }

    @Test
    fun geometryEnabledLabelAndStateReplacementKeepRequiredInvalidations() {
        for (kind in Kind.entries) {
            val input = Input()
            val width = update(kind, input, input.copy(width = 151))
            assertTrue(DirtyPhase.Paint in width)
            assertTrue(DirtyPhase.Measure in width)
            val disabled = update(kind, input, input.copy(enabled = false))
            assertTrue(DirtyPhase.Paint in disabled)
            assertTrue(DirtyPhase.Semantics in disabled)
            assertPaint(kind, input, input.copy(labels = listOf("B", "C", "D")))
            val replacement = input.copy(checkbox = CheckboxState(), slider = SliderState(0.0), cycle = CycleButtonState(listOf(0, 1, 2)))
            val changedState = update(kind, input, replacement)
            assertTrue(DirtyPhase.Paint in changedState)
            assertTrue(DirtyPhase.Semantics in changedState)
        }
    }

    @Test
    fun equalUpdatePreservesInputStatePendingDirtyAndCurrentSubscription() {
        for (kind in Kind.entries) {
            val input = Input()
            withNode(kind, input) { previous, node, invalidations ->
                (node as PointerHoverNode).onPointerHover(true)
                (node as FocusTargetNode).onFocusChanged(true)
                input.change(kind)
                assertTrue(invalidations.any { DirtyPhase.Paint in it })
                val pending = invalidations.toList()
                val fresh = input.element(kind)
                assertFalse(DirtyPhase.Paint in previous.type.updateErased(previous, fresh, node))
                assertEquals(pending, invalidations)
                assertEquals(true, field(node, "hovered"))
                assertEquals(true, field(node, "focused"))
                val disabled = input.copy(enabled = false).element(kind)
                previous.type.updateErased(fresh, disabled, node)
                assertEquals(false, field(node, "hovered"))
                assertEquals(false, field(node, "focused"))
                if (kind == Kind.Slider) assertEquals(false, field(node, "dragging"))
            }
        }
    }

    @Test
    fun stateReplacementRetiresOldObservationAndTerminalCloseReleasesCurrentAssets() {
        for (kind in Kind.entries) {
            val input = Input()
            withNode(kind, input) { previous, node, invalidations ->
                val replacement = Input()
                previous.type.updateErased(previous, replacement.element(kind), node)
                invalidations.clear()
                input.change(kind)
                assertTrue(invalidations.isEmpty())
                replacement.change(kind)
                assertTrue(invalidations.any { DirtyPhase.Paint in it })
                (node as LifecycleNode).dispose()
                invalidations.clear()
                replacement.change(kind)
                assertTrue(invalidations.isEmpty())
                assertEquals(null, field(node, "state"))
                assertEquals(null, field(node, "observer"))
                assertEquals(null, field(node, if (kind == Kind.Cycle) "labels" else "normalText"))
                (node as LifecycleNode).dispose()
            }
        }
    }

    private fun assertPaint(kind: Kind, previous: Input, current: Input) {
        assertTrue(DirtyPhase.Paint in update(kind, previous, current), kind.name)
    }

    private fun update(kind: Kind, previous: Input, current: Input): DirtyMask {
        var result = DirtyMask.None
        withNode(kind, previous) { element, node, _ -> result = element.type.updateErased(element, current.element(kind), node) }
        return result
    }

    private fun withNode(kind: Kind, input: Input, action: (Element, Node, MutableList<DirtyMask>) -> Unit) {
        val element = input.element(kind)
        val node = element.type.createErased(element)
        val dirty = ArrayList<DirtyMask>()
        val release = node.bindRuntime(callback = dirty::add)
        val lifecycle = node as LifecycleNode
        try {
            lifecycle.attach()
            dirty.clear()
            action(element, node, dirty)
        } finally {
            lifecycle.dispose()
            release()
        }
    }

    private fun field(instance: Any, name: String): Any? {
        val field = instance.javaClass.getDeclaredField(name)
        field.isAccessible = true
        return field.get(instance)
    }

    /**
     * Retained implementations under the same platform-neutral element update contract.
     */
    private enum class Kind {
        Checkbox,
        Slider,
        Cycle,
    }

    /**
     * Immutable description inputs with independently owned mutable control state.
     */
    private data class Input(
        val checkboxSprites: List<DrawImage> = List(4) { image(20, 20) },
        val buttonSprites: List<MinecraftButtonSpriteSnapshot> = List(3) { MinecraftButtonSpriteSnapshot.create(image(200, 20), 1, NineSliceCenterMode.Tiled) },
        val handles: List<DrawImage> = List(2) { image(8, 20) },
        val normalGlyph: DrawImage = glyph(),
        val inactiveGlyph: DrawImage = glyph(),
        val normalAdvance: Int = 1,
        val inactiveAdvance: Int = 1,
        val labels: List<String> = listOf("A", "C", "D"),
        val normalTransform: (MinecraftTextRun) -> MinecraftTextRun = { it },
        val inactiveTransform: (MinecraftTextRun) -> MinecraftTextRun = { it },
        val checkbox: CheckboxState = CheckboxState(),
        val slider: SliderState = SliderState(0.0),
        val cycle: CycleButtonState<Int> = CycleButtonState(listOf(0, 1, 2)),
        val width: Int = 150,
        val enabled: Boolean = true,
    ) {
        /**
         * Builds fresh text runs without replacing immutable sprite identities.
         */
        fun element(kind: Kind): Element {
            val runs = labels.map { label ->
                val literal = UiText.Literal(label)
                normalTransform(MinecraftTextRun.createNormal(literal) { snapshot(normalGlyph, normalAdvance) }) to inactiveTransform(MinecraftTextRun.createInactive(literal) { snapshot(inactiveGlyph, inactiveAdvance) })
            }
            val actions = Modifier.Empty.actionDispatcher()
            return when (kind) {
                Kind.Checkbox -> createMinecraftCheckboxElement(checkboxSprites[0], checkboxSprites[1], checkboxSprites[2], checkboxSprites[3], runs[0].first, runs[0].second, UiText.Literal(labels[0]), checkbox, width, enabled, actions, Modifier.Empty, null)
                Kind.Slider -> createMinecraftSliderElement(buttonSprites[0], buttonSprites[1], handles[0], handles[1], runs[0].first, runs[0].second, UiText.Literal(labels[0]), slider, width, enabled, actions, Modifier.Empty, null)
                Kind.Cycle -> createMinecraftCycleButtonElement(buttonSprites[0], buttonSprites[1], buttonSprites[2], cycle, runs, width, enabled, actions, Modifier.Empty, null)
            }
        }

        /**
         * Changes exactly the retained kind's authoritative value.
         */
        fun change(kind: Kind) {
            when (kind) {
                Kind.Checkbox -> checkbox.checked = checkbox.checked.not()
                Kind.Slider -> slider.value = if (slider.value == 0.0) 1.0 else 0.0
                Kind.Cycle -> cycle.next()
            }
        }
    }

    /**
     * Synthetic immutable pixel assets; no game or native resource is opened.
     */
    private companion object {
        fun copyRun(run: MinecraftTextRun, nativeWidth: Int = run.nativeWidth, changePolicy: Boolean = false): MinecraftTextRun {
            fun value(name: String): Any? {
                val field = run.javaClass.getDeclaredField(name)
                field.isAccessible = true
                return field.get(run)
            }
            val originalPolicy = checkNotNull(value("paintPolicy"))
            val policy = if (changePolicy) {
                val constructor = originalPolicy.javaClass.declaredConstructors.single { it.parameterCount == 3 }
                constructor.isAccessible = true
                constructor.newInstance(true, true, false)
            } else {
                originalPolicy
            }
            val constructor = run.javaClass.declaredConstructors.single { it.parameterCount == 7 }
            constructor.isAccessible = true
            return constructor.newInstance(run.text, value("glyphs"), run.size, value("sampledGlyphs"), nativeWidth, policy, run.verticalMetrics) as MinecraftTextRun
        }

        fun image(width: Int, height: Int): DrawImage = createDrawImage(IntSize(width, height), IntArray(width * height) { 0xFF426789.toInt() })
        fun glyph(): DrawImage = image(8, 8)
        fun snapshot(image: DrawImage, advance: Int): MinecraftGlyphSnapshot = MinecraftGlyphSnapshot.create(advance, image, image, image, image, image, image, image, image, image)
    }
}
