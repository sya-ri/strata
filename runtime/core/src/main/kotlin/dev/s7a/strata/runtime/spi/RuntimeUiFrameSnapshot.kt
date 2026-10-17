package dev.s7a.strata.runtime.spi

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.runtime.RetainedDrawCommands
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.runtime.semantics.SemanticsEntry
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import kotlin.jvm.JvmSynthetic

/**
 * Detaches frame output into the value shared by the session and its runtime adapter.
 * Immutable core command concatenations receive a fresh view sharing their detached branches; caller-owned lists are copied.
 * Elements retain their existing value contracts.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal fun createRuntimeUiFrame(
    size: IntSize,
    drawCommands: List<DrawCommand>,
    semantics: List<SemanticsEntry>,
): RuntimeUiFrame = RuntimeUiFrameSnapshot(size, if (drawCommands is RetainedDrawCommands) RetainedDrawCommands(listOf(drawCommands)) else drawCommands.toList(), semantics.toList())

/**
 * Publishes a frame with detached immutable semantics supplied by the retained pipeline.
 * The caller must supply a completed immutable list with no mutable construction alias; arbitrary caller-owned lists use [createRuntimeUiFrame].
 * Only detached entry values are retained, and command ownership keeps the ordinary copying contract.
 * The call is synchronous on the session owner and retains no producer, callback, node or tree.
 */
@InternalStrataRuntimeApi
@JvmSynthetic
internal fun createRuntimeUiFrameWithOwnedSemantics(
    size: IntSize,
    drawCommands: List<DrawCommand>,
    semantics: List<SemanticsEntry>,
): RuntimeUiFrame = RuntimeUiFrameSnapshot(size, if (drawCommands is RetainedDrawCommands) RetainedDrawCommands(listOf(drawCommands)) else drawCommands.toList(), semantics)

/**
 * Detached frame value; the session alone owns caching and terminal release.
 */
@OptIn(InternalStrataRuntimeApi::class)
private data class RuntimeUiFrameSnapshot(
    override val size: IntSize,
    override val drawCommands: List<DrawCommand>,
    override val semantics: List<SemanticsEntry>,
) : RuntimeUiFrame
