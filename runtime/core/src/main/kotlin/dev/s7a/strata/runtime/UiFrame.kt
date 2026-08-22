package dev.s7a.strata.runtime

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.runtime.semantics.SemanticsEntry
import java.util.Collections

/**
 * Immutable output of one successful synchronous UI session frame.
 *
 * The frame owns defensive immutable snapshots of its drawing and semantics collections.
 * An owning session may retain and reuse this instance while the retained tree revision and root constraints remain unchanged.
 *
 * @property size the measured root size.
 * @property drawCommands the drawing commands in tree coordinates and emission order.
 * @property semantics the semantics entries in tree coordinates and traversal order.
 */
internal class UiFrame internal constructor(
    val size: IntSize,
    drawCommands: List<DrawCommand>,
    semantics: List<SemanticsEntry>,
) {
    val drawCommands: List<DrawCommand> = immutableList(drawCommands)
    val semantics: List<SemanticsEntry> = immutableList(semantics)

    override fun equals(other: Any?): Boolean =
        other is UiFrame &&
            size == other.size &&
            drawCommands == other.drawCommands &&
            semantics == other.semantics

    override fun hashCode(): Int {
        var result = size.hashCode()
        result = 31 * result + drawCommands.hashCode()
        result = 31 * result + semantics.hashCode()
        return result
    }

    override fun toString(): String = "UiFrame(size=$size, drawCommands=$drawCommands, semantics=$semantics)"

    private fun <T> immutableList(values: List<T>): List<T> = Collections.unmodifiableList(values.toList())
}
