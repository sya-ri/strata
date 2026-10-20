package dev.s7a.strata.runtime.web

import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.render.PlatformDrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Detached native presentation transformed through common layout without rounding its CSS scale or origin.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal data class WebScaledPresentation(
    val presentation: PlatformDrawCommand,
    val source: IntRect,
    val destination: FloatRect,
) : PlatformDrawCommand
