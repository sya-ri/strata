package dev.s7a.strata.runtime.minecraft.canvas

import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Detached immutable pixels associated with exactly one committed native token.
 *
 * Only the native presenter creates these receipts after successful capture.
 * The image has the token's physical extent and normalized top-left orientation.
 * It contains no GPU owner and is safe to retain or read on any thread.
 */
@InternalStrataRuntimeApi
public class NativeCanvasSnapshot internal constructor(
    internal val token: NativeCanvasToken,
    internal val image: DrawImage,
)
