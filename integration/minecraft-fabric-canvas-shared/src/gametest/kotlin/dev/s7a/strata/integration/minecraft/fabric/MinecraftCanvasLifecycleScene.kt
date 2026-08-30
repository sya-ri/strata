package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.component.Canvas
import dev.s7a.strata.component.CanvasId
import dev.s7a.strata.component.CanvasSource
import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.VirtualList
import dev.s7a.strata.component.VirtualListState
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Uses ordinary dynamic VirtualList reconciliation to resize, replace, rekey, and remove one real Canvas.
 *
 * All state belongs to the client thread and is mutated before the list's explicit refresh cutoff.
 * Stable row identity preserves the Canvas node across extent and source changes; a changed Canvas key creates a new node.
 * The scene observes scalar attachment identities only and never retains a native target or callback context.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class MinecraftCanvasLifecycleScene(
    source: CanvasSource,
    size: IntSize,
) {
    private val state = VirtualListState<RowKey>()
    private var generation = 0
    private var entry: Entry? = Entry(tracked(source), size, Generation(generation))

    /**
     * Most recently opened scalar Canvas identity, observed synchronously on the client thread.
     */
    internal var lastCanvasId: CanvasId? = null
        private set

    /**
     * Creates the one-shot scene definition; resource and declaration failures propagate through its owning screen.
     */
    internal fun definition(): ScreenDefinition =
        ScreenDefinition("Native Canvas lifetime acceptance") {
            Stack(Modifier.Empty.background(ArgbColor(0xFF000000.toInt()))) {
                VirtualList(
                    itemCount = { if (entry == null) 0 else 1 },
                    itemAt = { checkNotNull(entry) },
                    keyAt = { RowKey.Only },
                    state = state,
                    viewportSize = IntSize(128, 96),
                    rowHeight = 96,
                ) { item ->
                    Stack {
                        Canvas(item.source, item.size, key = ElementKey(item.key))
                    }
                }
            }
        }

    /**
     * Requests a new positive extent while preserving both Canvas key and source identity.
     */
    internal fun resize(size: IntSize) {
        entry = checkNotNull(entry).copy(size = size)
        state.refresh()
    }

    /**
     * Replaces an external source while preserving the existing Canvas node and its stable lifetime identity.
     */
    internal fun replaceSource(source: CanvasSource) {
        entry = checkNotNull(entry).copy(source = tracked(source))
        state.refresh()
    }

    /**
     * Changes only the Canvas key, forcing the old node and its native attachment to retire without a screen close.
     */
    internal fun replaceKey() {
        generation++
        entry = checkNotNull(entry).copy(key = Generation(generation))
        state.refresh()
    }

    /**
     * Removes the only row and lets retained cleanup release its Canvas independently of the still-visible screen.
     */
    internal fun hide() {
        entry = null
        state.refresh()
    }

    /**
     * Adds a fresh Canvas after removal; [source] remains externally owned and may be reused by other scenes.
     */
    internal fun show(
        source: CanvasSource,
        size: IntSize,
    ) {
        check(entry == null) { "The Canvas lifetime scene already contains a row." }
        generation++
        entry = Entry(tracked(source), size, Generation(generation))
        state.refresh()
    }

    private fun tracked(source: CanvasSource): CanvasSource =
        CanvasSource { identity ->
            lastCanvasId = identity
            source.open(identity)
        }

    private enum class RowKey {
        Only,
    }

    private data class Generation(
        val value: Int,
    )

    private data class Entry(
        val source: CanvasSource,
        val size: IntSize,
        val key: Generation,
    )
}
