@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.component.TextAreaState
import dev.s7a.strata.component.TextAreaViewport
import dev.s7a.strata.component.UiScope
import dev.s7a.strata.element.Element
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.menuBackground
import dev.s7a.strata.projection.DeclarationProjection
import dev.s7a.strata.runtime.remote.RemoteComponentRuntime
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.component.Button as emitButton
import dev.s7a.strata.component.LoadingIndicator as emitLoadingIndicator
import dev.s7a.strata.component.ProgressBar as emitProgressBar
import dev.s7a.strata.component.Spacer as emitSpacer
import dev.s7a.strata.component.Text as emitText
import dev.s7a.strata.component.TextArea as emitTextArea

/**
 * Real remote profile records, including zero/singleton outer controls with multi-field nested values.
 */
internal enum class ProjectionFieldProfileCase {
    MenuBackground,
    LoadingIndicator,
    ProgressBar,
    Button,
    Text,
    TextArea,
    ;

    /**
     * Prepares caller-owned state and screen callbacks outside measurement.
     */
    fun prepare(): Prepared {
        val size = if (this == LoadingIndicator) IntSize(10, 4) else IntSize(100, 12)
        val content: UiScope.() -> Unit =
            when (this) {
                MenuBackground -> {
                    { emitSpacer(Modifier.Empty.menuBackground()) }
                }

                LoadingIndicator -> {
                    { emitLoadingIndicator(size) }
                }

                ProgressBar -> {
                    { emitProgressBar(0.5, size) }
                }

                Button -> {
                    { emitButton("literal") }
                }

                Text -> {
                    { emitText("literal") }
                }

                TextArea -> {
                    val state = TextAreaState("first\nsecond")
                    val viewport = TextAreaViewport.Lines(100, 4)
                    val factory: UiScope.() -> Unit = { emitTextArea(state, viewport) }
                    factory
                }
            }
        return Prepared(RemoteComponentRuntime(), content, this)
    }

    /**
     * Reuses the execution owner and immutable inputs while producing fresh declarations in the complete-boundary row.
     */
    class Prepared(
        private val runtime: RemoteComponentRuntime,
        private val content: UiScope.() -> Unit,
        private val selected: ProjectionFieldProfileCase,
    ) {
        /**
         * Runs the actual public DSL through the remote component evaluator.
         */
        fun evaluate(): Element = runtime.evaluate(content)

        /**
         * Selects the actual element or menu-background modifier projection without registration shortcuts.
         */
        fun projection(element: Element): DeclarationProjection<*> =
            when (selected) {
                MenuBackground -> checkNotNull(element.modifier.elements().single().projection)
                else -> checkNotNull(element.projection)
            }
    }
}
