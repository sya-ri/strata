package dev.s7a.strata.runtime

import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.SemanticsNode
import dev.s7a.strata.runtime.semantics.SemanticsEntry
import dev.s7a.strata.semantics.Semantics
import dev.s7a.strata.semantics.SemanticsScope
import java.util.Collections

/**
 * Collects unresolved semantics from laid-out retained nodes.
 */
internal class SemanticsPipeline(
    private val threadGuard: ThreadGuard,
) {
    /**
     * Collects [root] semantics in parent-before-child order.
     *
     * @param root the laid-out retained root.
     * @return immutable tree-coordinate entries.
     */
    fun semantics(root: RetainedEntry): List<SemanticsEntry> {
        val output = ArrayList<SemanticsEntry>()
        semanticsNode(root, output)
        return Collections.unmodifiableList(output.toList())
    }

    private fun semanticsNode(
        retained: RetainedEntry,
        output: MutableList<SemanticsEntry>,
    ) {
        if (DirtyPhase.Semantics in retained.dirty || retained.localSemantics == null) {
            retained.dirty -= DirtyMask.of(DirtyPhase.Semantics)
            val collector = SemanticsCollector(threadGuard)
            try {
                val semanticsCapability = retained.node as? SemanticsNode
                semanticsCapability?.semantics(collector)
                retained.localSemantics = collector.snapshot()
            } finally {
                collector.close()
            }
        }
        retained.localSemantics.orEmpty().forEach { semantics ->
            output.add(SemanticsEntry(retained.bounds, semantics))
        }
        for (index in 0 until retained.effectiveChildCount) {
            val child = retained.effectiveChildAt(index)
            if (child.placed) {
                semanticsNode(child, output)
            }
        }
    }

    /**
     * Collects one node's unresolved semantics during its active callback.
     */
    private class SemanticsCollector(
        threadGuard: ThreadGuard,
    ) : SemanticsScope {
        private val guard = ScopeGuard(threadGuard)
        private val values: MutableList<Semantics> = ArrayList()

        override fun emit(semantics: Semantics) {
            guard.check()
            values.add(semantics)
        }

        /**
         * Snapshots emitted payloads while the callback scope remains active.
         */
        fun snapshot(): List<Semantics> {
            guard.check()
            return values.toList()
        }

        /**
         * Closes this collector after the semantics callback.
         */
        fun close() {
            guard.close()
        }
    }
}
