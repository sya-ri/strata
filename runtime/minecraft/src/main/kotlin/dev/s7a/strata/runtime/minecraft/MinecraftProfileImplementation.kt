package dev.s7a.strata.runtime.minecraft

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
import dev.s7a.strata.component.UiScope
import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.geometry.Insets
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.actionDispatcher
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontBackendFactory
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontEngine
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontSnapshot
import dev.s7a.strata.spi.ComponentEvaluator
import dev.s7a.strata.spi.ComponentRuntime
import dev.s7a.strata.spi.ComponentRuntimeBridge
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.TextLayout
import dev.s7a.strata.text.TextWrap
import dev.s7a.strata.text.UiText
import java.util.Collections

/**
 * Owns callback-lifetime profile construction, validation, retained evaluation, and dynamically scoped component dispatch.
 */
@OptIn(InternalStrataRuntimeApi::class)
@Suppress("TooManyFunctions") // These operations share one profile/context ownership boundary.
internal object MinecraftProfileImplementation {
    /**
     * Creates one complete immutable profile through a callback-lifetime builder.
     *
     * @param content declaration callback invoked synchronously on the calling thread.
     * @return a complete immutable profile.
     * @throws IllegalArgumentException when declarations are invalid, duplicated, or incomplete.
     * @throws Throwable when [content] fails; the exact callback failure escapes unchanged.
     */
    @JvmSynthetic
    fun create(content: MinecraftUiProfileBuilder.() -> Unit): MinecraftUiProfile {
        val builder = Builder()
        return try {
            builder.content()
            builder.snapshot()
        } finally {
            builder.close()
        }
    }

    /**
     * Creates one core-session evaluator from a complete profile and transferred content callback.
     *
     * The evaluator retains its complete profile and content callback until one evaluation or explicit release and then clears them.
     *
     * @param profile complete profile produced by this runtime.
     * @param content transferred application callback.
     * @param platform optional version services retained until evaluation or explicit release.
     * @return an owner-thread one-shot element evaluator.
     */
    @JvmSynthetic
    fun createEvaluator(
        profile: MinecraftUiProfile,
        content: UiScope.() -> Unit,
        platform: MinecraftUiPlatform? = null,
        textRenderer: MinecraftTextRenderer,
    ): () -> Element =
        when (profile) {
            is ProfileSnapshot -> Evaluator.create(profile, content, platform, textRenderer)
        }

    /**
     * Opens a host-owned text service from immutable profile data on the calling thread.
     * The returned service must be closed after the host tree and is never stored in the profile.
     *
     * @param profile immutable profile produced by this runtime.
     * @param backend optional backend factory required by resource-font profiles.
     * @return independently owned text service.
     * @throws IllegalArgumentException when a resource-font profile has no backend factory.
     * @throws Throwable when native backend initialization fails.
     */
    @JvmSynthetic
    fun createTextRenderer(
        profile: MinecraftUiProfile,
        backend: MinecraftFontBackendFactory?,
    ): MinecraftTextRenderer =
        when (profile) {
            is ProfileSnapshot -> {
                val fonts = profile.fonts
                if (fonts == null) MinecraftTextRenderer.legacy(profile.glyphSnapshot()) else MinecraftTextRenderer.fonts(MinecraftFontEngine(fonts, requireNotNull(backend) { "Resource fonts require a CPU font backend factory." }))
            }
        }

    /**
     * Releases a one-shot evaluator created by [createEvaluator].
     *
     * The operation is owner-thread confined and idempotent after evaluation or an earlier release.
     *
     * @param evaluator evaluator whose captured profile and application callback are released.
     * @throws IllegalStateException when the evaluator is foreign or release runs from another thread.
     */
    @JvmSynthetic
    fun releaseEvaluator(evaluator: () -> Element) {
        check(evaluator is Evaluator) { "Minecraft content evaluator was not created by this runtime." }
        evaluator.release()
    }

    @Suppress("TooManyFunctions")
    private class Builder : MinecraftUiProfileBuilder {
        private val ownerThread = Thread.currentThread()
        private val menuSize = IntSize(16, 16)
        private val containerSize = IntSize(256, 256)
        private val slotHighlightSize = IntSize(24, 24)
        private val glyphSize = IntSize(8, 8)
        private val buttonSize = IntSize(200, 20)
        private val listSeparatorSize = IntSize(32, 2)
        private val scrollbarSize = IntSize(6, 32)
        private val checkboxSize = IntSize(20, 20)
        private val sliderHandleSize = IntSize(8, 20)
        private val loadingIndicatorSize = IntSize(5, 6)
        private val progressBarBorderSize = IntSize(12, 12)
        private val progressBarFillSize = IntSize(6, 6)
        private val tooltipSpriteSize = IntSize(100, 100)
        private val glyphRange = 0x21..0x7E
        private val transparentWhite = ArgbColor(0x00FFFFFF)
        private val opaqueWhite = ArgbColor(-1)
        private val normalShadowColor = ArgbColor(-0xC0C0C1)
        private val inactiveForegroundColor = ArgbColor(-0x5F5F60)
        private val inactiveShadowColor = ArgbColor(-0xD7D7D8)
        private val textFieldForegroundColor = ArgbColor(-0x1F1F20)
        private val textFieldShadowColor = ArgbColor(-0xC7C7C8)
        private val textFieldDisabledForegroundColor = ArgbColor(-0x8F8F90)
        private val textFieldDisabledShadowColor = ArgbColor(-0xE3E3E4)
        private val containerForegroundColor = ArgbColor(-0xBFBFC0)
        private val glyphs = LinkedHashMap<Int, MinecraftGlyphSnapshot>()
        private var fonts: MinecraftFontSnapshot? = null
        private var active = true
        private var menu: DrawImage? = null
        private var containerBackground: DrawImage? = null
        private var slotHighlightBack: DrawImage? = null
        private var slotHighlightFront: DrawImage? = null
        private var listBackground: DrawImage? = null
        private var listHeaderSeparator: DrawImage? = null
        private var listFooterSeparator: DrawImage? = null
        private var scrollbarBackground: DrawImage? = null
        private var scrollbarThumb: DrawImage? = null
        private var checkbox: DrawImage? = null
        private var checkboxHighlighted: DrawImage? = null
        private var checkboxSelected: DrawImage? = null
        private var checkboxSelectedHighlighted: DrawImage? = null
        private var slider: MinecraftButtonSpriteSnapshot? = null
        private var sliderHighlighted: MinecraftButtonSpriteSnapshot? = null
        private var sliderHandle: DrawImage? = null
        private var sliderHandleHighlighted: DrawImage? = null
        private var loadingIndicator: DrawImage? = null
        private var progressBarBorder: DrawImage? = null
        private var progressBarFill: DrawImage? = null
        private var progressBarFull: DrawImage? = null
        private var horizontalProgressBar: MinecraftProgressBarStyle.Horizontal? = null
        private var tooltipBackground: DrawImage? = null
        private var tooltipFrame: DrawImage? = null
        private var legacyTooltip: MinecraftTooltipStyle.Legacy? = null
        private var normalTextField: DrawImage? = null
        private var highlightedTextField: DrawImage? = null
        private var normalButton: MinecraftButtonSpriteSnapshot? = null
        private var highlightedButton: MinecraftButtonSpriteSnapshot? = null
        private var disabledButton: MinecraftButtonSpriteSnapshot? = null

