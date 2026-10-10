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
import dev.s7a.strata.runtime.diagnostics.UiRenderMetric
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Executes retained local paint and transforms commands into tree coordinates.
 * Empty subtree root-overlay results use the immutable zero-resource list; nonempty results preserve ordered concatenation.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class PaintPipeline(
    private val ownerGuard: OwnerGuard,
    private val monitoring: RenderMonitoring = RenderMonitoring(),
) {
    /**
     * Paints [root] in parent-before-child order.
     *
     * @param root the laid-out retained root.
     * @return tree-coordinate draw commands.
     */
    fun paint(root: RetainedEntry): List<DrawCommand> {
        val snapshot = paintNode(root, root.measuredSize)
        return if (snapshot.rootOverlays.isEmpty()) snapshot.commands else RetainedDrawCommands(listOf(snapshot.commands, snapshot.rootOverlays))
    }

    private fun paintNode(
        retained: RetainedEntry,
        viewport: IntSize,
    ): RetainedPaintSnapshot {
        retained.paintSnapshot?.let { if (it.matches(retained, viewport)) return it }
        retained.paintSubtreeDirty = false
        updateLocalCommands(retained, viewport)
        val commands = transformedCommands(retained)
        val parts = ArrayList<List<DrawCommand>>(retained.effectiveChildCount + 2)
        parts.add(commands.beforeChildren)
        var rootOverlays: MutableList<List<DrawCommand>>? = null
        for (index in 0 until retained.effectiveChildCount) {
            val child = retained.effectiveChildAt(index)
            if (child.placed) {
                val snapshot = paintNode(child, viewport)
                parts.add(snapshot.commands)
                if (snapshot.rootOverlays.isNotEmpty()) {
                    val overlays = rootOverlays ?: ArrayList()
                    overlays.add(snapshot.rootOverlays)
                    rootOverlays = overlays
                }
            }
        }
        parts.add(commands.afterChildren)
        if (commands.rootOverlays.isNotEmpty()) {
            val overlays = rootOverlays ?: ArrayList()
            overlays.add(commands.rootOverlays)
            rootOverlays = overlays
        }
        val overlays = rootOverlays?.let(::RetainedDrawCommands) ?: emptyList()
        return RetainedPaintSnapshot(commands, viewport, RetainedDrawCommands(parts), overlays).also { retained.paintSnapshot = it }
    }

    private fun transformedCommands(retained: RetainedEntry): RetainedPaintCommands {
        retained.transformedPaint?.let { if (it.matches(retained)) return it }
        val clipsChildren = retained.node is ClipChildrenNode
        val commands =
            RetainedPaintCommands(
                retained,
                buildList {
                    appendTransformed(retained.localCommands.orEmpty(), retained, this)
                    if (clipsChildren) {
                        add(transformClip(IntRect(0, 0, retained.measuredSize.width, retained.measuredSize.height), retained.localToTree))
                    }
                },
                buildList {
                    if (clipsChildren) add(DrawCommand.PopClip)
                    appendTransformed(retained.localOverlayCommands.orEmpty(), retained, this)
                },
                buildList { retained.rootOverlayCommands.orEmpty().forEach { add(translate(it, 0, 0)) } },
            )
        retained.transformedPaint = commands
        return commands
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
            retained.localCommands = collect(retained, UiRenderMetric.Paint, paintNode?.let { node -> node::paint })
            retained.localOverlayCommands = collect(retained, UiRenderMetric.OverlayPaint, overlayNode?.let { node -> node::paintOverlay })
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
        val collector = RootOverlayPaintScopeImplementation(ownerGuard, viewport, retained.bounds)
        return try {
            monitoring.record(UiRenderMetric.RootOverlayPaint, retained)
            node.paintRootOverlay(collector)
            collector.snapshot()
        } finally {
            collector.close()
        }
    }

    private fun collect(
        retained: RetainedEntry,
        metric: UiRenderMetric,
        callback: ((PaintScope) -> Unit)?,
    ): List<LocalDrawCommand> {
        if (callback == null) {
            return emptyList()
        }
        val collector = LocalPaintScope(ownerGuard, retained.measuredSize)
        return try {
            monitoring.record(metric, retained)
            callback(collector)
            composeDenseBlits(collector.snapshot())
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
            if (command is LocalDrawCommand.ComposedBlits) {
                val blits = if (retained.localToTree.integerTranslationOrNull() == null) command.original else command.commands
                blits.forEach { original -> transform(original, retained.localToTree)?.let(output::add) }
            } else {
                transform(command, retained.localToTree)?.let(output::add)
            }
        }
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
                transformClip(command.bounds, transform)
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

            is LocalDrawCommand.ComposedBlits -> {
                error("Composed blits must expand before fractional transformation.")
            }

            is LocalDrawCommand.Platform -> {
                throw UnsupportedOperationException(
                    "Platform draw commands require an exact integer-translation child transform.",
                )
            }
        }
    }

    private fun transformClip(
        bounds: IntRect,
        transform: TreeTransform,
    ): DrawCommand {
        val enclosing = transform.enclosing(bounds)
        if (transform.integerTranslationOrNull() != null) return DrawCommand.PushClip(enclosing)
        val exact = transform.mapFractional(bounds)
        val horizontal = exact.left.toDouble() == enclosing.left.toDouble() && exact.right.toDouble() == enclosing.right.toDouble()
        val vertical = exact.top.toDouble() == enclosing.top.toDouble() && exact.bottom.toDouble() == enclosing.bottom.toDouble()
        return if (horizontal && vertical) {
            DrawCommand.PushClip(enclosing)
        } else {
            DrawCommand.PushFractionalClip(exact)
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

            is LocalDrawCommand.ComposedBlits -> {
                error("Composed blits must expand before transformation.")
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
        ownerGuard: OwnerGuard,
        private val nodeSize: IntSize,
    ) : PaintScope {
        private val guard = ScopeGuard(ownerGuard)

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
        ownerGuard: OwnerGuard,
        viewport: IntSize,
        private val anchor: IntRect,
    ) : RootOverlayPaintScope {
        private val delegate = LocalPaintScope(ownerGuard, viewport)

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
