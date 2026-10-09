@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime

import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Row
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.element.Element
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.layout.Arrangement
import dev.s7a.strata.layout.HorizontalAlignment
import dev.s7a.strata.layout.VerticalAlignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.size
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.runtime.semantics.SemanticsEntry
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/** Executes original and standard layouts through real public scopes with the same retained probe children. */
internal class LinearRetainedTestFixture(
    private val original: Boolean,
    val horizontal: Boolean,
    val probe: TestProbe = TestProbe(),
) : AutoCloseable {
    val tree: UiTree = UiTree()

    /** Installs current immutable membership; keyed and positional reuse are exercised by the same adapter. */
    fun update(
        ids: List<Int>,
        weights: List<Float?> = List(ids.size) { null },
        fill: Boolean = true,
        extent: Int = 1,
        spacing: Int = 0,
        arrangement: Arrangement = Arrangement.Start,
        vertical: VerticalAlignment = VerticalAlignment.Top,
        alignment: HorizontalAlignment = HorizontalAlignment.Start,
        keyed: Boolean = true,
    ) {
        val description = if (original) {
            val children = ids.mapIndexed { index, id ->
                val weight = weights[index]
                val modifier = if (weight == null) Modifier.Empty else Modifier.Empty.then(LinearReferenceNode.WeightParentData.Element(LinearReferenceNode.WeightParentData.Data(weight, fill)))
                leaf(id, keyed, modifier, extent)
            }
            val orientation = if (horizontal) LinearReferenceNode.Orientation.Row(vertical) else LinearReferenceNode.Orientation.Column(alignment)
            LinearReferenceElement(orientation, spacing, arrangement, children)
        } else {
            evaluateComponentTree {
                if (horizontal) {
                    Row(spacing = spacing, horizontalArrangement = arrangement, verticalAlignment = vertical) {
                        ids.forEachIndexed { index, id ->
                            val weight = weights[index]
                            val modifier = if (weight == null) Modifier.Empty else Modifier.Empty.weight(weight, fill)
                            element(leaf(id, keyed, modifier, extent))
                        }
                    }
                } else {
                    Column(spacing = spacing, verticalArrangement = arrangement, horizontalAlignment = alignment) {
                        ids.forEachIndexed { index, id ->
                            val weight = weights[index]
                            val modifier = if (weight == null) Modifier.Empty else Modifier.Empty.weight(weight, fill)
                            element(leaf(id, keyed, modifier, extent))
                        }
                    }
                }
            }
        }
        tree.update(description)
    }

    /** Completes retained measure, layout, ordered paint, semantics, and actual pointer dispatch. */
    fun frame(constraints: Constraints): Output {
        val size = tree.measure(constraints)
        tree.layout()
        val commands = tree.paint()
        val semantics = tree.semantics()
        val input = tree.dispatchPointer(PointerEvent.Move(IntOffset(1, 1)))
        return Output(size, commands, semantics, input)
    }

    private fun leaf(id: Int, keyed: Boolean, modifier: Modifier, extent: Int): Element {
        val tag = TestProbe.ProbeId(id.toString())
        val sized = if (horizontal) modifier.size(extent, 3) else modifier.size(3, extent)
        return probe.element(tag, key = if (keyed) tag else null, modifier = sized)
    }

    /** Releases every claimed original/candidate child on normal or exceptional paths. */
    override fun close() = tree.close()

    /** Detached complete portable output, without a retained node or source reference. */
    data class Output(
        val size: IntSize,
        val commands: List<DrawCommand>,
        val semantics: List<SemanticsEntry>,
        val input: InputResult,
    )
}