        override fun fonts(snapshot: MinecraftFontSnapshot) {
            checkUsable()
            require(fonts == null && glyphs.isEmpty()) { "Resource fonts and compatibility glyph declarations cannot be mixed or repeated." }
            fonts = snapshot
        }

        override fun menuBackground(image: DrawImage) {
            checkUsable()
            require(menu == null) { "Menu background was already declared." }
            require(image.size == menuSize) {
                "Menu background must be 16 by 16 pixels."
            }
            menu = image
        }

        override fun loadingIndicator(image: DrawImage) {
            checkUsable()
            require(loadingIndicator == null) { "LoadingIndicator sprite was already declared." }
            require(image.size == loadingIndicatorSize) { "LoadingIndicator sprite must be 5 by 6 pixels." }
            loadingIndicator = image
        }

        override fun progressBarBorder(image: DrawImage) {
            checkUsable()
            require(horizontalProgressBar == null) { "Horizontal ProgressBar sprites were already declared." }
            require(progressBarBorder == null) { "ProgressBar border sprite was already declared." }
            require(image.size == progressBarBorderSize) { "ProgressBar border sprite must be 12 by 12 pixels." }
            progressBarBorder = image
        }

        override fun progressBarFill(image: DrawImage) {
            checkUsable()
            require(horizontalProgressBar == null) { "Horizontal ProgressBar sprites were already declared." }
            require(progressBarFill == null) { "ProgressBar fill sprite was already declared." }
            require(image.size == progressBarFillSize) { "ProgressBar fill sprite must be 6 by 6 pixels." }
            progressBarFill = image
        }

        override fun progressBarFull(image: DrawImage) {
            checkUsable()
            require(horizontalProgressBar == null) { "Horizontal ProgressBar sprites were already declared." }
            require(progressBarFull == null) { "Completed ProgressBar fill sprite was already declared." }
            require(image.size == progressBarFillSize) { "Completed ProgressBar fill sprite must be 6 by 6 pixels." }
            progressBarFull = image
        }

        override fun horizontalProgressBar(
            background: DrawImage,
            fill: DrawImage,
        ) {
            checkUsable()
            require(progressBarBorder == null && progressBarFill == null && progressBarFull == null) {
                "Bundle ProgressBar sprites were already declared."
            }
            require(horizontalProgressBar == null) { "Horizontal ProgressBar sprites were already declared." }
            require(0 < background.size.width && 0 < background.size.height) { "Horizontal ProgressBar sprites must be nonempty." }
            require(background.size == fill.size) { "Horizontal ProgressBar sprites must have equal sizes." }
            horizontalProgressBar = MinecraftProgressBarStyle.Horizontal(background, fill)
        }

        override fun tooltipBackground(image: DrawImage) {
            checkUsable()
            require(legacyTooltip == null) { "Legacy tooltip colors were already declared." }
            require(tooltipBackground == null) { "Tooltip background sprite was already declared." }
            require(image.size == tooltipSpriteSize) { "Tooltip background sprite must be 100 by 100 pixels." }
            tooltipBackground = image
        }

        override fun tooltipFrame(image: DrawImage) {
            checkUsable()
            require(legacyTooltip == null) { "Legacy tooltip colors were already declared." }
            require(tooltipFrame == null) { "Tooltip frame sprite was already declared." }
            require(image.size == tooltipSpriteSize) { "Tooltip frame sprite must be 100 by 100 pixels." }
            tooltipFrame = image
        }

        override fun legacyTooltip(
            backgroundColor: ArgbColor,
            borderTop: ArgbColor,
            borderBottom: ArgbColor,
        ) {
            checkUsable()
            require(tooltipBackground == null && tooltipFrame == null) { "Tooltip sprites were already declared." }
            require(legacyTooltip == null) { "Legacy tooltip colors were already declared." }
            legacyTooltip = MinecraftTooltipStyle.Legacy(backgroundColor, borderTop, borderBottom)
        }

        override fun containerBackground(image: DrawImage) {
            checkUsable()
            require(containerBackground == null) { "Container background was already declared." }
            require(image.size == containerSize) { "Container background must be 256 by 256 pixels." }
            containerBackground = image
        }

        override fun slotHighlightBack(image: DrawImage) {
            checkUsable()
            require(slotHighlightBack == null) { "Slot back highlight was already declared." }
            require(image.size == slotHighlightSize) { "Slot highlights must be 24 by 24 pixels." }
            slotHighlightBack = image
        }

