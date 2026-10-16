package dev.s7a.strata.runtime

import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.SemanticsNode
import dev.s7a.strata.runtime.diagnostics.UiRenderMetric
import dev.s7a.strata.runtime.semantics.SemanticsEntry
import dev.s7a.strata.semantics.Semantics
import dev.s7a.strata.semantics.SemanticsScope
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Collects unresolved semantics from laid-out retained nodes.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class SemanticsPipeline(
    private val ownerGuard: OwnerGuard,
    private val monitoring: RenderMonitoring = RenderMonitoring(),
) {
    /**
     * Collects [root] semantics in parent-before-child order.
     *
     * @param root the laid-out retained root.
     * @return immutable tree-coordinate entries.
     */
    fun semantics(root: RetainedEntry): List<SemanticsEntry> = buildList { semanticsNode(root, this) }

    private fun semanticsNode(
        retained: RetainedEntry,
        output: MutableList<SemanticsEntry>,
    ) {
        if (DirtyPhase.Semantics in retained.dirty || retained.localSemantics == null) {
            retained.dirty -= DirtyMask.of(DirtyPhase.Semantics)
            retained.localSemantics = collectLocalSemantics(retained)
        }
        retained.localSemantics.orEmpty().forEach { semantics ->
            output.add(SemanticsEntry(retained.bounds, semantics))
        }
        if (retained is RetainedNode) {
            val children = retained.semanticsChildren
            for (index in children.indices) {
                val child = children[index].effectiveRoot
                if (child.placed) semanticsNode(child, output)
            }
        } else {
            for (index in 0 until retained.effectiveChildCount) {
                val child = retained.effectiveChildAt(index)
                if (child.placed) semanticsNode(child, output)
            }
        }
    }

    /**
     * Refreshes one node's detached payload and closes every actual callback scope before returning or throwing.
     */
    private fun collectLocalSemantics(retained: RetainedEntry): List<Semantics> {
        val semanticsCapability = retained.node as? SemanticsNode
        if (semanticsCapability == null) {
            ownerGuard.check()
            return emptyList()
        }
        val collector = SemanticsCollector(ownerGuard)
        try {
            monitoring.record(UiRenderMetric.Semantics, retained)
            semanticsCapability.semantics(collector)
            return collector.snapshot()
        } finally {
            collector.close()
        }
    }

    /**
     * Collects one node's unresolved semantics during its active callback.
     */
    private class SemanticsCollector(
        private val ownerGuard: OwnerGuard,
    ) : SemanticsScope {
        private var closed: Boolean = false
        private var first: Semantics? = null
        private var values: MutableList<Semantics>? = null

        override fun emit(semantics: Semantics) {
            checkActive()
            val buffer = values
            if (buffer != null) {
                buffer.add(semantics)
                return
            }
            val initial = first
            if (initial == null) {
                first = semantics
            } else {
                values =
                    ArrayList<Semantics>().also {
                        it.add(initial)
                        it.add(semantics)
                    }
                first = null
            }
        }

        /**
         * Snapshots emitted payloads while the callback scope remains active.
         */
        fun snapshot(): List<Semantics> {
            checkActive()
            return values?.toList() ?: first?.let { listOf(it) } ?: emptyList()
        }

        /**
         * Closes this collector after the semantics callback.
         */
        fun close() {
            ownerGuard.check()
            closed = true
            first = null
            values = null
        }

        private fun checkActive() {
            ownerGuard.check()
            check(closed.not()) { "The callback scope is no longer active." }
        }
    }
}
