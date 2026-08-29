package dev.s7a.strata.component

import dev.s7a.strata.geometry.DoubleOffset
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.state.StateSource
import kotlin.jvm.JvmSynthetic

/**
 * Callback-lifetime scope for fixed-size overlay children positioned in tiled-image content coordinates.
 *
 * Every direct child must supply [atContentPosition] with either a fixed coordinate or an independently revisioned position source.
 * Children are measured independently of zoom, retain their logical size, share the image transform for placement, paint after all tiles, and are clipped to the viewport.
 */
@StrataDsl
public sealed class TiledImageScope private constructor() : UiScope() {
    /**
     * Anchors one direct overlay child at a source content coordinate.
     *
     * The owning tiled image consumes only the innermost anchor on the direct child's modifier chain.
     * Changing the anchor invalidates overlay geometry but leaves the retained tile paint layer and its image observations intact.
     *
     * @receiver modifier chain applied to one direct overlay child.
     * @param position finite source content coordinate.
     * @param alignment placement of the child's extent relative to the transformed coordinate.
     * @return this chain with one active parent-data provider appended.
     * @throws IllegalStateException when this scope has escaped its callback or constructing thread.
     */
    public fun Modifier.atContentPosition(
        position: DoubleOffset,
        alignment: Alignment = Alignment.Center,
    ): Modifier {
        checkUsable()
        return then(TiledImageContentParentData.Element(TiledImageContentParentData.Position.Fixed(position), alignment))
    }

    /**
     * Anchors one direct overlay child at an independently revisioned source content coordinate.
     *
     * The source is observed only while the retained child is attached.
     * Notifications may arrive on any thread and commit at the shared frame cutoff; only parent measurement and overlay placement are invalidated, while tile observations, images, and paint remain unchanged.
     * Replacing the source identity closes the previous observation before subscribing to the replacement, and detach or close drops every pending position without closing the externally owned source.
     *
     * @receiver modifier chain applied to one direct overlay child.
     * @param position externally owned revision history of finite source content coordinates.
     * @param alignment placement of the child's extent relative to the committed transformed coordinate.
     * @return this chain with one active revisioned parent-data provider appended.
     * @throws IllegalStateException when this scope has escaped its callback or constructing thread.
     * @throws Throwable when attachment or source replacement cannot establish an observation, or when replacement, detach, or close cannot release one.
     */
    public fun Modifier.atContentPosition(
        position: StateSource<DoubleOffset>,
        alignment: Alignment = Alignment.Center,
    ): Modifier {
        checkUsable()
        return then(TiledImageContentParentData.Element(TiledImageContentParentData.Position.Revisioned(position), alignment))
    }

    /**
     * Internal construction boundary for one callback-lifetime overlay scope.
     */
    internal companion object {
        /**
         * Creates the private implementation used by the tiled-image builder.
         *
         * @return a fresh callback-lifetime scope.
         */
        @JvmSynthetic
        internal fun create(): TiledImageScope = ScopeImpl()
    }

    private class ScopeImpl : TiledImageScope()
}