        override fun slotHighlightFront(image: DrawImage) {
            checkUsable()
            require(slotHighlightFront == null) { "Slot front highlight was already declared." }
            require(image.size == slotHighlightSize) { "Slot highlights must be 24 by 24 pixels." }
            slotHighlightFront = image
        }

        override fun listBackground(image: DrawImage) {
            checkUsable()
            require(listBackground == null) { "List background was already declared." }
            require(image.size == menuSize) { "List background must be 16 by 16 pixels." }
            listBackground = image
        }

        override fun listHeaderSeparator(image: DrawImage) {
            checkUsable()
            require(listHeaderSeparator == null) { "List header separator was already declared." }
            require(image.size == listSeparatorSize) { "List header separator must be 32 by 2 pixels." }
            listHeaderSeparator = image
        }

        override fun listFooterSeparator(image: DrawImage) {
            checkUsable()
            require(listFooterSeparator == null) { "List footer separator was already declared." }
            require(image.size == listSeparatorSize) { "List footer separator must be 32 by 2 pixels." }
            listFooterSeparator = image
        }

        override fun scrollbarBackground(image: DrawImage) {
            checkUsable()
            require(scrollbarBackground == null) { "Scrollbar background was already declared." }
            require(image.size == scrollbarSize) { "Scrollbar background must be 6 by 32 pixels." }
            scrollbarBackground = image
        }

        override fun scrollbarThumb(image: DrawImage) {
            checkUsable()
            require(scrollbarThumb == null) { "Scrollbar thumb was already declared." }
            require(image.size == scrollbarSize) { "Scrollbar thumb must be 6 by 32 pixels." }
            scrollbarThumb = image
        }

        override fun checkbox(image: DrawImage) {
            checkUsable()
            require(checkbox == null) { "Checkbox sprite was already declared." }
            require(image.size == checkboxSize) { "Checkbox sprites must be 20 by 20 pixels." }
            checkbox = image
        }

        override fun checkboxHighlighted(image: DrawImage) {
            checkUsable()
            require(checkboxHighlighted == null) { "Highlighted Checkbox sprite was already declared." }
            require(image.size == checkboxSize) { "Checkbox sprites must be 20 by 20 pixels." }
            checkboxHighlighted = image
        }

        override fun checkboxSelected(image: DrawImage) {
            checkUsable()
            require(checkboxSelected == null) { "Selected Checkbox sprite was already declared." }
            require(image.size == checkboxSize) { "Checkbox sprites must be 20 by 20 pixels." }
            checkboxSelected = image
        }

        override fun checkboxSelectedHighlighted(image: DrawImage) {
            checkUsable()
            require(checkboxSelectedHighlighted == null) { "Selected highlighted Checkbox sprite was already declared." }
            require(image.size == checkboxSize) { "Checkbox sprites must be 20 by 20 pixels." }
            checkboxSelectedHighlighted = image
        }

        override fun slider(image: DrawImage) {
            slider(image, 1, NineSliceCenterMode.Tiled)
        }

        override fun slider(
            image: DrawImage,
            border: Int,
            centerMode: NineSliceCenterMode,
        ) {
            checkUsable()
            require(slider == null) { "Slider sprite was already declared." }
            slider = createHorizontalWidgetSprite(image, border, centerMode, "Slider")
        }

        override fun sliderHighlighted(image: DrawImage) {
            sliderHighlighted(image, 1, NineSliceCenterMode.Tiled)
        }

        override fun sliderHighlighted(
            image: DrawImage,
            border: Int,
            centerMode: NineSliceCenterMode,
        ) {
            checkUsable()
            require(sliderHighlighted == null) { "Highlighted Slider sprite was already declared." }
            sliderHighlighted = createHorizontalWidgetSprite(image, border, centerMode, "Highlighted Slider")
        }

        override fun sliderHandle(image: DrawImage) {
            checkUsable()
            require(sliderHandle == null) { "Slider handle sprite was already declared." }
            require(image.size == sliderHandleSize) { "Slider handle sprites must be 8 by 20 pixels." }
            sliderHandle = image
        }

        override fun sliderHandleHighlighted(image: DrawImage) {
            checkUsable()
            require(sliderHandleHighlighted == null) { "Highlighted Slider handle sprite was already declared." }
            require(image.size == sliderHandleSize) { "Slider handle sprites must be 8 by 20 pixels." }
            sliderHandleHighlighted = image
        }

        override fun textFieldNormal(image: DrawImage) {
            checkUsable()
            require(normalTextField == null) { "Normal TextField sprite was already declared." }
            require(image.size == buttonSize) { "TextField sprites must be 200 by 20 pixels." }
            normalTextField = image
        }

        override fun textFieldHighlighted(image: DrawImage) {
            checkUsable()
            require(highlightedTextField == null) { "Highlighted TextField sprite was already declared." }
            require(image.size == buttonSize) { "TextField sprites must be 200 by 20 pixels." }
            highlightedTextField = image
        }

        override fun printableAsciiGlyph(
            codePoint: Int,
            mask: DrawImage,
        ) {
            checkUsable()
            require(fonts == null) { "Resource fonts and compatibility glyph declarations cannot be mixed." }
            require(codePoint in glyphRange) {
                "Glyph code point must be U+0021 through U+007E."
            }
            require((codePoint in glyphs).not()) { "Glyph code point was already declared." }
            require(mask.size == glyphSize) {
                "Glyph mask must be 8 by 8 pixels."
            }
            glyphs[codePoint] = createGlyph(mask)
        }

        override fun buttonNormal(
            image: DrawImage,
            border: Int,
            centerMode: NineSliceCenterMode,
        ) {
            checkUsable()
            require(normalButton == null) { "Normal Button sprite was already declared." }
            normalButton = createHorizontalWidgetSprite(image, border, centerMode, "Normal Button")
        }

        override fun buttonHighlighted(
            image: DrawImage,
            border: Int,
            centerMode: NineSliceCenterMode,
        ) {
            checkUsable()
            require(highlightedButton == null) { "Highlighted Button sprite was already declared." }
            highlightedButton = createHorizontalWidgetSprite(image, border, centerMode, "Highlighted Button")
        }

