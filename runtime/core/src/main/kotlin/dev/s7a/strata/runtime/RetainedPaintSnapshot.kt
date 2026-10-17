package dev.s7a.strata.runtime

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * One current immutable subtree snapshot, keyed by local paint, viewport and propagated subtree invalidation.
 * Ordinary commands and post-child root overlays share current child snapshots without retaining entries.
 * The tree owner replaces this value on change and clears it before structural removal or terminal callbacks.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class RetainedPaintSnapshot(
    val local: RetainedPaintCommands,
    val viewport: IntSize,
    val commands: RetainedDrawCommands,
    val rootOverlays: List<DrawCommand>,
) {
    /**
     * Returns whether local paint, geometry and every reachable child's paint remain current.
     */
    fun matches(
        entry: RetainedEntry,
        viewport: IntSize,
    ): Boolean = entry.paintSubtreeDirty.not() && (DirtyPhase.Paint in entry.dirty).not() && this.viewport == viewport && local.matches(entry)
}
