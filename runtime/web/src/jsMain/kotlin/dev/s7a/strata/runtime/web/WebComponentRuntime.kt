@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.web

import dev.s7a.strata.component.CheckboxState
import dev.s7a.strata.component.CycleButtonState
import dev.s7a.strata.component.ImageScale
import dev.s7a.strata.component.ImageSource
import dev.s7a.strata.component.NineSliceCenterMode
import dev.s7a.strata.component.PlayerSkinSource
import dev.s7a.strata.component.ScrollState
import dev.s7a.strata.component.SliderState
import dev.s7a.strata.component.SlotBinding
import dev.s7a.strata.component.TabSelectionIndicator
import dev.s7a.strata.component.TextAreaState
import dev.s7a.strata.component.TextAreaViewport
import dev.s7a.strata.component.TextFieldState
import dev.s7a.strata.component.TextStyle
import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.geometry.Insets
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.spi.ComponentEvaluator
import dev.s7a.strata.spi.ComponentRuntime
import dev.s7a.strata.spi.ComponentRuntimeBridge
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.TextLayout
import dev.s7a.strata.text.TextWrap
import dev.s7a.strata.text.TranslationFallback
import dev.s7a.strata.text.UiText
import kotlinx.browser.document
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement
import kotlin.math.ceil

/**
 * Resolves native browser presentations during owner-agent component evaluation.
 * Each host owns its identity sequence; immutable frames retain no evaluator or application callbacks.
 */
@Suppress("TooManyFunctions")
internal class WebComponentRuntime : ComponentRuntime {
    override fun retainEvaluator(): ComponentEvaluator = ComponentEvaluator { content -> ComponentRuntimeBridge.evaluate(this, content) }

    override fun tooltip(
        modifier: Modifier,
        text: UiText,
        delayMillis: Long,
    ): Modifier = throw UnsupportedOperationException("Web capability is unavailable: tooltip")

    override fun progressBar(
        progress: Double,
        size: IntSize,
        modifier: Modifier,
        key: ElementKey<*>?,
    ): Element = throw UnsupportedOperationException("Web capability is unavailable: progressBar")

    override fun loadingIndicator(
        size: IntSize,
        modifier: Modifier,
        key: ElementKey<*>?,
    ): Element = throw UnsupportedOperationException("Web capability is unavailable: loadingIndicator")

    override fun checkbox(
        label: UiText,
        state: CheckboxState,
        width: Int,
        enabled: Boolean,
        modifier: Modifier,
        key: ElementKey<*>?,
    ): Element = throw UnsupportedOperationException("Web capability is unavailable: checkbox")

    override fun cycleButton(
        state: CycleButtonState<*>,
        labels: List<UiText>,
        width: Int,
        enabled: Boolean,
        modifier: Modifier,
        key: ElementKey<*>?,
    ): Element = throw UnsupportedOperationException("Web capability is unavailable: cycleButton")

    override fun slider(
        label: UiText,
        state: SliderState,
        width: Int,
        enabled: Boolean,
        modifier: Modifier,
        key: ElementKey<*>?,
    ): Element = throw UnsupportedOperationException("Web capability is unavailable: slider")

    override fun text(
        text: UiText,
        style: TextStyle,
        modifier: Modifier,
        key: ElementKey<*>?,
    ): Element = primitive(resolve(text), WebPresentation.Kind.Text, style, true, null, modifier, key)

    override fun button(
        label: UiText,
        width: Int,
        enabled: Boolean,
        modifier: Modifier,
        key: ElementKey<*>?,
    ): Element = primitive(resolve(label), WebPresentation.Kind.Button, TextStyle.Normal, enabled, width, modifier, key)

    override fun textField(
        state: TextFieldState,
        size: IntSize,
        enabled: Boolean,
        style: TextStyle,
        modifier: Modifier,
        key: ElementKey<*>?,
    ): Element = throw UnsupportedOperationException("Web capability is unavailable: textField")