        override fun buttonDisabled(
            image: DrawImage,
            border: Int,
            centerMode: NineSliceCenterMode,
        ) {
            checkUsable()
            require(disabledButton == null) { "Disabled Button sprite was already declared." }
            disabledButton = createHorizontalWidgetSprite(image, border, centerMode, "Disabled Button")
        }

        fun snapshot(): ProfileSnapshot {
            checkUsable()
            if (fonts == null) {
                require(glyphs.size == glyphRange.count()) { "Every U+0021 through U+007E glyph must be declared." }
                for (codePoint in glyphRange) {
                    require(codePoint in glyphs) { "Every U+0021 through U+007E glyph must be declared." }
                }
            }
            return ProfileSnapshot.create(
                menuBackground = requireNotNull(menu) { "Menu background must be declared." },
                containerBackground = requireNotNull(containerBackground) { "Container background must be declared." },
                slotHighlightBack = requireNotNull(slotHighlightBack) { "Slot back highlight must be declared." },
                slotHighlightFront = requireNotNull(slotHighlightFront) { "Slot front highlight must be declared." },
                listBackground = requireNotNull(listBackground) { "List background must be declared." },
                listHeaderSeparator = requireNotNull(listHeaderSeparator) { "List header separator must be declared." },
                listFooterSeparator = requireNotNull(listFooterSeparator) { "List footer separator must be declared." },
                scrollbarBackground = requireNotNull(scrollbarBackground) { "Scrollbar background must be declared." },
                scrollbarThumb = requireNotNull(scrollbarThumb) { "Scrollbar thumb must be declared." },
                checkbox = requireNotNull(checkbox) { "Checkbox sprite must be declared." },
                checkboxHighlighted = requireNotNull(checkboxHighlighted) { "Highlighted Checkbox sprite must be declared." },
                checkboxSelected = requireNotNull(checkboxSelected) { "Selected Checkbox sprite must be declared." },
                checkboxSelectedHighlighted = requireNotNull(checkboxSelectedHighlighted) { "Selected highlighted Checkbox sprite must be declared." },
                slider = requireNotNull(slider) { "Slider sprite must be declared." },
                sliderHighlighted = requireNotNull(sliderHighlighted) { "Highlighted Slider sprite must be declared." },
                sliderHandle = requireNotNull(sliderHandle) { "Slider handle sprite must be declared." },
                sliderHandleHighlighted = requireNotNull(sliderHandleHighlighted) { "Highlighted Slider handle sprite must be declared." },
                loadingIndicator = requireNotNull(loadingIndicator) { "LoadingIndicator sprite must be declared." },
                progressBarStyle = createProgressBarStyle(),
                tooltipStyle = createTooltipStyle(),
                normalTextField = requireNotNull(normalTextField) { "Normal TextField sprite must be declared." },
                highlightedTextField = requireNotNull(highlightedTextField) { "Highlighted TextField sprite must be declared." },
                glyphs = glyphs,
                fonts = fonts,
                normalButton = requireNotNull(normalButton) { "Normal Button sprite must be declared." },
                highlightedButton = requireNotNull(highlightedButton) { "Highlighted Button sprite must be declared." },
                disabledButton = requireNotNull(disabledButton) { "Disabled Button sprite must be declared." },
            )
        }

        fun close() {
            active = false
            glyphs.clear()
            fonts = null
            menu = null
            containerBackground = null
            slotHighlightBack = null
            slotHighlightFront = null
            listBackground = null
            listHeaderSeparator = null
            listFooterSeparator = null
            scrollbarBackground = null
            scrollbarThumb = null
            checkbox = null
            checkboxHighlighted = null
            checkboxSelected = null
            checkboxSelectedHighlighted = null
            slider = null
            sliderHighlighted = null
            sliderHandle = null
            sliderHandleHighlighted = null
            loadingIndicator = null
            progressBarBorder = null
            progressBarFill = null
            progressBarFull = null
            horizontalProgressBar = null
            tooltipBackground = null
            tooltipFrame = null
            legacyTooltip = null
            normalTextField = null
            highlightedTextField = null
            normalButton = null
            highlightedButton = null
            disabledButton = null
        }

        private fun checkUsable() {
            check(Thread.currentThread() === ownerThread) { "Minecraft UI profile builder requires its creator thread." }
            check(active) { "Minecraft UI profile builder is closed." }
        }

        private fun createGlyph(mask: DrawImage): MinecraftGlyphSnapshot {
            val pixels = mask.copyArgb()
            val normalShadow = IntArray(pixels.size)
            val normalForeground = IntArray(pixels.size)
            val inactiveShadow = IntArray(pixels.size)
            val inactiveForeground = IntArray(pixels.size)
            val textFieldShadow = IntArray(pixels.size)
            val textFieldForeground = IntArray(pixels.size)
            val textFieldDisabledShadow = IntArray(pixels.size)
            val textFieldDisabledForeground = IntArray(pixels.size)
            val containerForeground = IntArray(pixels.size)
            var rightmost = -1
            for (index in pixels.indices) {
                when (ArgbColor(pixels[index])) {
                    transparentWhite -> {
                        normalShadow[index] = transparentWhite.value
                        normalForeground[index] = transparentWhite.value
                        inactiveShadow[index] = transparentWhite.value
                        inactiveForeground[index] = transparentWhite.value
                        textFieldShadow[index] = transparentWhite.value
                        textFieldForeground[index] = transparentWhite.value
                        textFieldDisabledShadow[index] = transparentWhite.value
                        textFieldDisabledForeground[index] = transparentWhite.value
                        containerForeground[index] = transparentWhite.value
                    }

                    opaqueWhite -> {
                        val x = index % glyphSize.width
                        if (rightmost < x) rightmost = x
                        normalShadow[index] = normalShadowColor.value
                        normalForeground[index] = opaqueWhite.value
                        inactiveShadow[index] = inactiveShadowColor.value
                        inactiveForeground[index] = inactiveForegroundColor.value
                        textFieldShadow[index] = textFieldShadowColor.value
                        textFieldForeground[index] = textFieldForegroundColor.value
                        textFieldDisabledShadow[index] = textFieldDisabledShadowColor.value
                        textFieldDisabledForeground[index] = textFieldDisabledForegroundColor.value
                        containerForeground[index] = containerForegroundColor.value
                    }

                    else -> {
                        throw IllegalArgumentException("Glyph masks must contain only transparent white or opaque white pixels.")
                    }
                }
            }
            return MinecraftGlyphSnapshot.create(
                advance = Math.addExact(rightmost, 2),
                normalShadow = createDrawImage(glyphSize, normalShadow),
                normalForeground = createDrawImage(glyphSize, normalForeground),
                inactiveShadow = createDrawImage(glyphSize, inactiveShadow),
                inactiveForeground = createDrawImage(glyphSize, inactiveForeground),
                textFieldShadow = createDrawImage(glyphSize, textFieldShadow),
                textFieldForeground = createDrawImage(glyphSize, textFieldForeground),
                textFieldDisabledShadow = createDrawImage(glyphSize, textFieldDisabledShadow),
                textFieldDisabledForeground = createDrawImage(glyphSize, textFieldDisabledForeground),
                containerForeground = createDrawImage(glyphSize, containerForeground),
            )
        }

