@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.web

import dev.s7a.strata.component.CheckboxState
import dev.s7a.strata.component.CycleButtonState
import dev.s7a.strata.component.ImageScale
import dev.s7a.strata.component.ImageSource
import dev.s7a.strata.component.NineSliceCenterMode
import dev.s7a.strata.component.Observe
import dev.s7a.strata.component.PlayerSkinSource
import dev.s7a.strata.component.ScrollState
import dev.s7a.strata.component.SliderState
import dev.s7a.strata.component.SlotBinding
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.TabSelectionIndicator
import dev.s7a.strata.component.TextAreaState
import dev.s7a.strata.component.TextAreaViewport
import dev.s7a.strata.component.TextFieldState
import dev.s7a.strata.component.TextInputAppearance
import dev.s7a.strata.component.TextStyle
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.geometry.Insets
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.padding
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.spi.ComponentEvaluator
import dev.s7a.strata.spi.ComponentRuntime
import dev.s7a.strata.spi.ComponentRuntimeBridge
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.spi.TooltipModifier
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
internal class WebComponentRuntime(
    private val theme: WebTheme = WebTheme.Native,
    internal val resources: WebUiResources = WebUiResources(),
) : ComponentRuntime {
    override fun retainEvaluator(): ComponentEvaluator = ComponentEvaluator { content -> ComponentRuntimeBridge.evaluate(this, content) }

    override fun tooltip(
        modifier: Modifier,
        text: UiText,
        delayMillis: Long,
    ): Modifier = tooltip(modifier, text, true, delayMillis)

    override fun tooltip(
        modifier: Modifier,
        content: Element,
        padding: Boolean,
        delayMillis: Long,
    ): Modifier {
        val decorated =
            evaluateComponentTree {
                Stack(modifier = Modifier.then(WebTooltipSurface(theme)).padding(if (padding) 3 else 0)) {
                    element(content)
                }
            }
        return modifier.then(TooltipModifier(decorated, delayMillis))
    }

    override fun progressBar(
        progress: Double,
        size: IntSize,
        modifier: Modifier,
        key: ElementKey<*>?,
    ): Element {
        require(0 < size.width && 0 < size.height) { "ProgressBar size must be positive." }
        return WebPrimitiveElement(
            WebPresentation(0, WebPresentation.Kind.Progress, "${(progress * 100.0).toInt()}%", true, TextStyle.Normal, progress),
            size,
            ::allocateIdentity,
            modifier,
            key,
        )
    }

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
    ): Element = primitive(resolve(text), WebPresentation.Kind.Text, style, true, null, modifier, key, fontOf(text))

    override fun text(
        text: UiText,
        layout: TextLayout,
        style: TextStyle,
        modifier: Modifier,
        key: ElementKey<*>?,
    ): Element =
        when (layout) {
            TextLayout.SingleLine -> text(text, style, modifier, key)
            is TextLayout.Multiline -> WebTextElement(resolve(text), fontOf(text), if (resources.defaultFont == null) 24 else 9, style, layout, this, modifier, key)
        }

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
    ): Element = editor(WebEditorState.Field(state), size, enabled, style, TextInputAppearance.Default, editingFont, false, 0, modifier, key)

    override fun textField(
        state: TextFieldState,
        size: IntSize,
        appearance: TextInputAppearance,
        enabled: Boolean,
        style: TextStyle,
        modifier: Modifier,
        key: ElementKey<*>?,
    ): Element =
        editor(
            WebEditorState.Field(state),
            size,
            enabled,
            style,
            appearance,
            editingFont,
            false,
            0,
            modifier,
            key,
        )

    override fun textField(
        state: TextFieldState,
        size: IntSize,
        enabled: Boolean,
        style: TextStyle,
        font: ResourceId,
        modifier: Modifier,
        key: ElementKey<*>?,
    ): Element =
        editor(
            WebEditorState.Field(state),
            size,
            enabled,
            style,
            TextInputAppearance.Default,
            resolveFont(font),
            false,
            0,
            modifier,
            key,
        )

    override fun textField(
        state: TextFieldState,
        size: IntSize,
        appearance: TextInputAppearance,
        enabled: Boolean,
        style: TextStyle,
        font: ResourceId,
        modifier: Modifier,
        key: ElementKey<*>?,
    ): Element =
        editor(
            WebEditorState.Field(state),
            size,
            enabled,
            style,
            appearance,
            resolveFont(font),
            false,
            0,
            modifier,
            key,
        )

    override fun textArea(
        state: TextAreaState,
        viewport: TextAreaViewport,
        enabled: Boolean,
        style: TextStyle,
        wrap: TextWrap,
        lineSpacing: Int,
        modifier: Modifier,
        key: ElementKey<*>?,
    ): Element =
        editor(
            WebEditorState.Area(state),
            editorSize(viewport, lineSpacing),
            enabled,
            style,
            TextInputAppearance.Default,
            editingFont,
            wrap != TextWrap.None,
            lineSpacing,
            modifier,
            key,
        )

    override fun textArea(
        state: TextAreaState,
        viewport: TextAreaViewport,
        appearance: TextInputAppearance,
        enabled: Boolean,
        style: TextStyle,
        wrap: TextWrap,
        lineSpacing: Int,
        modifier: Modifier,
        key: ElementKey<*>?,
    ): Element =
        editor(
            WebEditorState.Area(state),
            editorSize(viewport, lineSpacing),
            enabled,
            style,
            appearance,
            editingFont,
            wrap != TextWrap.None,
            lineSpacing,
            modifier,
            key,
        )

    override fun textArea(
        state: TextAreaState,
        viewport: TextAreaViewport,
        enabled: Boolean,
        style: TextStyle,
        font: ResourceId,
        wrap: TextWrap,
        lineSpacing: Int,
        modifier: Modifier,
        key: ElementKey<*>?,
    ): Element =
        editor(
            WebEditorState.Area(state),
            editorSize(viewport, lineSpacing),
            enabled,
            style,
            TextInputAppearance.Default,
            resolveFont(font),
            wrap != TextWrap.None,
            lineSpacing,
            modifier,
            key,
        )

    override fun textArea(
        state: TextAreaState,
        viewport: TextAreaViewport,
        appearance: TextInputAppearance,
        enabled: Boolean,
        style: TextStyle,
        font: ResourceId,
        wrap: TextWrap,
        lineSpacing: Int,
        modifier: Modifier,
        key: ElementKey<*>?,
    ): Element =
        editor(
            WebEditorState.Area(state),
            editorSize(viewport, lineSpacing),
            enabled,
            style,
            appearance,
            resolveFont(font),
            wrap != TextWrap.None,
            lineSpacing,
            modifier,
            key,
        )

    private fun editorSize(
        viewport: TextAreaViewport,
        spacing: Int,
    ): IntSize =
        when (viewport) {
            is TextAreaViewport.Size -> viewport.size
            is TextAreaViewport.Lines -> IntSize(viewport.width, viewport.lines * (9 + spacing) - spacing + 8)
        }

    private fun resolveFont(font: ResourceId): String = checkNotNull(resources.fonts[font]) { "Web resource font is not registered: $font" }.css

    private fun editor(
        state: WebEditorState,
        size: IntSize,
        enabled: Boolean,
        style: TextStyle,
        appearance: TextInputAppearance,
        font: String,
        wrap: Boolean,
        spacing: Int,
        modifier: Modifier,
        key: ElementKey<*>?,
    ): Element {
        if (appearance is TextInputAppearance.Custom) {
            listOf(appearance.normal, appearance.focused, appearance.disabled).forEach { source ->
                WebImageBackground(resolveImage(source), border = appearance.border)
            }
        }
        return WebEditorElement(state, size, enabled, style, appearance, font, wrap, spacing, this, modifier, key)
    }

    /**
     * Current retained editors, registered only while attached and removed before state release.
     */
    internal val editors: MutableMap<Int, WebEditorElement.Node> = LinkedHashMap()

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
    ): Element = WebScrollElement(state, scrollRate, content, modifier, key)

    override fun scrollbar(
        state: ScrollState,
        modifier: Modifier,
        key: ElementKey<*>?,
    ): Element = WebScrollbarElement(state, modifier, key)

    override fun image(
        source: ImageSource,
        sourceRegion: IntRect?,
        size: IntSize?,
        modifier: Modifier,
        key: ElementKey<*>?,
    ): Element {
        val image = resolveImage(source)
        val region = sourceRegion ?: IntRect(0, 0, image.size.width, image.size.height)
        return WebImageElement(image, region, size ?: IntSize(region.width, region.height), false, modifier, key)
    }

    override fun playerHead(
        source: PlayerSkinSource,
        size: Int,
        showHat: Boolean,
        loading: Element?,
        failure: Element?,
        modifier: Modifier,
        key: ElementKey<*>?,
    ): Element {
        fun head(
            image: DrawImage,
            modifier: Modifier,
            key: ElementKey<*>?,
        ): Element {
            require(image.size == IntSize(64, 64)) { "PlayerHead requires a normalized 64 by 64 skin." }
            if (size % 8 != 0) {
                val face = sampleWebHeadRegion(image, IntRect(8, 8, 16, 16), size)
                val hat = if (showHat) sampleWebHeadRegion(image, IntRect(40, 8, 48, 16), size) else null
                return evaluateComponentTree {
                    Stack(modifier, key = key) {
                        element(WebImageElement(face, IntRect(0, 0, size, size), IntSize(size, size), false, Modifier, null))
                        if (hat != null) element(WebImageElement(hat, IntRect(0, 0, size, size), IntSize(size, size), false, Modifier, null))
                    }
                }
            }
            return WebImageElement(image, IntRect(8, 8, 16, 16), IntSize(size, size), showHat, modifier, key)
        }
        if (source is PlayerSkinSource.Pixels) return head(source.skin, modifier, key)
        val resolved = checkNotNull(resources.skins) { "PlayerHead lookup requires a Web skin source resolver." }(source)
        return evaluateComponentTree {
            Observe(resolved, modifier.size(size, size), key) { skin ->
                when (skin) {
                    WebPlayerSkin.Loading -> if (loading != null) element(loading) else Spacer(Modifier.size(size, size))
                    WebPlayerSkin.Failed -> if (failure != null) element(failure) else Spacer(Modifier.size(size, size))
                    is WebPlayerSkin.Ready -> element(head(skin.image, Modifier, null))
                }
            }
        }
    }

    override fun imageBackground(
        modifier: Modifier,
        source: ImageSource,
        scale: ImageScale,
    ): Modifier = modifier.then(WebImageBackground(resolveImage(source), scale))

    override fun imageBackground(
        modifier: Modifier,
        source: ImageSource,
        border: Insets,
        centerMode: NineSliceCenterMode,
    ): Modifier = modifier.then(WebImageBackground(resolveImage(source), border = border, centerMode = centerMode))

    override fun menuBackground(
        modifier: Modifier,
    ): Modifier = throw UnsupportedOperationException("Web capability is unavailable: menuBackground")

    override fun containerBackground(
        modifier: Modifier,
        rows: Int,
    ): Modifier = throw UnsupportedOperationException("Web capability is unavailable: containerBackground")

    private val editingFont: String get() = resources.defaultFont?.css ?: theme.font.replace("16px", "9px")

    private var nextIdentity = 0

    private fun primitive(
        label: String,
        kind: WebPresentation.Kind,
        style: TextStyle,
        enabled: Boolean,
        width: Int?,
        modifier: Modifier,
        key: ElementKey<*>?,
        font: String = resources.defaultFont?.css ?: theme.font,
    ): Element {
        val canvas = document.createElement("canvas") as HTMLCanvasElement
        val context = checkNotNull(canvas.getContext("2d") as? CanvasRenderingContext2D)
        context.font = font
        val measured = ceil(context.measureText(label).width).toInt()
        val size =
            IntSize(
                width ?: measured,
                if (kind == WebPresentation.Kind.Button) {
                    32
                } else if (resources.defaultFont != null) {
                    9
                } else {
                    24
                },
            )
        return WebPrimitiveElement(WebPresentation(0, kind, label, enabled, style, font = font), size, ::allocateIdentity, modifier, key)
    }

    /**
     * Allocates a host-local presentation identity only when a retained node is created.
     */
    internal fun allocateIdentity(): Int {
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
                resolveFont(text.font)
                resolve(text.text)
            }

            is UiText.Platform -> {
                throw UnsupportedOperationException("Web platform text requires a text resolver.")
            }
        }

    /**
     * Resolves immutable resource pixels without doing I/O during evaluation.
     */
    internal fun resolveImage(source: ImageSource): DrawImage =
        when (source) {
            is ImageSource.Pixels -> source.image
            is ImageSource.Resource -> checkNotNull(resources.images[source.id]) { "Web image resource is not registered: ${source.id}" }
        }

    private fun fontOf(text: UiText): String =
        when (text) {
            is UiText.WithFont -> resolveFont(text.font)
            else -> resources.defaultFont?.css ?: theme.font
        }
}
