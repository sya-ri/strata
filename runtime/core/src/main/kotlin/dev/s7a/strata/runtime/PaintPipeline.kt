package dev.s7a.strata.runtime

import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.PaintNode
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.PaintScope
import dev.s7a.strata.runtime.render.DrawCommand
import java.util.Collections

/**
 * Executes retained local paint and translates commands into tree coordinates.
 */
internal class PaintPipeline(
    private val threadGuard: ThreadGuard,
) {
    /**
     * Paints [root] in parent-before-child order.
     *
     * @param root the laid-out retained root.
     * @return tree-coordinate draw commands.
     */
    fun paint(root: RetainedNode): List<DrawCommand> {
        val output = ArrayList<DrawCommand>()
        paintNode(root, output)
        return Collections.unmodifiableList(output.toList())
    }

    private fun paintNode(
        retained: RetainedNode,
        output: MutableList<DrawCommand>,
    ) {
        val paintNode = retained.node as? PaintNode
        if (paintNode != null) {
            if (DirtyPhase.Paint in retained.dirty || retained.localCommands == null) {
                retained.dirty -= DirtyMask.of(DirtyPhase.Paint)
                val collector = LocalPaintScope(threadGuard, retained.measuredSize)
                try {
                    paintNode.paint(collector)
                    retained.localCommands = collector.snapshot()
                } finally {
                    collector.close()
                }
            }
            retained.localCommands.orEmpty().forEach { command ->
                output.add(translate(command, retained.bounds.left, retained.bounds.top))
            }
        } else {
            retained.localCommands = emptyList()
            retained.dirty -= DirtyMask.of(DirtyPhase.Paint)
        }
        retained.children.forEach { child ->
            if (child.placed) {
                paintNode(child, output)
            }
        }
    }

    private fun translate(
        command: LocalDrawCommand,
        x: Int,
        y: Int,
    ): DrawCommand =
        when (command) {
            is LocalDrawCommand.FillRectangle -> DrawCommand.FillRectangle(command.bounds + IntOffset(x, y), command.color)
        }

    /**
     * Collects local commands for one retained node.
     */
    private class LocalPaintScope(
        threadGuard: ThreadGuard,
        private val nodeSize: IntSize,
    ) : PaintScope {
        private val guard = ScopeGuard(threadGuard)

        /**
         * Commands collected during one local paint call.
         */
        val commands: MutableList<LocalDrawCommand> = ArrayList()

        override val size: IntSize
            get() {
                guard.check()
                return nodeSize
            }

        override fun fillRectangle(
            localBounds: IntRect,
            color: ArgbColor,
        ) {
            guard.check()
            commands.add(LocalDrawCommand.FillRectangle(localBounds, color))
        }

        /**
         * Snapshots commands while the callback scope remains active.
         */
        fun snapshot(): List<LocalDrawCommand> {
            guard.check()
            return commands.toList()
        }

        /**
         * Closes this local collector after the paint callback.
         */
        fun close() {
            guard.close()
        }
    }
}
