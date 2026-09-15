package dev.s7a.strata.runtime.spi

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.runtime.semantics.SemanticsEntry
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Copies frame output into the value shared by the session and its runtime adapter.
 * Collection membership is detached; elements retain their existing value contracts.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal fun createRuntimeUiFrame(
    size: IntSize,
    drawCommands: List<DrawCommand>,
    semantics: List<SemanticsEntry>,
): RuntimeUiFrame = RuntimeUiFrameSnapshot(size, drawCommands.toList(), semantics.toList())

/**
 * Detached frame value; the session alone owns caching and terminal release.
 */
@OptIn(InternalStrataRuntimeApi::class)
private data class RuntimeUiFrameSnapshot(
    override val size: IntSize,
    override val drawCommands: List<DrawCommand>,
    override val semantics: List<SemanticsEntry>,
) : RuntimeUiFrame
