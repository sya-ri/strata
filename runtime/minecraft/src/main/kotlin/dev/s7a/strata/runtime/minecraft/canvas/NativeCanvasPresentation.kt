package dev.s7a.strata.runtime.minecraft.canvas

import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import java.util.Collections

/**
 * Immutable detached result of final native canvas preparation for one GUI batch.
 *
 * The original runtime frame is never changed.
 * This object retains only immutable commands, tokens, and optional CPU snapshots, never native storage or a producer.
 * An uncommitted Canvas remains transparent in the native display list, but immutable readiness metadata prevents an incomplete portable capture.
 * Read access and portable capture are safe from any thread; native submission and consumption remain device-thread operations.
 * Retaining this object cannot keep a native target alive after its device has retired the generation.
 */
@InternalStrataRuntimeApi
public class NativeCanvasPresentation internal constructor(
    internal val deviceId: Long,
    internal val batchId: Long,
    commands: List<DrawCommand>,
    snapshots: List<NativeCanvasSnapshot>,
    private val hasUncommittedCanvases: Boolean = false,
) {
    /**
     * Immutable display list in the exact original portable/native and clip order, with uncommitted Canvas requests omitted as transparent.
     */
    public val drawCommands: List<DrawCommand> = Collections.unmodifiableList(ArrayList(commands))

    private val snapshots: List<NativeCanvasSnapshot> = Collections.unmodifiableList(ArrayList(snapshots))

    /**
     * Creates a portable command list using only this presentation's matching immutable capture receipts.
     *
     * The entire list is validated before a result is returned.
     * Uncommitted Canvas requests, missing snapshots, mismatched extents or generations, and other platform commands fail explicitly.
     * This method never resolves a live token, performs readback, or invents replacement pixels.
     * Native snapshots become output-pixel image commands so rendering at the presentation's GUI scale preserves every physical texel.
     *
     * @return a detached unmodifiable portable list preserving drawing order, destinations, and clips.
     * @throws IllegalStateException when any requested Canvas lacks a committed generation or any platform command lacks an exact matching snapshot.
     */
    public fun capture(): List<DrawCommand> {
        check(hasUncommittedCanvases.not()) { "Portable capture requires a committed generation for every requested canvas." }
        val replacements =
            drawCommands.filterIsInstance<DrawCommand.Platform>().associateWith { command ->
                val token = command.command as? NativeCanvasToken
                checkNotNull(token) { "Portable capture requires a committed native canvas token and its snapshot." }
                val receipt = snapshots.singleOrNull { it.token === token }
                checkNotNull(receipt) { "Native canvas generation has no unique matching immutable snapshot." }
                check(receipt.image.size == token.physicalSize) { "Native canvas snapshot extent does not match its generation." }
                DrawCommand.BlitImagePixels(
                    receipt.image,
                    IntRect(0, 0, token.physicalSize.width, token.physicalSize.height),
                    command.bounds,
                )
            }
        return Collections.unmodifiableList(drawCommands.map { replacements[it] ?: it })
    }
}
