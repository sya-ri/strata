@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime

import dev.s7a.strata.component.FlowRow
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.FocusEvent
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.KeyCode
import dev.s7a.strata.input.KeyboardEvent
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.layout.Arrangement
import dev.s7a.strata.layout.VerticalAlignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.onFocusChanged
import dev.s7a.strata.modifier.onKeyEvent
import dev.s7a.strata.modifier.size
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.runtime.semantics.SemanticsEntry
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/** Complete original/candidate public scopes, real painted/semantic children, actual pointer and focus delivery. */
internal class FlowRetainedTestFixture(
    private val original: Boolean,
    val probe: TestProbe = TestProbe(),
) : AutoCloseable {
    val tree: UiTree = UiTree()
    val focus: MutableList<Pair<Int, FocusEvent>> = mutableListOf()
    val keys: MutableList<Int> = mutableListOf()

    /** Installs direct immutable children without synthetic row parents or candidate-derived geometry. */
    fun update(
        ids: List<Int>,
        widths: List<Int> = List(ids.size) { 1 },
        heights: List<Int> = List(ids.size) { it % 3 + 1 },
        horizontalSpacing: Int = 1,
        verticalSpacing: Int = 1,
        arrangement: Arrangement = Arrangement.Start,
        alignment: VerticalAlignment = VerticalAlignment.Top,
        overrides: List<VerticalAlignment?> = List(ids.size) { null },
        keyed: Boolean = true,
    ) {
        val description = if (original) {
            val children = ids.mapIndexed { index, id ->
                val overridden = overrides[index]
                val modifier = if (overridden == null) Modifier.Empty else Modifier.Empty.then(FlowReferenceNode.AlignmentParentData.Element(FlowReferenceNode.AlignmentParentData.Data(overridden)))
                leaf(id, keyed, modifier, widths[index], heights[index])
            }
            FlowReferenceElement(horizontalSpacing, verticalSpacing, arrangement, alignment, children, target)
        } else {
            evaluateComponentTree {
                FlowRow(key = target, horizontalSpacing = horizontalSpacing, verticalSpacing = verticalSpacing, horizontalArrangement = arrangement, verticalAlignment = alignment) {
                    ids.forEachIndexed { index, id ->
                        val overridden = overrides[index]
                        val modifier = if (overridden == null) Modifier.Empty else Modifier.Empty.align(overridden)
                        element(leaf(id, keyed, modifier, widths[index], heights[index]))
                    }
                }
            }
        }
        tree.update(description)
    }

    /** Completes real layout/paint/semantics and pointer/focused keyboard dispatch in retained order. */
    fun frame(constraints: Constraints): Output {
        val size = tree.measure(constraints)
        tree.layout()
        val commands = tree.paint()
        val semantics = tree.semantics()
        val input = tree.dispatchPointer(PointerEvent.Move(IntOffset(0, 0)))
        val key = tree.dispatchKeyboard(KeyboardEvent.Press(KeyCode.Tab, 0))
        val focused = tree.dispatchKeyboard(KeyboardEvent.Press(KeyCode.Enter, 0))
        return Output(size, commands, semantics, input, key, focused, focus.toList(), keys.toList())
    }

    private fun leaf(id: Int, keyed: Boolean, modifier: Modifier, width: Int, height: Int): Element {
        val tag = TestProbe.ProbeId(id.toString())
        val sized = modifier.size(width, height).onFocusChanged { focus += id to it }.onKeyEvent {
            keys += id
            if (it.key == KeyCode.Enter) InputResult.Consumed else InputResult.Ignored
        }
        return probe.element(tag, key = if (keyed) tag else null, modifier = sized)
    }

    /** Releases all active direct children and callbacks on the owning tree thread. */
    override fun close() = tree.close()

    /** Detached full portable output and actual focus/input callback observations. */
    data class Output(
        val size: IntSize,
        val commands: List<DrawCommand>,
        val semantics: List<SemanticsEntry>,
        val input: InputResult,
        val tab: InputResult,
        val key: InputResult,
        val focus: List<Pair<Int, FocusEvent>>,
        val keys: List<Int>,
    )

    /** Stable typed root key used only by independent diagnostics. */
    val target: ElementKey<*> = ElementKey(Target.Flow)

    private enum class Target { Flow }
}

