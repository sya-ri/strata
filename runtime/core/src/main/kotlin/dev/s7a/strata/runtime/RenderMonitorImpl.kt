package dev.s7a.strata.runtime

import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.node.ContentWork
import dev.s7a.strata.node.ContentWorkNode
import dev.s7a.strata.runtime.diagnostics.UiRenderMetric
import dev.s7a.strata.runtime.diagnostics.UiRenderMonitor
import dev.s7a.strata.runtime.diagnostics.UiRenderNodeId
import dev.s7a.strata.runtime.diagnostics.UiRenderNodeKind
import dev.s7a.strata.runtime.diagnostics.UiRenderOperation
import dev.s7a.strata.runtime.diagnostics.UiRenderSnapshot
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Bounded owner-thread collector with a detachable public handle and no frame history.
 */
@OptIn(InternalStrataRuntimeApi::class)
@Suppress("TooManyFunctions") // Event counters and the public handle share the same bounded collector lifetime.
internal class RenderMonitorImpl(
    private var boundary: (() -> Unit)?,
    private var onClose: (() -> Unit)?,
    activeSubscriptions: Int,
    private var monitoring: RenderMonitoring?,
) : UiRenderMonitor {
    private val threadGuard = ThreadGuard.currentThread()
    private val totals = RenderWorkCounts()
    private val live = IdentityHashMap<RetainedEntry, RenderNodeRecord>()
    private val records = ArrayList<RenderNodeRecord>()
    private var failed = false
    private var finalSnapshot: UiRenderSnapshot? = null
    private var subscriptions = activeSubscriptions
    private var overflowed = false

    /**
     * Registers the current tree as an uncounted baseline.
     */
    fun baseline(entry: RetainedEntry) {
        register(entry)
        repeat(entry.effectiveChildCount) { index -> baseline(entry.effectiveChildAt(index)) }
    }

    /**
     * Counts actual work; unknown entries are registered without inventing creation work.
     */
    fun record(
        metric: UiRenderMetric,
        operation: UiRenderOperation,
        entry: RetainedEntry?,
    ) {
        totals.record(metric, operation)
        if (entry != null) register(entry)?.counts?.record(metric, operation)
    }

    /**
     * Records a new retained identity after successful runtime binding.
     */
    fun created(
        entry: RetainedEntry,
        operation: UiRenderOperation,
    ) {
        record(UiRenderMetric.NodeCreate, operation, entry)
    }

    /**
     * Records final disposal and forgets the live node immediately.
     */
    fun disposed(
        entry: RetainedEntry,
        operation: UiRenderOperation,
    ) {
        record(UiRenderMetric.NodeDispose, operation, entry)
        live.remove(entry)?.let { record ->
            record.parentId = entry.parent?.let { live[it]?.id }
            record.entry = null
        }
        (entry.node as? ContentWorkNode)?.contentWorkObserver = null
    }

    /**
     * Counts open/close operations while preserving a current subscription gauge.
     */
    fun subscription(
        opened: Boolean,
        operation: UiRenderOperation,
    ) {
        subscriptions += if (opened) 1 else -1
        record(if (opened) UiRenderMetric.SubscriptionOpen else UiRenderMetric.SubscriptionClose, operation, null)
    }

    override fun checkpoint() {
        checkBoundary()
        records.removeAll { it.entry == null }
        totals.clear()
        records.forEach { it.counts.clear() }
        // Once an identity could not be recorded, a fresh monitor is required for complete node evidence.
    }

    override fun snapshot(): UiRenderSnapshot {
        threadGuard.check()
        finalSnapshot?.let { return it }
        checkBoundary()
        return detachedSnapshot()
    }

    private fun detachedSnapshot(): UiRenderSnapshot {
        records.forEach { record ->
            record.entry?.let { record.parentId = it.parent?.let { parent -> live[parent]?.id } }
        }
        return UiRenderSnapshot(
            totals.totals(),
            totals.operations(),
            Collections.unmodifiableList(records.map { it.snapshot() }),
            subscriptions,
            overflowed,
        )
    }

    override fun findNodes(key: ElementKey<*>): List<UiRenderNodeId> {
        checkBoundary()
        return Collections.unmodifiableList(
            records.mapNotNull { record ->
                val entry = record.entry as? RetainedNode
                val identity = entry?.element?.identity as? ElementIdentity.Keyed
                if (identity?.key == key) record.id else null
            },
        )
    }

    override fun close() {
        threadGuard.check()
        if (boundary == null) {
            finalSnapshot = null
            return
        }
        checkBoundary()
        release()
    }

    /**
     * Clears references on terminal owner cleanup without requiring an idle tree.
     */
    fun release() {
        if (failed) finalSnapshot = detachedSnapshot()
        val callback = onClose
        boundary = null
        onClose = null
        live.clear()
        records.forEach {
            (it.entry?.node as? ContentWorkNode)?.contentWorkObserver = null
            it.entry = null
        }
        records.clear()
        totals.clear()
        subscriptions = 0
        monitoring = null
        callback?.invoke()
    }

    /**
     * Retains only detached interval evidence after terminal cleanup; explicit close releases that evidence too.
     */
    fun markFailed() {
        failed = true
    }

    private fun checkBoundary() {
        threadGuard.check()
        checkNotNull(boundary) { "Render monitoring is closed." }.invoke()
    }

    private fun register(entry: RetainedEntry): RenderNodeRecord? {
        live[entry]?.let { return it }
        if (4096 <= records.size) {
            overflowed = true
            return null
        }
        val record =
            RenderNodeRecord(
                UiRenderNodeId(entry.diagnosticId.takeUnless { it == 0L } ?: checkNotNull(monitoring).allocateId().also { entry.diagnosticId = it }),
                entry,
                if (entry is RetainedNode) UiRenderNodeKind.Component else UiRenderNodeKind.Modifier,
                entry.node.javaClass.name,
            )
        live[entry] = record
        records.add(record)
        (entry.node as? ContentWorkNode)?.contentWorkObserver = { work ->
            when (work) {
                ContentWork.RowEvaluation -> monitoring?.record(UiRenderMetric.RowEvaluation, entry)
            }
        }
        return record
    }
}
