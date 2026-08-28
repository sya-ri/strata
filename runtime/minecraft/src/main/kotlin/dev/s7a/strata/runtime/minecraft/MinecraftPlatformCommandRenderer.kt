package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.render.PlatformDrawCommand
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Extensible version-owned renderer for one family of immutable platform draw payloads.
 *
 * The adapter owns this renderer; core neither dispatches on concrete components nor retains the renderer in a frame.
 * All operations run synchronously on the adapter's render thread.
 * A renderer may reference native services, but its accepted command payloads must obey their immutable ownership contracts.
 *
 * @param T native drawing context borrowed only during [render].
 */
@InternalStrataRuntimeApi
public interface MinecraftPlatformCommandRenderer<T> {
    /**
     * Validates whether this renderer accepts an immutable payload before any drawing in the batch.
     *
     * This operation must not draw, produce native frames, or mutate the command.
     * Invalid foreign or expired payloads may throw instead of returning false.
     *
     * @param command platform-owned immutable payload.
     * @return true only when this renderer can execute the payload in the current batch.
     * @throws Throwable when adapter ownership or payload validity fails.
     */
    public fun accepts(command: PlatformDrawCommand): Boolean

    /**
     * Draws a previously validated payload at its ordered position under the presenter's active clip.
     *
     * @param target borrowed native drawing context, never retained after this call.
     * @param command immutable payload and logical destination bounds.
     * @throws Throwable when native drawing fails; the outer GUI-consumption finally path still protects resources.
     */
    public fun render(
        target: T,
        command: DrawCommand.Platform,
    )
}
