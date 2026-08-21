package dev.s7a.strata.runtime

import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.node.ClipChildrenNode
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.OverlayPaintNode
import dev.s7a.strata.node.PaintNode
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.PaintScope
import dev.s7a.strata.render.PlatformDrawCommand
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import java.util.Collections

/**
 * Executes retained local paint and translates commands into tree coordinates.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class PaintPipeline(
    private val threadGuard: ThreadGuard,
) {
    /**
     * Paints [root] in parent-before-child order.
     *
     * @param root the laid-out retained root.
     * @return tree-coordinate draw commands.
     */
    fun paint(root: RetainedEntry): List<DrawCommand> {
        val output = ArrayList<DrawCommand>()
        paintNode(root, output)
        return Collections.unmodifiableList(output.toList())
    }

    private fun paintNode(
        retained: RetainedEntry,
        output: MutableList<DrawCommand>,
    ) {
        updateLocalCommands(retained)
        appendTranslated(retained.localCommands.orEmpty(), retained, output)
        val clipsChildren = retained.node is ClipChildrenNode
        if (clipsChildren) {
            output.add(DrawCommand.PushClip(retained.bounds))
        }
        for (index in 0 until retained.effectiveChildCount) {
            val child = retained.effectiveChildAt(index)
            if (child.placed) {
                paintNode(child, output)
            }
        }
        if (clipsChildren) {
            output.add(DrawCommand.PopClip)
        }
        appendTranslated(retained.localOverlayCommands.orEmpty(), retained, output)
    }

    private fun updateLocalCommands(retained: RetainedEntry) {
        val paintNode = retained.node as? PaintNode
        val overlayNode = retained.node as? OverlayPaintNode
        if (
            DirtyPhase.Paint !in retained.dirty &&
            retained.localCommands != null &&
            retained.localOverlayCommands != null
        ) {
            return
        }
        retained.dirty -= DirtyMask.of(DirtyPhase.Paint)
        retained.localCommands = collect(retained, paintNode?.let { node -> node::paint })
        retained.localOverlayCommands = collect(retained, overlayNode?.let { node -> node::paintOverlay })
    }

    private fun collect(
        retained: RetainedEntry,
        callback: ((PaintScope) -> Unit)?,
    ): List<LocalDrawCommand> {
        if (callback == null) {
            return emptyList()
        }
        val collector = LocalPaintScope(threadGuard, retained.measuredSize)
        return try {
            callback(collector)
            collector.snapshot()
        } finally {
            collector.close()
        }
    }

    private fun appendTranslated(
        commands: List<LocalDrawCommand>,
        retained: RetainedEntry,
        output: MutableList<DrawCommand>,
    ) {
        commands.forEach { command ->
            output.add(translate(command, retained.bounds.left, retained.bounds.top))
        }
    }

    private fun translate(
        command: LocalDrawCommand,
        x: Int,
        y: Int,
    ): DrawCommand =
        when (command) {
            is LocalDrawCommand.FillRectangle -> {
                DrawCommand.FillRectangle(command.bounds + IntOffset(x, y), command.color)
            }

            is LocalDrawCommand.BlitImage -> {
                DrawCommand.BlitImage(command.image, command.source, command.destination + IntOffset(x, y))
            }

            is LocalDrawCommand.Platform -> {
                DrawCommand.Platform(command.command, command.bounds + IntOffset(x, y))
            }
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

        override fun blitImage(
            image: DrawImage,
            source: IntRect,
            localDestination: IntRect,
        ) {
            guard.check()
            commands.add(LocalDrawCommand.BlitImage(image, source, localDestination))
        }

        override fun drawPlatform(
            command: PlatformDrawCommand,
            localBounds: IntRect,
        ) {
            guard.check()
            commands.add(LocalDrawCommand.Platform(command, localBounds))
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
