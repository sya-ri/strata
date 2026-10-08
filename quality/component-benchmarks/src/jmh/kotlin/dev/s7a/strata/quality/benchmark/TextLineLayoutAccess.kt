package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.quality.benchmark.TextLineLayoutBenchmark.Consumer
import dev.s7a.strata.runtime.minecraft.MinecraftUiHost

/**
 * Untimed read-only adapter for actual current layout owners on the frozen runtime sides.
 * Existing internal symbols are decoded once by class identity; no runtime field is changed or passed into timing.
 */
internal object TextLineLayoutAccess {
    private val layoutType = Class.forName("dev.s7a.strata.runtime.minecraft.MinecraftTextLayout")

    /**
     * Borrows the exact retained component owner through the known host/session/tree chain.
     */
    internal fun owner(
        host: MinecraftUiHost,
        consumer: Consumer,
    ): Any {
        if (consumer === Consumer.TextArea) return TextAreaInputAccess.editor(host)
        val session = TextAreaInputAccess.field(TextAreaInputAccess.field(host, "session"), "session")
        val root = TextAreaInputAccess.field(TextAreaInputAccess.field(session, "tree"), "root")
        return checkNotNull(findDisplay(root))
    }

    /**
     * Borrows only the owner's current layout and never consults previous layouts.
     */
    internal fun layout(
        owner: Any,
        consumer: Consumer,
    ): Any = TextAreaInputAccess.field(owner, if (consumer === Consumer.TextArea) "layout" else "currentLayout")

    /**
     * Requires terminal current-layout and borrowed-input release on the real owner.
     */
    internal fun verifyReleased(
        owner: Any,
        consumer: Consumer,
    ) {
        val layout = if (consumer === Consumer.TextArea) "layout" else "currentLayout"
        check(TextAreaInputAccess.optional(owner, layout) == null)
        if (consumer === Consumer.TextArea) {
            check(TextAreaInputAccess.optional(owner, "current") == null)
        } else {
            check(TextAreaInputAccess.optional(owner, "content") == null)
            check(TextAreaInputAccess.optional(owner, "renderer") == null)
        }
    }

    /**
     * Copies temporary oracle inputs outside timing to prove old immutable lines survive replacement and release.
     */
    internal fun snapshot(layout: Any): List<Pair<IntArray, IntArray>> =
        TextAreaInputAccess.lines(layout).map { line ->
            (TextAreaInputAccess.field(line, "offsets") as IntArray).copyOf() to
                (TextAreaInputAccess.field(line, "positions") as IntArray).copyOf()
        }

    /**
     * Compares every original boundary and coordinate after the owning component changes or closes.
     */
    internal fun verifySnapshot(
        layout: Any,
        expected: List<Pair<IntArray, IntArray>>,
    ) {
        val lines = TextAreaInputAccess.lines(layout)
        check(lines.size == expected.size)
        for ((line, metrics) in lines.zip(expected)) {
            check((TextAreaInputAccess.field(line, "offsets") as IntArray).contentEquals(metrics.first))
            check((TextAreaInputAccess.field(line, "positions") as IntArray).contentEquals(metrics.second))
        }
    }

    private fun findDisplay(entry: Any): Any? {
        val node = TextAreaInputAccess.field(entry, "node")
        if (node.javaClass.declaredFields.any { field -> field.type === layoutType }) return node
        for (child in TextAreaInputAccess.field(entry, "children") as List<*>) {
            findDisplay(checkNotNull(child))?.let { return it }
        }
        return null
    }
}
