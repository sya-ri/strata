package dev.s7a.strata.runtime.web

import dev.s7a.strata.component.TextStyle
import dev.s7a.strata.render.PlatformDrawCommand
import dev.s7a.strata.semantics.SemanticsRole
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Immutable DOM presentation detached from state, callbacks, and retained nodes.
 * The renderer owns its DOM counterpart only while this identity occurs in the current frame.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal data class WebPresentation(
    val identity: Int,
    val kind: Kind,
    val label: String,
    val enabled: Boolean,
    val style: TextStyle,
    val progress: Double? = null,
) : PlatformDrawCommand {
    /**
     * Native DOM kinds and their matching portable semantics.
     */
    enum class Kind(
        val tag: String,
        val role: SemanticsRole,
    ) {
        /**
         * Plain native inline text with portable text semantics.
         */
        Text("span", SemanticsRole.Text),

        /**
         * Native button appearance with portable button semantics.
         */
        Button("button", SemanticsRole.Button),

        /**
         * Native determinate progress with a bounded value.
         */
        Progress("progress", SemanticsRole.ProgressBar),
    }
}
