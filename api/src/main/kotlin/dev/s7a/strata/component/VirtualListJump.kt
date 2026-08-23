package dev.s7a.strata.component

/**
 * Typed pending jump retained by [VirtualListState] before or during attachment.
 */
internal sealed interface VirtualListJump<out K : Any> {
    /**
     * Absolute zero-based item index target.
     */
    data class Index(
        val value: Int,
    ) : VirtualListJump<Nothing>

    /**
     * Stable application item key target.
     */
    data class Key<K : Any>(
        val value: K,
    ) : VirtualListJump<K>
}
