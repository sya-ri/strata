@file:Suppress("FunctionNaming", "ktlint:standard:function-naming")

package dev.s7a.strata.component

import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.modifier.Modifier

/**
 * Emits one input-passive rectangular region displaying an externally owned [source].
 *
 * The whole source image stretches to the explicit positive logical [size] using nearest sampling.
 * Source image extent changes update painting without changing that destination.
 * Pointer and keyboard forwarding are composed through ordinary modifiers; the canvas acquires no input or focus implicitly.
 * The source is opened only when a retained node attaches, never during declaration or measurement.
 * Every attachment owns a separate binding, and declaration does not transfer ownership of the source itself.
 *
 * @param source externally owned CPU image, revisioned image stream, or version-runtime native source.
 * @param size exact positive logical destination size, which must be admitted by the parent constraints.
 * @param modifier active layout, paint, and optional input behavior around the canvas.
 * @param key optional stable sibling identity.
 * @throws IllegalArgumentException when either requested dimension is zero.
 * @throws IllegalStateException when this callback-lifetime scope is used after its callback or on another thread.
 */
public fun UiScope.Canvas(
    source: CanvasSource,
    size: IntSize,
    modifier: Modifier = Modifier.Empty,
    key: ElementKey<*>? = null,
) {
    checkUsable()
    require(0 < size.width && 0 < size.height) { "Canvas destination dimensions must be positive." }
    element(CanvasElement(source, size, modifier, key))
}
