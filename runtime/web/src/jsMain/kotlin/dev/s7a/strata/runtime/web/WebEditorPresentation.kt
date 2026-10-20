package dev.s7a.strata.runtime.web

import dev.s7a.strata.component.TextInputAppearance
import dev.s7a.strata.component.TextStyle
import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.render.PlatformDrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Detached editing snapshot; native selection and composition remain in the retained DOM.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal data class WebEditorPresentation(
    val identity: Int,
    val multiline: Boolean,
    val value: String,
    val maxLength: Int,
    val enabled: Boolean,
    val focused: Boolean,
    val style: TextStyle,
    val appearance: TextInputAppearance,
    val font: String,
    val wrap: Boolean,
    val lineSpacing: Int,
    val scrollOffset: Double,
) : PlatformDrawCommand {
    override fun transformed(
        source: IntRect,
        destination: FloatRect,
    ): PlatformDrawCommand = WebScaledPresentation(this, source, destination)
}
