@file:Suppress("FunctionNaming", "LongParameterList", "ktlint:standard:function-naming")

package dev.s7a.strata.component

import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.modifier.Modifier

/**
 * Emits one fixed viewport over a logical raster assembled from independently revisioned image tiles.
 *
 * The component subscribes only to a bounded visible, overscan, and coarser-fallback working set and emits nearest-sampled portable image commands from coarsest to finest, in deterministic row-major order within each level.
 * Pan, zoom, and resize replace only the current placement plan; unchanged immutable tile images remain source-owned and are never joined or copied by this component.
 * Direct children keep fixed logical sizes, paint after the tile layer, and must use [TiledImageScope.atContentPosition] with either a fixed coordinate or an independently revisioned position source to share its content transform.
 * Pointer navigation is composed independently with `Modifier.panZoom`; this component consumes no input itself.
 * Retained attachment, measurement, source observation, and release failures propagate through the owning UI session; the component closes its observations but never closes [source], its returned state sources, or [state].
 *
 * @receiver active owner-thread screen scope.
 * @param source externally owned immutable source generation, replaced by identity when geometry or generation changes.
 * @param state caller-owned pan-and-zoom transform used by exactly one live viewport geometry owner.
 * @param size exact positive logical viewport size admitted by its parent constraints.
 * @param fit base transform used at zoom one.
 * @param cachePolicy bounded attachment-owned tile working-set policy.
 * @param modifier active behavior applied around the complete clipped viewport.
 * @param key optional stable sibling identity.
 * @param content fixed-size overlay children in source content coordinates.
 * @throws IllegalArgumentException when size, exactly representable source bounds, levels, alignment, or cache limits are invalid.
 * @throws ArithmeticException when source grid extents or edge-tile envelopes exceed the [Long] coordinate space.
 * @throws IllegalStateException when either callback scope has escaped its callback or constructing thread.
 * @throws Throwable when [content] or a source geometry getter fails; retained session work may later propagate state observer, tile subscription, or observation-release failures.
 */
public fun UiScope.TiledImage(
    source: TiledImageSource,
    state: PanZoomState,
    size: IntSize,
    fit: PanZoomFit = PanZoomFit.Contain,
    cachePolicy: TiledImageCachePolicy = TiledImageCachePolicy.Default,
    modifier: Modifier = Modifier.Empty,
    key: ElementKey<*>? = null,
    content: TiledImageScope.() -> Unit = {},
) {
    checkUsable()
    require(0 < size.width && 0 < size.height) { "Tiled image viewport dimensions must be positive." }
    val scope = TiledImageScope.create()
    val overlays =
        try {
            scope.content()
            scope.childElementsSnapshot()
        } finally {
            scope.close()
        }
    element(TiledImageElement.create(source, state, size, fit, cachePolicy, modifier, key, overlays))
}