    override fun tab(
        label: UiText,
        selected: Boolean,
        width: Int,
        enabled: Boolean,
        indicator: TabSelectionIndicator,
        customIndicator: Element?,
        modifier: Modifier,
        key: ElementKey<*>?,
    ): Element = throw UnsupportedOperationException("Web capability is unavailable: tab")

    override fun slot(
        binding: SlotBinding?,
        highlightable: Boolean,
        item: Element?,
        modifier: Modifier,
        key: ElementKey<*>?,
    ): Element = throw UnsupportedOperationException("Web capability is unavailable: slot")

    override fun scrollArea(
        state: ScrollState,
        content: Element,
        scrollRate: Int,
        modifier: Modifier,
        key: ElementKey<*>?,
    ): Element = throw UnsupportedOperationException("Web capability is unavailable: scrollArea")

    override fun scrollbar(
        state: ScrollState,
        modifier: Modifier,
        key: ElementKey<*>?,
    ): Element = throw UnsupportedOperationException("Web capability is unavailable: scrollbar")

    override fun image(
        source: ImageSource,
        sourceRegion: IntRect?,
        size: IntSize?,
        modifier: Modifier,
        key: ElementKey<*>?,
    ): Element = throw UnsupportedOperationException("Web capability is unavailable: image")

    override fun playerHead(
        source: PlayerSkinSource,
        size: Int,
        showHat: Boolean,
        loading: Element?,
        failure: Element?,
        modifier: Modifier,
        key: ElementKey<*>?,
    ): Element = throw UnsupportedOperationException("Web capability is unavailable: playerHead")

    override fun imageBackground(
        modifier: Modifier,
        source: ImageSource,
        scale: ImageScale,
    ): Modifier = throw UnsupportedOperationException("Web capability is unavailable: imageBackground")

    override fun imageBackground(
        modifier: Modifier,
        source: ImageSource,
        border: Insets,
        centerMode: NineSliceCenterMode,
    ): Modifier = throw UnsupportedOperationException("Web capability is unavailable: imageBackground")

    override fun menuBackground(
        modifier: Modifier,
    ): Modifier = throw UnsupportedOperationException("Web capability is unavailable: menuBackground")

    override fun containerBackground(
        modifier: Modifier,
        rows: Int,
    ): Modifier = throw UnsupportedOperationException("Web capability is unavailable: containerBackground")

    private var nextIdentity = 0

    private fun primitive(
        label: String,
        kind: WebPresentation.Kind,
        style: TextStyle,
        enabled: Boolean,
        width: Int?,
        modifier: Modifier,
        key: ElementKey<*>?,
    ): Element {
        val canvas = document.createElement("canvas") as HTMLCanvasElement
        val context = checkNotNull(canvas.getContext("2d") as? CanvasRenderingContext2D)
        context.font = "16px sans-serif"
        val measured = ceil(context.measureText(label).width).toInt()
        val size = IntSize(width ?: measured, if (kind == WebPresentation.Kind.Button) 32 else 24)
        return WebPrimitiveElement(WebPresentation(0, kind, label, enabled, style), size, ::allocateIdentity, modifier, key)
    }

    private fun allocateIdentity(): Int {
        check(nextIdentity < Int.MAX_VALUE) { "Web node identity space was exhausted." }
        nextIdentity += 1
        return nextIdentity
    }

    private fun resolve(text: UiText): String =
        when (text) {
            is UiText.Literal -> {
                text.value
            }

            is UiText.Concatenated -> {
                text.parts.joinToString("") { resolve(it) }
            }

            is UiText.Translated -> {
                require(text.arguments.isEmpty()) { "Web translations with arguments require a text resolver." }
                when (val fallback = text.fallback) {
                    TranslationFallback.UseKey -> text.key
                    is TranslationFallback.Literal -> fallback.value
                }
            }

            is UiText.WithFont -> {
                throw UnsupportedOperationException("Web resource fonts require a font resolver.")
            }

            is UiText.Platform -> {
                throw UnsupportedOperationException("Web platform text requires a text resolver.")
            }
        }
}
