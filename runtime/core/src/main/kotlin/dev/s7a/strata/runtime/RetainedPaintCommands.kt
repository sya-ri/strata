package dev.s7a.strata.runtime

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * One entry's current transformed paint, keyed by immutable local-list identities, transform and measured size.
 * The tree owner replaces this value rather than retaining history, and cleanup releases it before callbacks.
 * Child membership and ancestor clips remain outside this cache and are traversed in current tree order.
 *
 * @param entry inputs snapshotted without retaining the entry itself.
 * @property beforeChildren local paint followed by this entry's optional child clip.
 * @property afterChildren the matching clip end followed by local overlays.
 * @property rootOverlays root-coordinate overlays appended in the ordinary post-child order.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class RetainedPaintCommands(
    entry: RetainedEntry,
    val beforeChildren: List<DrawCommand>,
    val afterChildren: List<DrawCommand>,
    val rootOverlays: List<DrawCommand>,
) {
    private val local = entry.localCommands
    private val overlay = entry.localOverlayCommands
    private val rootOverlay = entry.rootOverlayCommands
    private val transform = entry.localToTree
    private val size: IntSize = entry.measuredSize

    /**
     * Returns whether every input to this entry's transformed commands is unchanged.
     */
    fun matches(entry: RetainedEntry): Boolean =
        local === entry.localCommands && overlay === entry.localOverlayCommands &&
            rootOverlay === entry.rootOverlayCommands && transform == entry.localToTree && size == entry.measuredSize
}
