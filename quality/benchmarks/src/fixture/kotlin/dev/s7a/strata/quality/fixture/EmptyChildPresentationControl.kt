package dev.s7a.strata.quality.fixture

import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.runtime.UiTree
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.text.UiText

/**
 * Independent geometry and drawing-command oracle, shared with the actual JVM pixel raster check.
 */
public object EmptyChildPresentationControl {
    /**
     * Builds equivalent and changed declarations through the real retained tree and checks exact ordered output.
     */
    public fun verify(): List<List<DrawCommand>> {
        val probe = EmptyChildProbe()
        fun declaration(changed: Boolean) =
            probe.element(
                0,
                children = listOf(probe.element(1, if (changed) 1 else 0), probe.element(2, if (changed) 0 else 1)),
            )

        return UiTree().use { tree ->
            tree.update(declaration(false))

            fun capture(): List<DrawCommand> {
                check(tree.measure(Constraints()) == IntSize(3, 1))
                tree.layout()
                val commands = tree.paint()
                val semantics = tree.semantics()
                check(semantics.map { it.semantics.label } == listOf(0, 1, 2).map { UiText.Literal(it.toString()) })
                return commands
            }

            val initial = capture()
            tree.update(declaration(false))
            val equal = capture()
            check(equal == initial)
            val nodeHandles = probe.nodes.toList()
            tree.update(declaration(true))
            val changed = capture()
            check(nodeHandles.indices.all { nodeHandles[it] === probe.nodes[it] })
            check(initial == expected(false) && changed == expected(true))
            val bounds = tree.semantics().map { it.bounds }
            check(bounds == listOf(IntRect(0, 0, 3, 1), IntRect(0, 0, 2, 1), IntRect(2, 0, 3, 1)))
            check(tree.semantics().map { it.semantics.value } == listOf(0, 1, 0).map { UiText.Literal(it.toString()) })
            listOf(initial, equal, changed)
        }
    }

    private fun expected(changed: Boolean): List<DrawCommand> {
        val split = if (changed) 2 else 1
        fun color(value: Int) = ArgbColor(0xFF000000.toInt() or value)

        return listOf(
            DrawCommand.FillRectangle(IntRect(0, 0, 3, 1), color(0)),
            DrawCommand.FillRectangle(IntRect(0, 0, split, 1), color(if (changed) 1 else 0)),
            DrawCommand.FillRectangle(IntRect(split, 0, 3, 1), color(if (changed) 0 else 1)),
        )
    }
}
