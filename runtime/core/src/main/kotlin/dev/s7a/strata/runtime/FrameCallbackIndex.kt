package dev.s7a.strata.runtime

import dev.s7a.strata.node.FrameCutoffNode
import dev.s7a.strata.node.FrameTimeNode

/**
 * Owner-confined callback capabilities for one current effective tree, in parent-first order.
 * Root replacement or structural revision changes rebuild both lists; phase invalidation does not.
 * Cleanup clears all references before caller lifecycle callbacks, including terminal failure.
 * Storage is bounded by the current tree and never retains previous tree generations.
 */
internal class FrameCallbackIndex {
    private var root: RetainedNode? = null
    private var revision = 0L
    private var cutoff: List<FrameCutoffNode> = emptyList()
    private var timed: List<FrameTimeNode> = emptyList()

    /**
     * Captures every current cutoff before the separate commit pass can publish any observation.
     */
    fun capture(
        root: RetainedNode,
        revision: Long,
    ) {
        prepare(root, revision)
        cutoff.forEach { it.captureFrameState() }
    }

    /**
     * Commits the current effective tree in the same order as capture.
     */
    fun commit(
        root: RetainedNode,
        revision: Long,
    ) {
        prepare(root, revision)
        cutoff.forEach { it.commitFrameState() }
    }

    /**
     * Delivers every explicit timestamp, including repeated timestamps, to current capable entries.
     */
    fun advance(
        root: RetainedNode,
        revision: Long,
        time: FrameTime,
    ) {
        prepare(root, revision)
        timed.forEach { it.onFrame(time) }
    }

    /**
     * Releases every borrowed root and node reference before replacement cleanup or terminal disposal.
     */
    fun clear() {
        root = null
        cutoff = emptyList()
        timed = emptyList()
    }

    private fun prepare(
        root: RetainedNode,
        revision: Long,
    ) {
        if (this.root === root && this.revision == revision) return
        clear()
        val cutoff = ArrayList<FrameCutoffNode>()
        val timed = ArrayList<FrameTimeNode>()
        collect(root.effectiveRoot, cutoff, timed)
        this.cutoff = cutoff
        this.timed = timed
        this.root = root
        this.revision = revision
    }

    private fun collect(
        entry: RetainedEntry,
        cutoff: MutableList<FrameCutoffNode>,
        timed: MutableList<FrameTimeNode>,
    ) {
        (entry.node as? FrameCutoffNode)?.let(cutoff::add)
        (entry.node as? FrameTimeNode)?.let(timed::add)
        for (index in 0 until entry.effectiveChildCount) collect(entry.effectiveChildAt(index), cutoff, timed)
    }
}