        private fun createProgressBarStyle(): MinecraftProgressBarStyle {
            horizontalProgressBar?.let { style ->
                require(progressBarBorder == null && progressBarFill == null && progressBarFull == null) {
                    "A profile cannot mix horizontal and bundle ProgressBar sprites."
                }
                return style
            }
            return MinecraftProgressBarStyle.Bundle(
                requireNotNull(progressBarBorder) { "ProgressBar border sprite must be declared." },
                requireNotNull(progressBarFill) { "ProgressBar fill sprite must be declared." },
                requireNotNull(progressBarFull) { "Completed ProgressBar fill sprite must be declared." },
            )
        }

        private fun createTooltipStyle(): MinecraftTooltipStyle {
            legacyTooltip?.let { style ->
                require(tooltipBackground == null && tooltipFrame == null) {
                    "A profile cannot mix legacy tooltip colors and tooltip sprites."
                }
                return style
            }
            return MinecraftTooltipStyle.Sprites(
                requireNotNull(tooltipBackground) { "Tooltip background sprite must be declared." },
                requireNotNull(tooltipFrame) { "Tooltip frame sprite must be declared." },
            )
        }

        private fun createHorizontalWidgetSprite(
            image: DrawImage,
            border: Int,
            centerMode: NineSliceCenterMode,
            label: String,
        ): MinecraftButtonSpriteSnapshot {
            require(image.size == buttonSize) {
                "$label sprites must be 200 by 20 pixels."
            }
            require(0 < border) { "$label sprite border must be positive." }
            require(border < buttonSize.width / 2) {
                "$label sprite borders must leave a nonempty source center."
            }
            return MinecraftButtonSpriteSnapshot.create(image, border, centerMode)
        }
    }

    private class ProfileSnapshot private constructor(
        val menuBackground: DrawImage,
        val containerBackground: DrawImage,
        val slotHighlightBack: DrawImage,
        val slotHighlightFront: DrawImage,
        val listBackground: DrawImage,
        val listHeaderSeparator: DrawImage,
        val listFooterSeparator: DrawImage,
        val scrollbarBackground: DrawImage,
        val scrollbarThumb: DrawImage,
        val checkbox: DrawImage,
        val checkboxHighlighted: DrawImage,
        val checkboxSelected: DrawImage,
        val checkboxSelectedHighlighted: DrawImage,
        val slider: MinecraftButtonSpriteSnapshot,
        val sliderHighlighted: MinecraftButtonSpriteSnapshot,
        val sliderHandle: DrawImage,
        val sliderHandleHighlighted: DrawImage,
        val loadingIndicator: DrawImage,
        val progressBarStyle: MinecraftProgressBarStyle,
        val tooltipStyle: MinecraftTooltipStyle,
        val normalTextField: DrawImage,
        val highlightedTextField: DrawImage,
        glyphs: Map<Int, MinecraftGlyphSnapshot>,
        val fonts: MinecraftFontSnapshot?,
        val normalButton: MinecraftButtonSpriteSnapshot,
        val highlightedButton: MinecraftButtonSpriteSnapshot,
        val disabledButton: MinecraftButtonSpriteSnapshot,
    ) : MinecraftUiProfile {
        private val glyphs: Map<Int, MinecraftGlyphSnapshot> = Collections.unmodifiableMap(LinkedHashMap(glyphs))

        fun glyphSnapshot(): Map<Int, MinecraftGlyphSnapshot> = glyphs

        companion object {
            /**
             * Creates one private complete profile snapshot.
             *
             * @param menuBackground immutable menu image.
             * @param containerBackground immutable generic-container image.
             * @param slotHighlightBack immutable back-highlight image.
             * @param slotHighlightFront immutable front-highlight image.
             * @param glyphs complete printable-ASCII glyph map.
             * @param listBackground immutable menu-list background image.
             * @param listHeaderSeparator immutable menu-list header separator.
             * @param listFooterSeparator immutable menu-list footer separator.
             * @param scrollbarBackground immutable scrollbar-track sprite.
             * @param scrollbarThumb immutable scrollbar-thumb sprite.
             * @param normalTextField normal TextField sprite.
             * @param highlightedTextField focused TextField sprite.
             * @param normalButton normal sprite policy.
             * @param highlightedButton highlighted sprite policy.
             * @param disabledButton disabled sprite policy.
             * @return a private immutable profile implementation.
             */
            @JvmSynthetic
            internal fun create(
                menuBackground: DrawImage,
                containerBackground: DrawImage,
                slotHighlightBack: DrawImage,
                slotHighlightFront: DrawImage,
                listBackground: DrawImage,
                listHeaderSeparator: DrawImage,
                listFooterSeparator: DrawImage,
                scrollbarBackground: DrawImage,
                scrollbarThumb: DrawImage,
                checkbox: DrawImage,
                checkboxHighlighted: DrawImage,
                checkboxSelected: DrawImage,
                checkboxSelectedHighlighted: DrawImage,
                slider: MinecraftButtonSpriteSnapshot,
                sliderHighlighted: MinecraftButtonSpriteSnapshot,
                sliderHandle: DrawImage,
                sliderHandleHighlighted: DrawImage,
                loadingIndicator: DrawImage,
                progressBarStyle: MinecraftProgressBarStyle,
                tooltipStyle: MinecraftTooltipStyle,
                normalTextField: DrawImage,
                highlightedTextField: DrawImage,
                glyphs: Map<Int, MinecraftGlyphSnapshot>,
                fonts: MinecraftFontSnapshot?,
                normalButton: MinecraftButtonSpriteSnapshot,
                highlightedButton: MinecraftButtonSpriteSnapshot,
                disabledButton: MinecraftButtonSpriteSnapshot,
            ): ProfileSnapshot =
                ProfileSnapshot(
                    menuBackground,
                    containerBackground,
                    slotHighlightBack,
                    slotHighlightFront,
                    listBackground,
                    listHeaderSeparator,
                    listFooterSeparator,
                    scrollbarBackground,
                    scrollbarThumb,
                    checkbox,
                    checkboxHighlighted,
                    checkboxSelected,
                    checkboxSelectedHighlighted,
                    slider,
                    sliderHighlighted,
                    sliderHandle,
                    sliderHandleHighlighted,
                    loadingIndicator,
                    progressBarStyle,
                    tooltipStyle,
                    normalTextField,
                    highlightedTextField,
                    glyphs,
                    fonts,
                    normalButton,
                    highlightedButton,
                    disabledButton,
                )
        }
    }

