package dev.s7a.strata.runtime

import dev.s7a.strata.runtime.diagnostics.UiRenderNodeId
import dev.s7a.strata.runtime.diagnostics.UiRenderNodeKind
import dev.s7a.strata.runtime.diagnostics.UiRenderNodeSnapshot
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * One bounded interval record; retired records contain no retained entry reference.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class RenderNodeRecord(
    val id: UiRenderNodeId,
    var entry: RetainedEntry?,
    val kind: UiRenderNodeKind,
    val typeName: String,
) {
    /**
     * Last known effective parent, including modifier ancestry.
     */
    var parentId: UiRenderNodeId? = null

    /**
     * Primitive interval counts belonging to this identity.
     */
    val counts = RenderWorkCounts()

    /**
     * Produces evidence with no node, key, callback, or state value references.
     */
    fun snapshot(): UiRenderNodeSnapshot = UiRenderNodeSnapshot(id, parentId, kind, typeName, entry == null, counts.totals(), counts.operations())
}
