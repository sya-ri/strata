package dev.s7a.strata.runtime.minecraft.canvas

import dev.s7a.strata.render.PlatformDrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Immutable paint-cache identifier for one native canvas attachment.
 *
 * It contains no native handle, source, callback, node, host, or device reference.
 * Only its matching live native device may resolve it during final native preparation.
 * Retaining it after detachment does not extend any resource lifetime; resolution then fails before output.
 */
@InternalStrataRuntimeApi
public class NativeCanvasRequest internal constructor(
    internal val deviceId: Long,
    internal val attachmentId: Long,
) : PlatformDrawCommand
