package dev.s7a.strata.runtime

import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Immutable concatenation of already detached command lists, sharing unchanged paint branches.
 * Parts contain only immutable command values and other concatenations, never retained entries or owners.
 * Indexed reads use prefix lengths; iteration keeps only a depth-bounded cursor without allocating per branch.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class RetainedDrawCommands(
    parts: List<List<DrawCommand>>,
) : AbstractList<DrawCommand>() {
    private val parts = parts.filter { it.isNotEmpty() }
    private val ends = IntArray(this.parts.size)

    init {
        var total = 0
        this.parts.forEachIndexed { index, part ->
            if (Int.MAX_VALUE - total < part.size) throw ArithmeticException("Command list size exceeds Int.MAX_VALUE.")
            total += part.size
            ends[index] = total
        }
    }

    override val size: Int = ends.lastOrNull() ?: 0

    override fun get(index: Int): DrawCommand {
        if (index < 0 || size <= index) throw IndexOutOfBoundsException("Command index $index outside 0 until $size.")
        var first = 0
        var last = parts.lastIndex
        while (first < last) {
            val middle = (first + last) ushr 1
            if (index < ends[middle]) last = middle else first = middle + 1
        }
        val start = if (first == 0) 0 else ends[first - 1]
        return parts[first][index - start]
    }

    override fun iterator(): Iterator<DrawCommand> = Cursor(this)

    /**
     * Iterates shared parts with one reusable position per active nesting level.
     */
    private class Cursor(
        root: RetainedDrawCommands,
    ) : Iterator<DrawCommand> {
        private val parents = arrayListOf(root)
        private var positions = IntArray(8)
        private var leaf: List<DrawCommand>? = null
        private var index = 0

        override fun hasNext(): Boolean {
            if (index < (leaf?.size ?: 0)) return true
            leaf = null
            while (parents.isNotEmpty()) {
                val depth = parents.lastIndex
                val parent = parents[depth]
                val position = positions[depth]
                if (parent.parts.size <= position) {
                    parents.removeAt(depth)
                    continue
                }
                positions[depth] = position + 1
                val part = parent.parts[position]
                if (part is RetainedDrawCommands) {
                    if (positions.size <= parents.size) positions = positions.copyOf(positions.size * 2)
                    positions[parents.size] = 0
                    parents.add(part)
                } else {
                    leaf = part
                    index = 0
                    return true
                }
            }
            return false
        }

        override fun next(): DrawCommand {
            if (hasNext().not()) throw NoSuchElementException("No remaining paint command.")
            return checkNotNull(leaf)[index++]
        }
    }
}
