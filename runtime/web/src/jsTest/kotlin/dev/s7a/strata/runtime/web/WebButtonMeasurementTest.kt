@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.web

import dev.s7a.strata.component.TextStyle
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.UiText
import kotlinx.browser.document
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * Verifies fixed-width construction independently of native DOM rendering and browser timing.
 */
internal class WebButtonMeasurementTest {
    @Test
    fun fixedButtonsDoNotRequireCanvasWhileAutomaticTextRetainsItsFailure() {
        for (theme in WebTheme.entries) {
            for (fault in CanvasFault.entries) {
                val failure = IllegalStateException("Canvas unavailable")
                withCanvasFault(fault, failure) {
                    val runtime = WebComponentRuntime(theme)
                    val button = runtime.button(UiText.Literal("Long <label> & escaped content"), 96, false, Modifier.Empty, null) as WebPrimitiveElement
                    assertEquals(IntSize(96, 32), button.naturalSize)
                    assertEquals("Long <label> & escaped content", button.presentation.label)
                    assertEquals(false, button.presentation.enabled)
                    val textFailure = assertFailsWith<IllegalStateException> { runtime.text(UiText.Literal("Automatic"), TextStyle.Normal, Modifier.Empty, null) }
                    if (fault != CanvasFault.NullContext) assertSame(failure, textFailure)
                }
            }
        }
    }

    @Test
    fun textKeepsTheActualThemedNaturalWidthAndButtonKeepsZeroWidth() {
        for (theme in WebTheme.entries) {
            val canvas = document.createElement("canvas") as HTMLCanvasElement
            val context = checkNotNull(canvas.getContext("2d") as? CanvasRenderingContext2D)
            context.font = theme.font
            val width = ceil(context.measureText("Automatic").width).toInt()
            val runtime = WebComponentRuntime(theme)
            val text = runtime.text(UiText.Literal("Automatic"), TextStyle.Normal, Modifier.Empty, null) as WebPrimitiveElement
            assertEquals(IntSize(width, 24), text.naturalSize)
            val button = runtime.button(UiText.Literal(""), 0, true, Modifier.Empty, null) as WebPrimitiveElement
            assertEquals(IntSize(0, 32), button.naturalSize)
            assertFailsWith<IllegalArgumentException> { runtime.button(UiText.Literal("Negative"), -1, true, Modifier.Empty, null) }
        }
    }

    /**
     * Restores native prototype values on every exit, including an assertion failure.
     */
    private fun withCanvasFault(
        fault: CanvasFault,
        failure: Throwable,
        action: () -> Unit,
    ) {
        val documentPrototype = js("Document.prototype")
        val canvasPrototype = js("HTMLCanvasElement.prototype")
        val contextPrototype = js("CanvasRenderingContext2D.prototype")
        val create = documentPrototype.createElement
        val context = canvasPrototype.getContext
        val measure = contextPrototype.measureText
        try {
            when (fault) {
                CanvasFault.Creation -> documentPrototype.createElement = { _: String -> throw failure }
                CanvasFault.NullContext -> canvasPrototype.getContext = { _: String -> null }
                CanvasFault.Measure -> contextPrototype.measureText = { _: String -> throw failure }
            }
            action()
        } finally {
            documentPrototype.createElement = create
            canvasPrototype.getContext = context
            contextPrototype.measureText = measure
        }
    }

    /**
     * Distinct native prerequisites removed only for fixed-width Button construction.
     */
    private enum class CanvasFault {
        Creation,
        NullContext,
        Measure,
    }
}
