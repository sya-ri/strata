package dev.s7a.strata.runtime.remote

import dev.s7a.strata.component.ScrollState
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.projection.BuiltinProjection
import dev.s7a.strata.projection.ProjectionFields

/**
 * Receives only the server-materialized visible window while retaining local viewport input and scroll state.
 * The fixed viewport, row height, and one-row overscan match the standard VirtualList contract.
 */
internal object RemoteVirtualLists {
    /**
     * Shares the ordinary scroll binding token with standalone scrollbar components.
     */
    fun register(
        registry: RemoteRegistry,
        scroll: RemoteControl<ScrollState, Double>,
    ) {
        registry.element(BuiltinProjection.VirtualList.type, { value ->
            val fields = ProjectionFields(value)
            val size = IntSize(fields.int(1..Int.MAX_VALUE), fields.int(1..Int.MAX_VALUE))
            val rowHeight = fields.int(1..Int.MAX_VALUE)
            val rate = fields.int(1..Int.MAX_VALUE)
            val count = fields.int(0..Int.MAX_VALUE / rowHeight)
            val start = fields.int(0..count)
            val binding = RemoteBindingSnapshot.decode(fields.value())
            val demand = fields.long().also { require(0 < it) }
            fields.finish()
            val properties = Properties(size, rowHeight, rate, count, start, demand)
            scroll.specification(binding, ::ScrollState, { state, context ->
                val maximumRows = (size.height.toLong() + rowHeight - 1) / rowHeight + 3
                require(context.children.size.toLong() <= maximumRows && context.children.size <= count - start)
                RemoteVirtualListElement(properties, state, context)
            })
        }, { specification, context -> specification.prepare(context) }) { specification, context -> specification.render(context) }
    }

    /**
     * Bounded current-window metadata; row models and row construction functions never leave the server.
     */
    class Properties(
        val size: IntSize,
        val rowHeight: Int,
        val rate: Int,
        val count: Int,
        val start: Int,
        val demand: Long,
    )
}
