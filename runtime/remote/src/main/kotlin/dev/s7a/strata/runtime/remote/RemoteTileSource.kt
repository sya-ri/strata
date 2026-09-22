package dev.s7a.strata.runtime.remote

import dev.s7a.strata.component.TiledImageLevel
import dev.s7a.strata.component.TiledImageSource
import dev.s7a.strata.component.TiledImageTile
import dev.s7a.strata.component.TiledImageTileId
import dev.s7a.strata.geometry.LongRect
import dev.s7a.strata.state.StateRevision
import dev.s7a.strata.state.StateSnapshot
import dev.s7a.strata.state.StateSource
import dev.s7a.strata.state.StateSubscription

/**
 * Client presentation source containing only the last validated server working set.
 * Keys are tile IDs within one immutable source generation; each update replaces the complete set and invalidates subscribers.
 * The owning remote screen serializes updates and subscriptions on its thread and closes this source on replacement or termination.
 * Native tile observations are independently bounded by the standard renderer's cache policy.
 */
internal class RemoteTileSource(
    val generation: Long,
    override val bounds: LongRect,
    override val levels: List<TiledImageLevel>,
) : TiledImageSource,
    AutoCloseable {
    private var values: Map<TiledImageTileId, TiledImageTile> = emptyMap()
    private val observers = mutableMapOf<TiledImageTileId, MutableMap<Any, (StateSnapshot<TiledImageTile>) -> Unit>>()
    private var revision = 0L
    private var closed = false

    override fun tile(id: TiledImageTileId): StateSource<TiledImageTile> {
        require(id.level in levels.indices)
        return StateSource { observer ->
            check(closed.not())
            val token = Any()
            observers.getOrPut(id) { mutableMapOf() }[token] = observer
            StateSubscription(StateSnapshot(StateRevision(revision), values[id] ?: TiledImageTile.Empty)) {
                observers[id]?.let { group ->
                    group.remove(token)
                    if (group.isEmpty()) observers.remove(id)
                }
            }
        }
    }

    /**
     * Replaces derived pixels outside declaration evaluation and queues native frame-cutoff revisions.
     */
    fun update(next: Map<TiledImageTileId, TiledImageTile>) {
        check(closed.not())
        if (values == next) return
        revision = Math.incrementExact(revision)
        values = next
        observers.toList().forEach { (id, group) ->
            val snapshot = StateSnapshot(StateRevision(revision), values[id] ?: TiledImageTile.Empty)
            group.values.toList().forEach { it(snapshot) }
        }
    }

    override fun close() {
        closed = true
        values = emptyMap()
        observers.clear()
    }
}
