package dev.s7a.strata.component

/**
 * Owner-thread attachment bridge used by [VirtualListState] to resolve index and key jumps.
 */
internal interface VirtualListController<K : Any> {
    /**
     * Resolves and applies [target], returning whether it exists in the current data model.
     */
    fun jump(target: VirtualListJump<K>): Boolean

    /**
     * Invalidates the materialized row descriptions after caller-owned presentation state changes.
     */
    fun refresh()
}
