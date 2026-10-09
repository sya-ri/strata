package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.quality.benchmark.TextProvenanceBenchmark.Consumer
import dev.s7a.strata.runtime.minecraft.MinecraftUiHost
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Untimed current-owner inspection through the existing host/session/tree contracts.
 * Runtime fields are read only; decoded class identities never enter content or sampled operations.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal object TextProvenanceOwnerAccess {
    private val editorType = Class.forName("dev.s7a.strata.runtime.minecraft.MinecraftTextAreaEditor")
    private val layoutType = Class.forName("dev.s7a.strata.runtime.minecraft.MinecraftTextLayout")
    private val runType = Class.forName("dev.s7a.strata.runtime.minecraft.MinecraftTextRun")

    /**
     * Locates exactly one actual retained consumer without retaining historical owners.
     */
    internal fun owner(
        host: MinecraftUiHost,
        consumer: Consumer,
    ): Any {
        val session = field(field(host, "session"), "session")
        val root = field(field(session, "tree"), "root")
        return checkNotNull(find(root, consumer))
    }

    /**
     * Borrows one current layout or single-line run.
     */
    internal fun presentation(
        owner: Any,
        consumer: Consumer,
    ): Any =
        field(
            owner,
            when (consumer) {
                Consumer.TextArea -> "layout"
                Consumer.MultilineText -> "currentLayout"
                Consumer.SingleLineText -> "run"
            },
        )

    /**
     * Requires borrowed renderer/source inputs and current multiline/editor layouts to be released terminally.
     * Single-line nodes contain only detached runs, which deliberately remain usable after removal.
     */
    internal fun released(
        owner: Any,
        consumer: Consumer,
    ) {
        when (consumer) {
            Consumer.TextArea -> {
                check(TextProvenanceAccess.field(owner, "layout") == null)
                check(TextProvenanceAccess.field(owner, "current") == null)
            }

            Consumer.MultilineText -> {
                check(TextProvenanceAccess.field(owner, "currentLayout") == null)
                check(TextProvenanceAccess.field(owner, "content") == null)
                check(TextProvenanceAccess.field(owner, "renderer") == null)
            }

            Consumer.SingleLineText -> {}
        }
    }

    /**
     * Reads a required immutable field for the independent oracle, outside all intervals.
     */
    internal fun field(
        owner: Any,
        name: String,
    ): Any = checkNotNull(TextProvenanceAccess.field(owner, name))

    private fun find(
        entry: Any,
        consumer: Consumer,
    ): Any? {
        val node = field(entry, "node")
        val type =
            when (consumer) {
                Consumer.TextArea -> editorType
                Consumer.MultilineText -> layoutType
                Consumer.SingleLineText -> runType
            }
        val selected = node.javaClass.declaredFields.singleOrNull { it.type === type }
        if (selected != null) return if (consumer === Consumer.TextArea) selected.apply { isAccessible = true }.get(node) else node
        for (child in field(entry, "children") as List<*>) find(checkNotNull(child), consumer)?.let { return it }
        return null
    }
}