    private class Context private constructor(
        initialProfile: ProfileSnapshot,
        initialPlatform: MinecraftUiPlatform?,
        initialTextRenderer: MinecraftTextRenderer,
    ) : ComponentRuntime {
        private val ownerThread = Thread.currentThread()
        private var profile: ProfileSnapshot? = initialProfile
        private var platform: MinecraftUiPlatform? = initialPlatform
        private var textRenderer: MinecraftTextRenderer? = initialTextRenderer

        override fun retainEvaluator(): ComponentEvaluator {
            val retainedProfile = requireProfile()
            val retainedPlatform = platform
            val retainedTextRenderer = requireTextRenderer()
            val evaluatorOwner = ownerThread
            return ComponentEvaluator { deferredContent ->
                check(Thread.currentThread() === evaluatorOwner) {
                    "Deferred Minecraft component evaluation must run on its owner thread."
                }
                val context = create(retainedProfile, retainedPlatform, retainedTextRenderer)
                try {
                    ComponentRuntimeBridge.evaluate(context, deferredContent)
                } finally {
                    context.close()
                }
            }
        }

        override fun loadingIndicator(
            size: IntSize,
            modifier: Modifier,
            key: ElementKey<*>?,
        ): Element = MinecraftLoadingIndicatorElement.create(requireProfile().loadingIndicator, size, modifier, key)

        override fun progressBar(
            progress: Double,
            size: IntSize,
            modifier: Modifier,
            key: ElementKey<*>?,
        ): Element {
            val currentProfile = requireProfile()
            return MinecraftProgressBarElement.create(
                currentProfile.progressBarStyle,
                progress,
                size,
                modifier,
                key,
            )
        }

        override fun tooltip(
            modifier: Modifier,
            text: UiText,
            delayMillis: Long,
        ): Modifier {
            val currentProfile = requireProfile()
            val delayNanos = Math.multiplyExact(delayMillis, 1_000_000L)
            return modifier.then(
                createMinecraftTooltipModifier(
                    requireTextRenderer().create(text, TextStyle.Normal),
                    currentProfile.tooltipStyle,
                    delayNanos,
                ),
            )
        }

        override fun checkbox(
            label: UiText,
            state: CheckboxState,
            width: Int,
            enabled: Boolean,
            modifier: Modifier,
            key: ElementKey<*>?,
        ): Element {
            val currentProfile = requireProfile()
            require(24 < width) { "Minecraft Checkbox width must leave room for its box and label." }
            val normalText = requireTextRenderer().create(label, TextStyle.Normal)
            val inactiveText = requireTextRenderer().create(label, TextStyle.Inactive)
            return createMinecraftCheckboxElement(
                normal = currentProfile.checkbox,
                highlighted = currentProfile.checkboxHighlighted,
                selected = currentProfile.checkboxSelected,
                selectedHighlighted = currentProfile.checkboxSelectedHighlighted,
                normalText = normalText,
                inactiveText = inactiveText,
                label = normalText.text,
                state = state,
                width = width,
                enabled = enabled,
                actions = modifier.actionDispatcher(),
                modifier = modifier,
                key = key,
            )
        }

        override fun cycleButton(
            state: CycleButtonState<*>,
            labels: List<UiText>,
            width: Int,
            enabled: Boolean,
            modifier: Modifier,
            key: ElementKey<*>?,
        ): Element {
            val currentProfile = requireProfile()
            require(0 < width && width <= 200) { "Minecraft CycleButton width must be positive and no larger than 200." }
            require(labels.size == state.values.size) { "CycleButton labels must match its values." }
            val runs =
                labels.map { label ->
                    requireTextRenderer().create(label, TextStyle.Normal) to
                        requireTextRenderer().create(label, TextStyle.Inactive)
                }
            return createMinecraftCycleButtonElement(
                currentProfile.normalButton,
                currentProfile.highlightedButton,
                currentProfile.disabledButton,
                state,
                runs,
                width,
                enabled,
                modifier.actionDispatcher(),
                modifier,
                key,
            )
        }

        override fun slider(
            label: UiText,
            state: SliderState,
            width: Int,
            enabled: Boolean,
            modifier: Modifier,
            key: ElementKey<*>?,
        ): Element {
            val currentProfile = requireProfile()
            require(8 < width && width <= 200) { "Minecraft Slider width must be greater than 8 and no larger than 200." }
            val normalText = requireTextRenderer().create(label, TextStyle.Normal)
            val inactiveText = requireTextRenderer().create(label, TextStyle.Inactive)
            return createMinecraftSliderElement(
                normalTrack = currentProfile.slider,
                highlightedTrack = currentProfile.sliderHighlighted,
                normalHandle = currentProfile.sliderHandle,
                highlightedHandle = currentProfile.sliderHandleHighlighted,
                normalText = normalText,
                inactiveText = inactiveText,
                label = normalText.text,
                state = state,
                width = width,
                enabled = enabled,
                actions = modifier.actionDispatcher(),
                modifier = modifier,
                key = key,
            )
        }

        override fun menuBackground(modifier: Modifier): Modifier = modifier.then(createMinecraftMenuBackgroundModifier(requireProfile().menuBackground))

        override fun containerBackground(
            modifier: Modifier,
            rows: Int,
        ): Modifier = modifier.then(createMinecraftContainerBackgroundModifier(requireProfile().containerBackground, rows))

        override fun slot(
            binding: SlotBinding?,
            highlightable: Boolean,
            item: Element?,
            modifier: Modifier,
            key: ElementKey<*>?,
        ): Element {
            val currentProfile = requireProfile()
            val currentPlatform =
                if (binding == null) {
                    null
                } else {
                    checkNotNull(platform) { "Bound inventory Slots require a versioned Minecraft platform host." }
                }
            return createMinecraftSlotElement(
                currentProfile.slotHighlightBack,
                currentProfile.slotHighlightFront,
                highlightable,
                item,
                currentPlatform,
                binding,
                modifier,
                key,
            )
        }

        override fun text(
            text: UiText,
            style: TextStyle,
            modifier: Modifier,
            key: ElementKey<*>?,
        ): Element =
            createMinecraftTextElement(
                requireTextRenderer().create(text, style),
                modifier,
                key,
            )

        override fun text(
            text: UiText,
            layout: TextLayout,
            style: TextStyle,
            modifier: Modifier,
            key: ElementKey<*>?,
        ): Element =
            when (layout) {
                TextLayout.SingleLine -> text(text, style, modifier, key)
                is TextLayout.Multiline -> createMinecraftMultilineTextElement(text, requireTextRenderer(), layout, style, modifier, key)
            }

        override fun button(
            label: UiText,
            width: Int,
            enabled: Boolean,
            modifier: Modifier,
            key: ElementKey<*>?,
        ): Element {
            val currentProfile = requireProfile()
            require(0 < width && width <= 200) { "Minecraft Button width must be positive and no larger than 200." }
            val normalText = requireTextRenderer().create(label, TextStyle.Normal)
            val inactiveText = requireTextRenderer().create(label, TextStyle.Inactive)
            return createMinecraftPointerButtonElement(
                currentProfile.normalButton,
                currentProfile.highlightedButton,
                currentProfile.disabledButton,
                normalText,
                inactiveText,
                normalText.text,
                width,
                enabled,
                modifier,
                key,
            )
        }

        override fun textField(
            state: TextFieldState,
            size: IntSize,
            enabled: Boolean,
            style: TextStyle,
            modifier: Modifier,
            key: ElementKey<*>?,
        ): Element = textField(state, size, enabled, style, MinecraftTextRenderer.defaultFont, modifier, key)

        override fun textField(
            state: TextFieldState,
            size: IntSize,
            enabled: Boolean,
            style: TextStyle,
            font: ResourceId,
            modifier: Modifier,
            key: ElementKey<*>?,
        ): Element {
            val currentProfile = requireProfile()
            return createMinecraftTextFieldElement(
                currentProfile.normalTextField,
                currentProfile.highlightedTextField,
                requireTextRenderer(),
                font,
                state,
                size,
                enabled,
                style,
                modifier,
                key,
            )
        }

        override fun textArea(
            state: TextAreaState,
            viewport: TextAreaViewport,
            enabled: Boolean,
            style: TextStyle,
            wrap: TextWrap,
            lineSpacing: Int,
            modifier: Modifier,
            key: ElementKey<*>?,
        ): Element = textArea(state, viewport, enabled, style, MinecraftTextRenderer.defaultFont, wrap, lineSpacing, modifier, key)

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
        ): Element {
            val currentProfile = requireProfile()
            return createMinecraftTextAreaElement(
                currentProfile.normalTextField,
                currentProfile.highlightedTextField,
                requireTextRenderer(),
                font,
                state,
                viewport,
                enabled,
                style,
                wrap,
                lineSpacing,
                modifier,
                key,
            )
        }

