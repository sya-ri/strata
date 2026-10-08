package dev.s7a.strata.runtime

import dev.s7a.strata.node.DeclarationProjectionNode
import dev.s7a.strata.projection.DeclarationProjection
import dev.s7a.strata.runtime.spi.RuntimeDeclaration
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Current fixed declaration snapshots under one UiTree's owner-thread operation boundary.
 * Keys include exact immutable descriptions, fixed type/enabled/value inputs and ordered modifier/child snapshots.
 * Preparation and dynamic reconciliation always precede reading; arbitrary encoders never reuse a revision.
 * Each retained entry owns at most one eligible snapshot, sharing child snapshots without retaining history.
 * Reconciliation, detach and LifecycleManager cleanup release description keys before user callbacks.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class DeclarationSnapshots {
    private var nextIdentity = 1L
    private var nextRevision = 1L

    /**
     * Returns the complete current fixed snapshot or a fresh revision for an opaque or changed subtree.
     * This does not invoke encoders, mutate source state or select any concrete component kind.
     */
    fun read(entry: RetainedNode): RuntimeDeclaration {
        val identity = identity(entry)
        val projection = (entry.node as? DeclarationProjectionNode)?.declarationProjection ?: entry.element.projection
        val previous = entry.declarationSnapshot
        val modifiers = entry.modifiers.snapshotMap(previous?.modifiers) { retained, index -> modifier(retained, previous?.modifiers?.getOrNull(index)) }
        val children = entry.children.snapshotMap(previous?.children) { child, _ -> read(child) }
        val fixed = projection?.fixedValue != null && modifiers.all { it.projection?.fixedValue != null } && entry.children.all { it.declarationSnapshot != null }
        if (fixed && previous != null && previous.element === entry.element && sameFixedProjection(projection, previous.projection) && previous.modifiers === modifiers && previous.children === children) return previous
        check(nextRevision < Long.MAX_VALUE) { "Declaration projection revision space is exhausted." }
        val snapshot = RuntimeDeclaration(identity, entry.element, projection, modifiers, children, nextRevision++)
        entry.declarationSnapshot = if (fixed) snapshot else null
        return snapshot
    }

    private fun modifier(entry: RetainedModifier, previous: RuntimeDeclaration.Modifier?): RuntimeDeclaration.Modifier {
        val identity = identity(entry)
        val projection = (entry.node as? DeclarationProjectionNode)?.declarationProjection ?: entry.element.projection
        if (projection?.fixedValue != null && previous != null && previous.identity == identity && previous.element === entry.element && sameFixedProjection(projection, previous.projection)) return previous
        return RuntimeDeclaration.Modifier(identity, entry.element, projection)
    }

    private fun sameFixedProjection(current: DeclarationProjection<*>?, previous: DeclarationProjection<*>?): Boolean {
        val value = current?.fixedValue ?: return false
        return previous != null && current.type == previous.type && current.inputEnabled == previous.inputEnabled && value == previous.fixedValue
    }

    private fun identity(entry: RetainedEntry): Long {
        if (entry.declarationId == 0L) {
            check(nextIdentity < Long.MAX_VALUE) { "Declaration identity space is exhausted." }
            entry.declarationId = nextIdentity++
        }
        return entry.declarationId
    }

    /**
     * Shares an owned snapshot list only when every ordered transformed reference is unchanged.
     * Replacement creates one new current list; unchanged requests allocate no list or prefix copy.
     */
    private inline fun <T, R> List<T>.snapshotMap(previous: List<R>?, transform: (T, Int) -> R): List<R> {
        if (previous != null && previous.size == size) {
            var replacement: MutableList<R>? = null
            for (index in indices) {
                val value = transform(this[index], index)
                val current = replacement
                if (current != null) {
                    current.add(value)
                } else if (value !== previous[index]) {
                    val fresh = ArrayList<R>(size)
                    for (prefix in 0 until index) fresh.add(previous[prefix])
                    fresh.add(value)
                    replacement = fresh
                }
            }
            return replacement ?: previous
        }
        return when (size) {
            0 -> emptyList()
            1 -> listOf(transform(this[0], 0))
            else -> mapIndexed { index, value -> transform(value, index) }
        }
    }
}
