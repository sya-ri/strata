package dev.s7a.strata.examples.web

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.screen.ScreenDefinition

/**
 * Owns the public demo catalog and deterministic factories shared by build rendering and browser startup.
 * Slugs are decoded only at the document boundary; each factory creates independent caller-owned state.
 */
internal enum class Demo(
    val slug: String,
    val title: String,
    val description: String,
    val sourceFile: String,
    val definition: () -> ScreenDefinition,
) {
    Counter("counter", "Counter", "Change a value and explore enabled and disabled actions.", "CounterDemo.kt", ::counterDemo),
    Progress("progress", "Progress", "Keep a progress indicator and its label in sync with shared state.", "ProgressDemo.kt", ::progressDemo),
    KeyedList("keyed-list", "Keyed list", "Add, remove, and reorder items while retaining their native elements.", "KeyedListDemo.kt", ::keyedListDemo),
    ;

    /**
     * Shared document-boundary configuration for build rendering and client startup.
     */
    companion object {
        /**
         * Logical CSS-pixel size used by both initial rendering and adoption.
         */
        val viewport: IntSize = IntSize(320, 400)

        /**
         * Rejects unknown document identifiers before consuming a screen definition.
         */
        fun decode(slug: String): Demo = requireNotNull(entries.singleOrNull { it.slug == slug }) { "Unknown web demo: $slug" }
    }
}
