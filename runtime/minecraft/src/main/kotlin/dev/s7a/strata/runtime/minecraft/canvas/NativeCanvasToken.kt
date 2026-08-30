package dev.s7a.strata.runtime.minecraft.canvas

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.PlatformDrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Immutable identity of one committed native color generation.
 *
 * The token is safe to retain from any thread and contains only scalar identifiers and an immutable extent.
 * It owns no native storage and cannot resolve live GPU state in Headless.
 * Native resolution requires a matching unsettled presentation on the owning render thread.
 *
 * @property physicalSize the committed generation's positive, top-left-origin physical extent.
 */
@InternalStrataRuntimeApi
public class NativeCanvasToken internal constructor(
    internal val deviceId: Long,
    internal val attachmentId: Long,
    internal val generation: Long,
    public val physicalSize: IntSize,
) : PlatformDrawCommand