        override fun tab(
            label: UiText,
            selected: Boolean,
            width: Int,
            enabled: Boolean,
            indicator: TabSelectionIndicator,
            customIndicator: Element?,
            modifier: Modifier,
            key: ElementKey<*>?,
        ): Element {
            val currentProfile = requireProfile()
            require(0 < width && width <= 200) { "Minecraft Tab width must be positive and no larger than 200." }
            val normalText = requireTextRenderer().create(label, TextStyle.Normal)
            val inactiveText = requireTextRenderer().create(label, TextStyle.Inactive)
            return createMinecraftTabElement(
                normalSprite = currentProfile.normalButton,
                highlightedSprite = currentProfile.highlightedButton,
                disabledSprite = currentProfile.disabledButton,
                normalText = normalText,
                inactiveText = inactiveText,
                label = normalText.text,
                width = width,
                enabled = enabled,
                selected = selected,
                underlined = selected && indicator === TabSelectionIndicator.Underline,
                customIndicator = customIndicator,
                modifier = modifier,
                key = key,
            )
        }

        override fun scrollArea(
            state: ScrollState,
            content: Element,
            scrollRate: Int,
            modifier: Modifier,
            key: ElementKey<*>?,
        ): Element {
            val currentProfile = requireProfile()
            require(0 < scrollRate) { "Minecraft Scroll rate must be positive." }
            return createMinecraftScrollElement(
                currentProfile.listBackground,
                currentProfile.listHeaderSeparator,
                currentProfile.listFooterSeparator,
                state,
                scrollRate,
                content,
                modifier,
                key,
            )
        }

