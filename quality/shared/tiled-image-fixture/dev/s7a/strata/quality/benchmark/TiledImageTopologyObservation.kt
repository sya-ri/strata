@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.component.TiledImageTileId
import dev.s7a.strata.runtime.spi.RuntimeUiSession
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import java.security.MessageDigest
import java.util.Base64

/**
 * Untimed inspection of actual baseline or candidate current plans through the normal runtime loader.
 * Retains no image, source, historical grid, counter, clock or allocation model in the measured fixture.
 */
internal object TiledImageTopologyObservation {
    /**
     * Reads exact ordered IDs/cells and actual retained key descriptors after a completed owner operation.
     * The original scalar plan has no topology/key fields; its existing ID/cell lists are read directly.
     */
    internal fun capture(
        session: RuntimeUiSession,
        input: TiledImageBenchmarkInput,
    ): Snapshot {
        val owner = checkNotNull(field(session, "session"))
        val tree = checkNotNull(field(owner, "tree"))
        val pending = ArrayDeque<Any>()
        pending.add(checkNotNull(field(tree, "root")))
        val type = Class.forName("dev.s7a.strata.component.TiledImageTileLayerElement\$Node")
        while (pending.isNotEmpty()) {
            val retained = pending.removeLast()
            val node = retained.javaClass.getMethod("getNode").invoke(retained)
            if (type.isInstance(node)) return snapshot(checkNotNull(node), input)
            val children = retained.javaClass.getMethod("getChildren").invoke(retained) as List<*>
            children.forEach { child -> pending.add(checkNotNull(child)) }
        }
        error("The actual retained TiledImage layer is absent.")
    }

    /**
     * Emits deterministic current-state diagnostics outside JMH, including complete ordered-grid hashes.
     * Enumeration and allocation savings still require profiler evidence rather than interpreting these counts as allocation.
     */
    internal fun record(
        label: String,
        snapshot: Snapshot,
        input: TiledImageBenchmarkInput,
        reused: Boolean,
    ) {
        val grid = (snapshot.required + snapshot.painted).joinToString(";") { id -> listOf(id.level, id.column, id.row).joinToString(":") }
        val digest = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(grid.toByteArray(Charsets.UTF_8)))
        println(listOf(label, snapshot.required.size, snapshot.painted.size, snapshot.reservedBytes, snapshot.keyRanges, reused, input.active, input.peakActive, input.reservedBytes, input.peakReservedBytes, input.opened, input.closed, digest).joinToString(","))
    }

    private fun snapshot(
        node: Any,
        input: TiledImageBenchmarkInput,
    ): Snapshot {
        val plan = checkNotNull(field(node, "plan"))
        val topology = field(plan, "topology") ?: plan
        val required = (checkNotNull(field(topology, "requiredIds")) as List<*>).map { id -> id as TiledImageTileId }
        val painted = (checkNotNull(field(topology, "cells")) as List<*>).map { cell -> checkNotNull(field(checkNotNull(cell), "id")) as TiledImageTileId }
        val key = field(topology, "key")
        val ranges = if (key == null) 0 else (checkNotNull(field(key, "requiredRanges")) as List<*>).size + if (0 < input.policy.overscanTiles) 1 else 0
        val bytes =
            required.sumOf { id ->
                input.source.levels[id.level]
                    .tilePixelSize
                    .let { size -> size.width.toLong() * size.height * 4L }
            }
        return Snapshot(topology, required, painted, ranges, bytes)
    }

    private fun field(
        instance: Any,
        name: String,
    ): Any? {
        val reflected =
            try {
                instance.javaClass.getDeclaredField(name)
            } catch (_: NoSuchFieldException) {
                return null
            }
        check(reflected.trySetAccessible())
        return reflected.get(instance)
    }

    /**
     * One detached metadata observation used only by optional admission, never by a timed benchmark operation.
     */
    internal data class Snapshot(
        val identity: Any,
        val required: List<TiledImageTileId>,
        val painted: List<TiledImageTileId>,
        val keyRanges: Int,
        val reservedBytes: Long,
    )
}
