package dev.s7a.strata.runtime

import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.node.ClipChildrenNode
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.OverlayPaintNode
import dev.s7a.strata.node.PaintNode
import dev.s7a.strata.node.RootOverlayPaintNode
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.PaintScope
import dev.s7a.strata.render.PlatformDrawCommand
import dev.s7a.strata.render.RootOverlayPaintScope
import dev.s7a.strata.render.SampledImageOrientation
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import java.util.Collections

/**
 * Executes retained local paint and transforms commands into tree coordinates.
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
        val rootOverlays = ArrayList<DrawCommand>()
        paintNode(root, root.measuredSize, output, rootOverlays)
        output.addAll(rootOverlays)
        return Collections.unmodifiableList(output.toList())
    }

    private fun paintNode(
        retained: RetainedEntry,
        viewport: IntSize,
        output: MutableList<DrawCommand>,
        rootOverlays: MutableList<DrawCommand>,
    ) {
        updateLocalCommands(retained, viewport)
        appendTransformed(retained.localCommands.orEmpty(), retained, output)
        val clipsChildren = retained.node is ClipChildrenNode
        if (clipsChildren) {
            output.add(DrawCommand.PushClip(retained.bounds))
        }
        for (index in 0 until retained.effectiveChildCount) {
            val child = retained.effectiveChildAt(index)
            if (child.placed) {
                paintNode(child, viewport, output, rootOverlays)
            }
        }
        if (clipsChildren) {
            output.add(DrawCommand.PopClip)
        }
        appendTransformed(retained.localOverlayCommands.orEmpty(), retained, output)
        appendUntranslated(retained.rootOverlayCommands.orEmpty(), rootOverlays)
    }

    private fun updateLocalCommands(
        retained: RetainedEntry,
        viewport: IntSize,
    ) {
        val paintNode = retained.node as? PaintNode
        val overlayNode = retained.node as? OverlayPaintNode
        val rootOverlayNode = retained.node as? RootOverlayPaintNode
        val rootOverlayGeometryChanged = retained.rootOverlayAnchor != retained.bounds || retained.rootOverlayViewport != viewport
        val localNeedsUpdate =
            DirtyPhase.Paint in retained.dirty || retained.localCommands == null || retained.localOverlayCommands == null
        if (localNeedsUpdate) {
            retained.dirty -= DirtyMask.of(DirtyPhase.Paint)
            retained.localCommands = collect(retained, paintNode?.let { node -> node::paint })
            retained.localOverlayCommands = collect(retained, overlayNode?.let { node -> node::paintOverlay })
        }
        if (localNeedsUpdate || retained.rootOverlayCommands == null || rootOverlayGeometryChanged) {
            retained.rootOverlayCommands = collectRootOverlay(retained, viewport, rootOverlayNode)
            retained.rootOverlayAnchor = retained.bounds
            retained.rootOverlayViewport = viewport
        }
    }

    private fun collectRootOverlay(
        retained: RetainedEntry,
        viewport: IntSize,
        node: RootOverlayPaintNode?,
    ): List<LocalDrawCommand> {
        if (node == null) return emptyList()
        val collector = RootOverlayPaintScopeImplementation(threadGuard, viewport, retained.bounds)
        return try {
            node.paintRootOverlay(collector)
            collector.snapshot()
        } finally {
            collector.close()
        }
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

    private fun appendTransformed(
        commands: List<LocalDrawCommand>,
        retained: RetainedEntry,
        output: MutableList<DrawCommand>,
    ) {
        commands.forEach { command ->
            transform(command, retained.localToTree)?.let(output::add)
        }
    }

    private fun appendUntranslated(
        commands: List<LocalDrawCommand>,
        output: MutableList<DrawCommand>,
    ) {
        commands.forEach { command -> output.add(translate(command, 0, 0)) }
    }

    private fun transform(
        command: LocalDrawCommand,
        transform: TreeTransform,
    ): DrawCommand? {
        val translation = transform.integerTranslationOrNull()
        if (translation != null) {
            return translate(command, translation.x, translation.y)
        }
        return when (command) {
            is LocalDrawCommand.PushClip -> {
                DrawCommand.PushClip(transform.enclosing(command.bounds))
            }

            LocalDrawCommand.PopClip -> {
                DrawCommand.PopClip
            }

            is LocalDrawCommand.FillRectangle -> {
                transform.mapFractional(command.bounds).drawCommandOrNull { destination ->
                    DrawCommand.SampledImage(
                        SOLID_IMAGE,
                        SOLID_SOURCE,
                        destination,
                        command.color,
                        0f,
                    )
                }
            }

            is LocalDrawCommand.BlitImage -> {
                transform.mapFractional(command.destination).drawCommandOrNull { destination ->
                    DrawCommand.SampledImage(
                        command.image,
                        command.source.toFloatRect(),
                        destination,
                        ArgbColor(-1),
                        0f,
                    )
                }
            }

            is LocalDrawCommand.SampledImage -> {
                transform.mapFractional(command.destination).drawCommandOrNull { destination ->
                    DrawCommand.SampledImage(
                        command.image,
                        command.source,
                        destination,
                        command.tint,
                        command.alphaCutoff,
                        command.orientation,
                    )
                }
            }

            is LocalDrawCommand.Platform -> {
                throw UnsupportedOperationException(
                    "Platform draw commands require an exact integer-translation child transform.",
                )
            }
        }
    }

    private fun translate(
        command: LocalDrawCommand,
        x: Int,
        y: Int,
    ): DrawCommand =
        when (command) {
            is LocalDrawCommand.PushClip -> {
                DrawCommand.PushClip(command.bounds + IntOffset(x, y))
            }

            LocalDrawCommand.PopClip -> {
                DrawCommand.PopClip
            }

            is LocalDrawCommand.FillRectangle -> {
                DrawCommand.FillRectangle(command.bounds + IntOffset(x, y), command.color)
            }

            is LocalDrawCommand.BlitImage -> {
                DrawCommand.BlitImage(command.image, command.source, command.destination + IntOffset(x, y))
            }

            is LocalDrawCommand.SampledImage -> {
                DrawCommand.SampledImage(
                    command.image,
                    command.source,
                    command.destination + IntOffset(x, y),
                    command.tint,
                    command.alphaCutoff,
                    command.orientation,
                )
            }

            is LocalDrawCommand.Platform -> {
                DrawCommand.Platform(command.command, command.bounds + IntOffset(x, y))
            }
        }

    private fun IntRect.toFloatRect(): FloatRect = FloatRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())

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

        override fun withClip(
            localBounds: IntRect,
            content: () -> Unit,
        ) {
            guard.check()
            commands.add(LocalDrawCommand.PushClip(localBounds))
            val failures = FailureAccumulator()
            try {
                failures.capture(content)
            } finally {
                failures.capture { commands.add(LocalDrawCommand.PopClip) }
            }
            failures.throwIfPresent()
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

        override fun sampledImage(
            image: DrawImage,
            source: FloatRect,
            localDestination: FloatRect,
            tint: ArgbColor,
            alphaCutoff: Float,
        ) {
            sampledImage(image, source, localDestination, SampledImageOrientation.Normal, tint, alphaCutoff)
        }

        override fun sampledImage(
            image: DrawImage,
            source: FloatRect,
            localDestination: FloatRect,
            orientation: SampledImageOrientation,
            tint: ArgbColor,
            alphaCutoff: Float,
        ) {
            guard.check()
            commands.add(LocalDrawCommand.SampledImage(image, source, localDestination, tint, alphaCutoff, orientation))
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

    /**
     * Collects one root overlay in root coordinates.
     */
    private class RootOverlayPaintScopeImplementation(
        threadGuard: ThreadGuard,
        viewport: IntSize,
        private val anchor: IntRect,
    ) : RootOverlayPaintScope {
        private val delegate = LocalPaintScope(threadGuard, viewport)

        override val size: IntSize
            get() = delegate.size

        override val anchorBounds: IntRect
            get() = anchor

        override fun withClip(
            localBounds: IntRect,
            content: () -> Unit,
        ) {
            delegate.withClip(localBounds, content)
        }

        override fun fillRectangle(
            localBounds: IntRect,
            color: ArgbColor,
        ) {
            delegate.fillRectangle(localBounds, color)
        }

        override fun blitImage(
            image: DrawImage,
            source: IntRect,
            localDestination: IntRect,
        ) {
            delegate.blitImage(image, source, localDestination)
        }

        override fun sampledImage(
            image: DrawImage,
            source: FloatRect,
            localDestination: FloatRect,
            tint: ArgbColor,
            alphaCutoff: Float,
        ) {
            delegate.sampledImage(image, source, localDestination, tint, alphaCutoff)
        }

        override fun sampledImage(
            image: DrawImage,
            source: FloatRect,
            localDestination: FloatRect,
            orientation: SampledImageOrientation,
            tint: ArgbColor,
            alphaCutoff: Float,
        ) {
            delegate.sampledImage(image, source, localDestination, orientation, tint, alphaCutoff)
        }

        override fun drawPlatform(
            command: PlatformDrawCommand,
            localBounds: IntRect,
        ) {
            delegate.drawPlatform(command, localBounds)
        }

        fun snapshot(): List<LocalDrawCommand> = delegate.snapshot()

        fun close() {
            delegate.close()
        }
    }

    private companion object {
        val SOLID_IMAGE: DrawImage = createDrawImage(IntSize(1, 1), intArrayOf(-1))
        val SOLID_SOURCE: FloatRect = FloatRect(0f, 0f, 1f, 1f)
    }
}

private inline fun FloatRect.drawCommandOrNull(create: (FloatRect) -> DrawCommand): DrawCommand? = if (width <= 0f || height <= 0f) null else create(this)
