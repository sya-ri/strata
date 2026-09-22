@file:Suppress("LongParameterList")

package dev.s7a.strata.projection

import dev.s7a.strata.component.PanZoomFit
import dev.s7a.strata.component.PanZoomState
import dev.s7a.strata.component.TiledImageCachePolicy
import dev.s7a.strata.component.TiledImageElement
import dev.s7a.strata.component.TiledImageLevel
import dev.s7a.strata.component.TiledImageSource
import dev.s7a.strata.component.TiledImageTileLayerElement
import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.geometry.LongRect
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Trusted reconstruction boundary reusing the standard viewport layout and sampled tile renderer.
 * Source identity is stable for one immutable geometry generation; children retain their remote declaration identities.
 */
@InternalStrataRuntimeApi
public object StandardTiledImageProjections {
    /**
     * Validates detached geometry before a remote update can acquire or replace client resources.
     */
    public fun validate(
        bounds: LongRect,
        levels: List<TiledImageLevel>,
        size: IntSize,
    ): Unit = TiledImageElement.validateGeometry(bounds, levels, size)

    /**
     * Creates the standard viewport around a projected tile layer and ordinary positioned overlays.
     */
    public fun viewport(
        sourceIdentity: Any,
        bounds: LongRect,
        levels: List<TiledImageLevel>,
        state: PanZoomState,
        size: IntSize,
        fit: PanZoomFit,
        modifier: Modifier,
        key: ElementKey<*>?,
        children: List<Element>,
    ): Element = TiledImageElement.projected(sourceIdentity, bounds, levels, state, size, fit, modifier, key, children)

    /**
     * Creates the standard bounded tile observation and paint layer over a client-owned revision source.
     */
    public fun layer(
        source: TiledImageSource,
        state: PanZoomState,
        size: IntSize,
        policy: TiledImageCachePolicy,
    ): Element = TiledImageTileLayerElement(source, source.bounds, source.levels, state, size, policy)
}
