package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.runtime.minecraft.MinecraftUiHost
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import java.lang.reflect.Field
import kotlin.math.abs

/**
 * Untimed read-only access to the exact current retained editor and detached line data.
 * Reflection resolves existing internal symbols, never mutates runtime fields, and never participates in session input timing.
 */
// Why: the external-symbol adapter keeps all untimed reads and independent line oracles in one place.
@Suppress("TooManyFunctions")
@OptIn(InternalStrataRuntimeApi::class)
internal object TextAreaInputAccess {
    private val editorClass = Class.forName("dev.s7a.strata.runtime.minecraft.MinecraftTextAreaEditor")

    /**
     * Locates the single actual editor through its known retained ownership chain.
     */
    internal fun editor(host: MinecraftUiHost): Any {
        val session = field(field(host, "session"), "session")
        val root = field(field(session, "tree"), "root")
        return checkNotNull(findEditor(root))
    }

    /**
     * Reads one required current field without copying or retaining ownership history.
     */
    internal fun field(owner: Any, name: String): Any = checkNotNull(optional(owner, name))

    /**
     * Reads nullable current ownership, used only outside collection.
     */
    internal fun optional(owner: Any, name: String): Any? = member(owner.javaClass, name).apply { isAccessible = true }.get(owner)

    /**
     * Borrows the current line list without exposing it to timed session code.
     */
    internal fun lines(layout: Any): List<Any> = (field(layout, "lines") as List<*>).map(::checkNotNull)

    /**
     * Finds the current visual line independently from scalar intervals and decoded soft-wrap affinity.
     */
    internal fun lineIndex(layout: Any, cursor: Any): Int {
        val offset = field(cursor, "offset") as Int
        val lines = lines(layout)
        val index = lines.indexOfLast { line -> (field(line, "start") as Int) <= offset }.coerceAtLeast(0)
        val upstream = enumValueOf<Affinity>((field(cursor, "affinity") as Enum<*>).name) === Affinity.Upstream
        if (upstream && 0 < index && field(lines[index], "start") == offset) {
            val previous = lines[index - 1]
            if (field(previous, "end") == offset && field(previous, "nextStart") == offset) return index - 1
        }
        return index
    }

    /**
     * Selects pointer line boxes by the complete spacing-gap midpoint rule, independently of runtime lookup.
     */
    internal fun pointerLine(layout: Any, y: Int): Int {
        val lines = lines(layout)
        val step = field(layout, "lineStep") as Int
        val index = Math.floorDiv(y.coerceAtLeast(0), step).coerceAtMost(lines.lastIndex)
        val afterMidpoint = 9 + step < 2L * (y.toLong() - index.toLong() * step)
        return if (index < lines.lastIndex && afterMidpoint) index + 1 else index
    }

    /**
     * Complete independent Long-distance scan with earliest logical ties.
     */
    internal fun nearest(line: Any, x: Int): Int {
        val positions = field(line, "positions") as IntArray
        val offsets = field(line, "offsets") as IntArray
        val nearest = positions.indices.minBy { index -> abs(positions[index].toLong() - x.toLong()) }
        return offsets[nearest]
    }

    /**
     * Reads an exact scalar coordinate by its original UTF-16 boundary.
     */
    internal fun coordinate(line: Any, offset: Int): Int {
        val offsets = field(line, "offsets") as IntArray
        return (field(line, "positions") as IntArray)[offsets.indexOf(offset)]
    }

    /**
     * Checks candidate coordinate reads when available; old runtimes still execute the identical complete-scan oracle.
     */
    internal fun verifyVisits(line: Any, x: Int) {
        val method = line.javaClass.declaredMethods.singleOrNull { candidate -> candidate.name.startsWith("boundaryVisits") }
        if (method == null) return
        method.isAccessible = true
        val visits = method.invoke(line, x) as Int
        val positions = field(line, "positions") as IntArray
        val monotone = (1 until positions.size).all { index -> positions[index - 1] <= positions[index] }
        val bound = if (monotone) 2 * (32 - Integer.numberOfLeadingZeros(positions.size)) + 2 else positions.size
        check(visits <= bound)
        if (monotone.not()) check(visits == positions.size)
    }

    private fun findEditor(entry: Any): Any? {
        val node = field(entry, "node")
        val editor = node.javaClass.declaredFields.singleOrNull { candidate -> candidate.type === editorClass }
        if (editor != null) return editor.apply { isAccessible = true }.get(node)
        for (child in field(entry, "children") as List<*>) findEditor(checkNotNull(child))?.let { return it }
        return null
    }

    private fun member(type: Class<*>, name: String): Field =
        try {
            type.getDeclaredField(name)
        } catch (missing: NoSuchFieldException) {
            val parent = type.superclass ?: throw missing
            member(parent, name)
        }

    private enum class Affinity {
        Upstream,
        Downstream,
    }
}
