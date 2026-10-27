package dev.s7a.strata.runtime

import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Detaches native input eligibility while preserving portable drawing and clipping commands.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal fun DrawCommand.withoutInput(): DrawCommand = if (this is DrawCommand.Platform) DrawCommand.Platform(command.withoutInput(), bounds) else this