        override fun scrollbar(
            state: ScrollState,
            modifier: Modifier,
            key: ElementKey<*>?,
        ): Element {
            val currentProfile = requireProfile()
            return createMinecraftScrollbarElement(
                currentProfile.scrollbarBackground,
                currentProfile.scrollbarThumb,
                state,
                modifier,
                key,
            )
        }

        override fun image(
            source: ImageSource,
            sourceRegion: IntRect?,
            size: IntSize?,
            modifier: Modifier,
            key: ElementKey<*>?,
        ): Element {
            val resolved = resolveImage(source)
            val region = sourceRegion ?: IntRect(0, 0, resolved.size.width, resolved.size.height)
            val destination = size ?: IntSize(region.width, region.height)
            return createMinecraftImageElement(resolved, region, destination, modifier, key)
        }

        override fun playerHead(
            source: PlayerSkinSource,
            size: Int,
            showHat: Boolean,
            loading: Element?,
            failure: Element?,
            modifier: Modifier,
            key: ElementKey<*>?,
        ): Element =
            when (source) {
                is PlayerSkinSource.Pixels -> {
                    createMinecraftPlayerHeadElement(source.skin, size, showHat, modifier, key)
                }

                PlayerSkinSource.CurrentPlayer,
                is PlayerSkinSource.Name,
                is PlayerSkinSource.Uuid,
                -> {
                    val currentPlatform = checkNotNull(platform) { "Player skin lookups require a versioned Minecraft platform host." }
                    createMinecraftAsyncPlayerHeadElement(
                        currentPlatform,
                        source,
                        size,
                        showHat,
                        loading,
                        failure,
                        modifier,
                        key,
                    )
                }
            }

        override fun imageBackground(
            modifier: Modifier,
            source: ImageSource,
            scale: ImageScale,
        ): Modifier = modifier.then(createMinecraftImageBackgroundModifier(resolveImage(source), scale))

        override fun imageBackground(
            modifier: Modifier,
            source: ImageSource,
            border: Insets,
            centerMode: NineSliceCenterMode,
        ): Modifier = modifier.then(createMinecraftNineSliceImageBackgroundModifier(resolveImage(source), border, centerMode))

        private fun resolveImage(source: ImageSource): DrawImage =
            when (source) {
                is ImageSource.Pixels -> {
                    source.image
                }

                is ImageSource.Resource -> {
                    val currentPlatform = checkNotNull(platform) { "Resource images require a versioned Minecraft platform host." }
                    currentPlatform.image(source.id)
                }
            }

        private fun requireTextRenderer(): MinecraftTextRenderer {
            requireProfile()
            return checkNotNull(textRenderer) { "Minecraft UI context is closed." }
        }

        fun close() {
            check(Thread.currentThread() === ownerThread) { "Minecraft UI context requires its creator thread." }
            profile = null
            platform = null
            textRenderer = null
        }

        private fun requireProfile(): ProfileSnapshot {
            check(Thread.currentThread() === ownerThread) { "Minecraft UI context requires its creator thread." }
            return checkNotNull(profile) { "Minecraft UI context is closed." }
        }

        companion object {
            /**
             * Creates one private callback-lifetime context.
             *
             * @param profile complete immutable profile available during evaluation.
             * @param platform optional version services available only during evaluation.
             * @return an active context bound to the current thread.
             */
            @JvmSynthetic
            internal fun create(
                profile: ProfileSnapshot,
                platform: MinecraftUiPlatform?,
                textRenderer: MinecraftTextRenderer,
            ): Context = Context(profile, platform, textRenderer)
        }
    }

    private class Evaluator private constructor(
        initialProfile: ProfileSnapshot,
        initialContent: UiScope.() -> Unit,
        initialPlatform: MinecraftUiPlatform?,
        initialTextRenderer: MinecraftTextRenderer,
    ) : () -> Element {
        private val ownerThread = Thread.currentThread()
        private var profile: ProfileSnapshot? = initialProfile
        private var content: (UiScope.() -> Unit)? = initialContent
        private var platform: MinecraftUiPlatform? = initialPlatform
        private var textRenderer: MinecraftTextRenderer? = initialTextRenderer

        override fun invoke(): Element {
            check(Thread.currentThread() === ownerThread) { "Minecraft content evaluation requires the host owner thread." }
            val currentProfile = checkNotNull(profile) { "Minecraft screen content was already evaluated." }
            val currentContent = checkNotNull(content) { "Minecraft screen content was already evaluated." }
            val currentPlatform = platform
            val currentTextRenderer = checkNotNull(textRenderer) { "Minecraft screen content was already evaluated." }
            profile = null
            content = null
            platform = null
            textRenderer = null
            val context = Context.create(currentProfile, currentPlatform, currentTextRenderer)
            return try {
                ComponentRuntimeBridge.evaluate(context, currentContent)
            } finally {
                context.close()
            }
        }

        @Suppress("unused")
        fun release() {
            check(Thread.currentThread() === ownerThread) { "Minecraft content release requires the host owner thread." }
            profile = null
            content = null
            platform = null
            textRenderer = null
        }

        companion object {
            /**
             * Creates one private owner-thread evaluator.
             *
             * @param profile complete profile retained until evaluation or release.
             * @param content application content retained until evaluation or release.
             * @param platform optional version services retained until evaluation or release.
             * @return a one-shot evaluator.
             */
            @JvmSynthetic
            internal fun create(
                profile: ProfileSnapshot,
                content: UiScope.() -> Unit,
                platform: MinecraftUiPlatform?,
                textRenderer: MinecraftTextRenderer,
            ): Evaluator = Evaluator(profile, content, platform, textRenderer)
        }
    }
}
